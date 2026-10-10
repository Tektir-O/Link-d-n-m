"""Self-hosted YouTube video link to MP3 service for permitted media."""
import hmac
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
from collections import deque
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

VIDEO_ID = re.compile(r"^[a-zA-Z0-9_-]{11}$")
MAX_MP3_BYTES = 100 * 1024 * 1024
MAX_REQUEST_BYTES = 2048
MAX_UPLOAD_BYTES = 60 * 1024 * 1024
UPLOAD_MIME = {
    "video/mp4": ".mp4", "video/quicktime": ".mov", "video/webm": ".webm",
    "video/x-matroska": ".mkv", "video/3gpp": ".3gp", "audio/mp4": ".m4a",
    "audio/mpeg": ".mp3", "audio/wav": ".wav", "audio/x-wav": ".wav",
    "audio/webm": ".webm", "audio/ogg": ".ogg", "application/ogg": ".ogg",
}

CONVERT_TIMEOUT_SECONDS = 300
MAX_DURATION_SECONDS = 5400
QUALITIES = {128, 192, 320}
DEFAULT_QUALITY = 192
# Optional official EJS script provider (Deno is required). This improves JS
# signature support, but cannot override a YouTube login/bot challenge.
ENABLE_REMOTE_EJS = os.getenv("LINK_MP3_REMOTE_EJS", "0") == "1"
SLOTS = threading.BoundedSemaphore(2)

# Strict caps for public, no-login mobile conversions. Limits are per running instance;
# for a multi-replica deployment, put a centralized limiter at the reverse proxy.
RATE_WINDOW_SECONDS = 3600
MAX_PUBLIC_REQUESTS_PER_HOUR = 12
MAX_CLIENT_REQUESTS_PER_HOUR = 3
RATE_LOCK = threading.Lock()
PUBLIC_REQUESTS = deque()
CLIENT_REQUESTS = {}


def allow_public_request(ip: str, now: float | None = None) -> bool:
    if now is None:
        now = time.monotonic()
    with RATE_LOCK:
        while PUBLIC_REQUESTS and now - PUBLIC_REQUESTS[0] >= RATE_WINDOW_SECONDS:
            PUBLIC_REQUESTS.popleft()
        # Keep memory bounded by retaining at most one hour of IP tracking.
        for key, timestamps in list(CLIENT_REQUESTS.items()):
            while timestamps and now - timestamps[0] >= RATE_WINDOW_SECONDS:
                timestamps.popleft()
            if not timestamps:
                del CLIENT_REQUESTS[key]
        client = CLIENT_REQUESTS.setdefault(ip, deque())
        if len(PUBLIC_REQUESTS) >= MAX_PUBLIC_REQUESTS_PER_HOUR or len(client) >= MAX_CLIENT_REQUESTS_PER_HOUR:
            return False
        client.append(now)
        PUBLIC_REQUESTS.append(now)
        return True



