"""Self-hosted YouTube video link to MP3 service for permitted media."""
import hmac
import json
import os
import re
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

VIDEO_ID = re.compile(r"^[a-zA-Z0-9_-]{11}$")
MAX_MP3_BYTES = 100 * 1024 * 1024
MAX_REQUEST_BYTES = 2048
CONVERT_TIMEOUT_SECONDS = 300
SLOTS = threading.BoundedSemaphore(2)


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


def convert(url: str, video_id: str, folder: Path) -> Path:
    subprocess.run(
        [
            "yt-dlp", "--no-playlist", "--no-progress", "--no-warnings",
            "--no-mtime", "--no-cache-dir", "--restrict-filenames",
            "--max-filesize", "80M", "--socket-timeout", "20", "--retries", "2",
            "--extract-audio", "--audio-format", "mp3", "--audio-quality", "0",
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


class Handler(BaseHTTPRequestHandler):
    api_key = ""
    server_version = "LinkMp3/1.0"

    def respond_json(self, status: int, message: str):
        payload = json.dumps({"error": message}, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
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

    def do_POST(self):
        if self.path != "/convert":
            return self.respond_json(404, "Sayfa bulunamadı.")
        if not self.api_key:
            return self.respond_json(503, "Sunucu anahtarı eksik.")
        if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.api_key):
            return self.respond_json(401, "Yetkilendirme başarısız.")
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
        except (ValueError, UnicodeError) as exc:
            return self.respond_json(400, str(exc))
        if not SLOTS.acquire(blocking=False):
            return self.respond_json(429, "Sunucu meşgul. Tekrar deneyin.")
        try:
            with tempfile.TemporaryDirectory(prefix="link-mp3-") as directory:
                try:
                    mp3 = convert(url, video_id, Path(directory))
                except subprocess.TimeoutExpired:
                    return self.respond_json(504, "Dönüştürme zaman aşımına uğradı.")
                except (subprocess.CalledProcessError, RuntimeError, OSError):
                    return self.respond_json(502, "Video alınamadı veya MP3 oluşturulamadı.")
                except ValueError as exc:
                    return self.respond_json(413, str(exc))
                size = mp3.stat().st_size
                self.send_response(200)
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("Content-Length", str(size))
                self.send_header("Content-Disposition", f'attachment; filename="youtube-{video_id}.mp3"')
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                try:
                    with mp3.open("rb") as source:
                        while chunk := source.read(64 * 1024):
                            self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    pass
        finally:
            SLOTS.release()


if __name__ == "__main__":
    key = os.getenv("LINK_MP3_API_KEY", "")
    if len(key) < 16:
        raise SystemExit("LINK_MP3_API_KEY en az 16 karakter olmalı.")
    Handler.api_key = key
    port = int(os.getenv("PORT", "8080"))
    print(f"MP3 sunucusu 0.0.0.0:{port} dinliyor. HTTPS proxy kullanın.", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