def canonical_youtube_url(raw: str) -> tuple[str, str]:
    if not isinstance(raw, str) or len(raw) > 1024:
        raise ValueError("Geçerli bir YouTube video bağlantısı girin.")
    try:
        parsed = urlsplit(raw.strip())
        host = (parsed.hostname or "").lower()
        invalid = (
            parsed.scheme.lower() != "https"
            or parsed.username is not None
            or parsed.password is not None
            or parsed.port is not None
            or bool(parsed.fragment)
        )
    except ValueError as exc:
        raise ValueError("Geçersiz YouTube bağlantısı.") from exc
    if invalid:
        raise ValueError("Yalnızca HTTPS YouTube bağlantıları kabul edilir.")
    parts = parsed.path.strip("/").split("/")
    video_id = ""
    if host in {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"}:
        if parsed.path == "/watch":
            candidates = parse_qs(parsed.query).get("v", [])
            video_id = candidates[0] if len(candidates) == 1 else ""
        elif len(parts) == 2 and parts[0] in {"shorts", "live", "embed"}:
            video_id = parts[1]
    elif host in {"youtu.be", "www.youtu.be"} and len(parts) == 1:
        video_id = parts[0]
    if not VIDEO_ID.fullmatch(video_id):
        raise ValueError("Geçerli bir YouTube video bağlantısı girin.")
    return f"https://www.youtube.com/watch?v={video_id}", video_id


def js_runtime_args() -> list[str]:
    """Use the exact same JS challenge support in downloads and diagnostics."""
    if not shutil.which("deno"):
        return []
    result = ["--js-runtimes", "deno"]
    if ENABLE_REMOTE_EJS:
        result += ["--remote-components", "ejs:npm"]
    return result


def convert(url: str, video_id: str, folder: Path, quality: int = DEFAULT_QUALITY) -> Path:
    if type(quality) is not int or quality not in QUALITIES:
        raise ValueError("Desteklenmeyen MP3 kalitesi.")
    runtime_options = js_runtime_args()
    subprocess.run(
        [
            "yt-dlp", "--ignore-config", *runtime_options,
            "--no-playlist", "--no-progress", "--no-warnings",
            "--no-mtime", "--no-cache-dir", "--restrict-filenames",
            "--max-filesize", "80M", "--socket-timeout", "20", "--retries", "2",
            "--match-filter", f"duration <= {MAX_DURATION_SECONDS}",
            "--extract-audio", "--audio-format", "mp3", "--audio-quality", f"{quality}K",
            "-o", str(folder / "%(id)s.%(ext)s"), "--", url,
        ],
        cwd=str(folder), check=True, timeout=CONVERT_TIMEOUT_SECONDS,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    result = folder / f"{video_id}.mp3"
    if not result.is_file() or result.stat().st_size == 0:
        raise RuntimeError("MP3 oluşturulamadı.")
    if result.stat().st_size > MAX_MP3_BYTES:
        raise ValueError("MP3 100 MB sınırını aşıyor.")
    return result



def encode_local_media(input_path: Path, output_path: Path):
    """Convert an explicitly uploaded local video/audio file to MP3; no YouTube required."""
    subprocess.run(
        ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
         "-protocol_whitelist", "file", "-i", str(input_path),
         "-map", "0:a:0", "-vn", "-t", str(MAX_DURATION_SECONDS),
         "-codec:a", "libmp3lame", "-b:a", "192k", "-ac", "2",
         "-ar", "44100", str(output_path)],
        check=True, timeout=180, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True,
    )
    if not output_path.is_file() or not 0 < output_path.stat().st_size <= MAX_MP3_BYTES:
        raise RuntimeError("MP3 çıktısı oluşturulamadı.")


class Handler(BaseHTTPRequestHandler):
    api_key = ""
    public_mode = False
    server_version = "LinkMp3/1.1"

    def respond_json(self, status: int, message: str, error_code: str = "", retry_after: int = 0):
        data = {"error": message}
        if error_code:
            data["error_code"] = error_code
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        if retry_after > 0:
            self.send_header("Retry-After", str(retry_after))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path != "/health":
            return self.respond_json(404, "Sayfa bulunamadı.")
        body = b'{"status":"ok"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


    def post_file(self, authenticated: bool):
        """Process a bounded media upload. Never fetch arbitrary URLs."""
        media_type = self.headers.get("Content-Type", "").split(";")[0].strip().lower()
        extension = UPLOAD_MIME.get(media_type)
        if not extension:
            return self.respond_json(415, "Yalnızca video veya ses dosyası seç.", "UNSUPPORTED_MEDIA")
        if self.headers.get("Transfer-Encoding"):
            return self.respond_json(400, "Sabit dosya boyutu gerekli.")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.respond_json(400, "Geçersiz dosya boyutu.")
        if not 1 <= length <= MAX_UPLOAD_BYTES:
            return self.respond_json(413, "Dosya 60 MB sınırını aşıyor.", "FILE_TOO_LARGE")
        if not SLOTS.acquire(blocking=False):
            return self.respond_json(429, "Sunucu meşgul. Biraz sonra tekrar dene.")
        try:
            if not authenticated and not allow_public_request(self.client_address[0]):
                return self.respond_json(429, "Saatlik dönüştürme sınırına ulaşıldı.")
            with tempfile.TemporaryDirectory(prefix="link-mp3-upload-") as directory:
                folder = Path(directory)
                input_file = folder / ("media" + extension)
                remaining = length
                self.connection.settimeout(45)
                try:
                    with input_file.open("wb") as dest:
                        while remaining > 0:
                            chunk = self.rfile.read(min(64 * 1024, remaining))
                            if not chunk:
                                return self.respond_json(400, "Dosya eksik gönderildi.")
                            dest.write(chunk)
                            remaining -= len(chunk)
                except (OSError, TimeoutError):
                    return self.respond_json(400, "Dosya yüklemesi tamamlanamadı.")
                output = folder / "converted.mp3"
                try:
                    encode_local_media(input_file, output)
                except subprocess.TimeoutExpired:
                    return self.respond_json(504, "Dosya dönüştürme zaman aşımına uğradı.")
                except (subprocess.CalledProcessError, RuntimeError):
                    return self.respond_json(422, "Bu dosyada dönüştürülebilir ses bulunamadı.")
                except FileNotFoundError:
                    return self.respond_json(503, "FFmpeg sunucuda bulunamadı.")
                self.send_mp3(output, "video-yerelmedya0.mp3")
        finally:
            SLOTS.release()

    def send_mp3(self, mp3: Path, file_name: str):
        size = mp3.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", f'attachment; filename="{file_name}"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            with mp3.open("rb") as source:
                while chunk := source.read(64 * 1024):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        if self.path not in {"/convert", "/convert-file"}:
            return self.respond_json(404, "Sayfa bulunamadı.")
        authenticated = bool(self.api_key) and hmac.compare_digest(
            self.headers.get("Authorization", ""), "Bearer " + self.api_key
        )
        if not authenticated and not self.public_mode:
            return self.respond_json(401, "Yetkilendirme başarısız.")
        if self.path == "/convert-file":
            return self.post_file(authenticated)
        if self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json":
            return self.respond_json(415, "JSON gerekli.")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.respond_json(400, "Geçersiz istek.")
        if not 1 <= length <= MAX_REQUEST_BYTES:
            return self.respond_json(413, "Bağlantı çok uzun.")
        self.connection.settimeout(30)
        try:
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(request, dict):
                raise ValueError("JSON nesnesi gerekli.")
            url, video_id = canonical_youtube_url(request.get("url"))
            quality = request.get("quality", DEFAULT_QUALITY)
            if type(quality) is not int or quality not in QUALITIES:
                return self.respond_json(400, "Kalite 128, 192 veya 320 kbps olmalı.")
        except (ValueError, UnicodeError) as exc:
            return self.respond_json(400, str(exc))
        if not SLOTS.acquire(blocking=False):
            return self.respond_json(429, "Sunucu meşgul. Tekrar deneyin.")
        try:
            if not authenticated and not allow_public_request(self.client_address[0]):
                return self.respond_json(429, "Saatlik dönüştürme sınırı doldu. Daha sonra tekrar deneyin.")
            with tempfile.TemporaryDirectory(prefix="link-mp3-") as directory:
                try:
                    mp3 = convert(url, video_id, Path(directory), quality=quality)
                except subprocess.TimeoutExpired:
                    return self.respond_json(504, "Dönüştürme zaman aşımına uğradı.")
                except subprocess.CalledProcessError as exc:
                    output = str(exc.output or "").lower()
                    if "sign in to confirm" in output or "not a bot" in output or "cookies" in output:
                        print("Conversion failed: YouTube requires login/bot verification", flush=True)
                        return self.respond_json(
                            503,
                            "YouTube şu anda sunucudan indirmeye izin vermiyor. Bir süre sonra yeniden dene. Video için telefondaki MP3 dosyanı da seçebilirsin.",
                            error_code="YOUTUBE_VERIFICATION_REQUIRED",
                            retry_after=600,
                        )
                    if "ffmpeg" in output and ("not found" in output or "not installed" in output):
                        print("Conversion failed: ffmpeg missing", flush=True)
                        return self.respond_json(503, "Sunucuda FFmpeg kurulu değil.")
                    if "does not pass filter" in output or "match-filter" in output:
                        return self.respond_json(422, "Video 90 dakikalık sınırı aşıyor.")
                    if "unavailable" in output or "private video" in output:
                        print("Conversion failed: video unavailable", flush=True)
                        return self.respond_json(422, "Video indirilemedi: özel, kaldırılmış veya erişime kapalı olabilir.")
                    if "unable to extract" in output or "signature" in output or "javascript" in output:
                        print("Conversion failed: yt-dlp extractor/JS challenge issue", flush=True)
                        return self.respond_json(
                            503,
                            "YouTube biçimi çözümlenemedi. Sunucu güncellemesi gerekebilir.",
                            error_code="EXTRACTOR_UPDATE_NEEDED",
                            retry_after=300,
                        )
                    print(f"Conversion failed: yt-dlp exit code {exc.returncode}", flush=True)
                    return self.respond_json(502, "Video alınamadı veya MP3 oluşturulamadı.")
                except FileNotFoundError as exc:
                    tool = str(exc.filename or "unknown")
                    print(f"Conversion failed: tool not installed: {tool}", flush=True)
                    return self.respond_json(503, "Sunucunun MP3 dönüştürme aracı kurulu değil.")
                except (RuntimeError, OSError) as exc:
                    print(f"Conversion failed: {type(exc).__name__}", flush=True)
                    return self.respond_json(502, "Video alınamadı veya MP3 oluşturulamadı.")
                except ValueError as exc:
                    return self.respond_json(413, str(exc))
                self.send_mp3(mp3, f"youtube-{video_id}.mp3")
        finally:
            SLOTS.release()


if __name__ == "__main__":
    key = os.getenv("LINK_MP3_API_KEY", "")
    public_mode = os.getenv("LINK_MP3_PUBLIC_MODE", "") == "1"
    if not public_mode and len(key) < 16:
        raise SystemExit("Gizli API anahtarı gerekli veya LINK_MP3_PUBLIC_MODE=1 olmalı.")
    if key and len(key) < 16:
        raise SystemExit("API anahtarı en az 16 karakter olmalı.")
    Handler.api_key = key
    Handler.public_mode = public_mode
    print(f"Tools present: yt-dlp={bool(shutil.which('yt-dlp'))}, ffmpeg={bool(shutil.which('ffmpeg'))}, deno={bool(shutil.which('deno'))}, remote_ejs={ENABLE_REMOTE_EJS}", flush=True)
    diagnose_id = os.getenv("LINK_MP3_DIAG_VIDEO_ID", "")
    if VIDEO_ID.fullmatch(diagnose_id):
        print("Starting single YouTube metadata diagnostic (no download)", flush=True)
        try:
            diag = subprocess.run([
                "yt-dlp", "--ignore-config", *js_runtime_args(),
                "--skip-download", "--no-playlist", "--no-progress",
                "--no-warnings", "--print", "id", "--socket-timeout", "12",
                "--retries", "0", "--",
                f"https://www.youtube.com/watch?v={diagnose_id}",
            ], capture_output=True, text=True, timeout=35)
            stderr = (diag.stderr or "").lower()
            reason = "unknown"
            if diag.returncode == 0:
                reason = "metadata available"
            elif "sign in to confirm" in stderr or "not a bot" in stderr:
                reason = "YouTube login/bot block"
            elif "js runtime" in stderr or "javascript" in stderr or "signature" in stderr:
                reason = "YouTube signature / JavaScript runtime issue"
            elif "unavailable" in stderr:
                reason = "video unavailable"
            elif "timed out" in stderr or "timeout" in stderr:
                reason = "network timeout"
            elif "unsupported" in stderr:
                reason = "extractor unsupported"
            print(f"YouTube metadata diagnostic: result={reason}, exit={diag.returncode}, stderr_tail={stderr[-220:]!r}", flush=True)
        except (subprocess.TimeoutExpired, OSError) as exc:
            print(f"YouTube metadata diagnostic could not run: {type(exc).__name__}", flush=True)
    port = int(os.getenv("PORT", "8080"))
    print(f"MP3 sunucusu 0.0.0.0:{port} dinliyor. HTTPS proxy kullanın.", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
