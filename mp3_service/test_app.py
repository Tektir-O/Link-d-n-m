"""Offline unit and API tests: no requests to YouTube."""
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from app import Handler, canonical_youtube_url, convert, allow_public_request, CLIENT_REQUESTS, PUBLIC_REQUESTS


class UrlTests(unittest.TestCase):
    def test_valid_links(self):
        for link in (
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?t=5",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        ):
            with self.subTest(link=link):
                self.assertEqual(
                    canonical_youtube_url(link),
                    ("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ"),
                )

    def test_invalid_links(self):
        for link in (
            "http://youtu.be/dQw4w9WgXcQ",
            "https://youtube.com.evil.org/watch?v=dQw4w9WgXcQ",
            "https://youtube.com@evil.org/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/playlist?list=PLxyz",
            "https://www.youtube.com/watch?v=invalid",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ#fragment",
            "",
            None,
        ):
            with self.subTest(link=link), self.assertRaises(ValueError):
                canonical_youtube_url(link)

    def test_invokes_mp3_encoder_and_disables_playlists(self):
        with tempfile.TemporaryDirectory() as directory:
            def fake_run(cmd, **kwargs):
                self.assertIn("--no-playlist", cmd)
                self.assertEqual(cmd[cmd.index("--audio-format") + 1], "mp3")
                self.assertEqual(cmd[cmd.index("--audio-quality") + 1], "192K")
                self.assertIn("--match-filter", cmd)
                self.assertEqual(cmd[-1], "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                Path(directory, "dQw4w9WgXcQ.mp3").write_bytes(b"ID3fake")
            with patch("app.subprocess.run", side_effect=fake_run):
                mp3 = convert("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", Path(directory))
            self.assertEqual(mp3.read_bytes(), b"ID3fake")

    def test_runtime_flag_for_deno_and_optional_ejs(self):
        with tempfile.TemporaryDirectory() as directory:
            def fake_run(cmd, **kwargs):
                self.assertIn("--js-runtimes", cmd)
                self.assertEqual(cmd[cmd.index("--js-runtimes") + 1], "deno")
                self.assertIn("--remote-components", cmd)
                self.assertEqual(cmd[cmd.index("--remote-components") + 1], "ejs:npm")
                self.assertIn("--ignore-config", cmd)
                Path(directory, "dQw4w9WgXcQ.mp3").write_bytes(b"ID3mock")
            with patch("app.shutil.which", side_effect=lambda tool: "/usr/bin/deno" if tool == "deno" else None), \
                 patch("app.ENABLE_REMOTE_EJS", True), \
                 patch("app.subprocess.run", side_effect=fake_run):
                result = convert("https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                 "dQw4w9WgXcQ", Path(directory))
            self.assertEqual(result.read_bytes(), b"ID3mock")


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        Handler.api_key = "test-only-very-long-api-key"
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def post(self, path, key="test-only-very-long-api-key", payload=None):
        body = json.dumps(payload or {"url": "https://youtu.be/dQw4w9WgXcQ"}).encode()
        request = Request(
            self.base + path, method="POST", data=body,
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
        )
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, response.read(), response.headers
        except HTTPError as exc:
            return exc.code, exc.read(), exc.headers

    def test_health(self):
        with urlopen(self.base + "/health", timeout=5) as response:
            self.assertEqual(json.loads(response.read())["status"], "ok")

    def test_authorization(self):
        code, body, _ = self.post("/convert", key="wrong")
        self.assertEqual(code, 401)
        self.assertIn("error", json.loads(body))

    def test_rejected_non_youtube_url(self):
        code, _, _ = self.post("/convert", payload={"url": "https://example.com/"})
        self.assertEqual(code, 400)

    def test_public_mode_without_embedded_api_key(self):
        before_key, before_mode = Handler.api_key, Handler.public_mode
        try:
            Handler.api_key = ""
            Handler.public_mode = True

            def fake_converter(url, video_id, folder, quality=192):
                output = folder / (video_id + ".mp3")
                output.write_bytes(b"ID3-test-public-audio")
                return output

            with patch("app.convert", side_effect=fake_converter):
                code, body, _ = self.post("/convert", key="not-a-secret")
            self.assertEqual(code, 200)
            self.assertEqual(body, b"ID3-test-public-audio")
        finally:
            Handler.api_key, Handler.public_mode = before_key, before_mode

    def test_public_rate_limiting(self):
        with patch("app.MAX_PUBLIC_REQUESTS_PER_HOUR", 12), patch("app.MAX_CLIENT_REQUESTS_PER_HOUR", 3):
            PUBLIC_REQUESTS.clear()
            CLIENT_REQUESTS.clear()
            self.assertTrue(allow_public_request("192.0.2.10", now=1000))
            self.assertTrue(allow_public_request("192.0.2.10", now=1000))
            self.assertTrue(allow_public_request("192.0.2.10", now=1000))
            self.assertFalse(allow_public_request("192.0.2.10", now=1000))
            self.assertTrue(allow_public_request("192.0.2.11", now=1000))
            self.assertTrue(allow_public_request("192.0.2.10", now=5000))
            PUBLIC_REQUESTS.clear()
            CLIENT_REQUESTS.clear()


    def test_quality_options(self):
        from app import QUALITIES
        self.assertEqual(QUALITIES, {128, 192, 320})
        for invalid in (0, 256, 999, "320", None, True):
            status, _, _ = self.post("/convert", payload={
                "url": "https://youtu.be/dQw4w9WgXcQ", "quality": invalid,
            })
            self.assertEqual(status, 400, str(invalid))

    def test_quality_forwarded(self):
        def create_audio(url, video_id, folder, quality=192):
            self.assertEqual(quality, 320)
            output = folder / (video_id + ".mp3")
            output.write_bytes(b"ID3-quality")
            return output
        with patch("app.convert", side_effect=create_audio):
            status, body, _ = self.post("/convert", payload={
                "url": "https://youtu.be/dQw4w9WgXcQ", "quality": 320,
            })
        self.assertEqual(status, 200)
        self.assertEqual(body, b"ID3-quality")

    def test_youtube_verification_is_temporary_service_error(self):
        import subprocess
        fail = subprocess.CalledProcessError(
            1, ["yt-dlp"], output="ERROR: Sign in to confirm you're not a bot. Use cookies."
        )
        with patch("app.convert", side_effect=fail):
            code, body, headers = self.post("/convert")
        self.assertEqual(code, 503)
        self.assertEqual(headers.get("Retry-After"), "600")
        payload = json.loads(body)
        self.assertEqual(payload["error_code"], "YOUTUBE_VERIFICATION_REQUIRED")
        self.assertIn("telefondaki MP3", payload["error"])

    def test_signature_failure_is_classified(self):
        import subprocess
        fail = subprocess.CalledProcessError(
            1, ["yt-dlp"], output="ERROR: Unable to extract signature"
        )
        with patch("app.convert", side_effect=fail):
            code, body, headers = self.post("/convert")
        self.assertEqual(code, 503)
        self.assertEqual(headers.get("Retry-After"), "300")
        self.assertEqual(json.loads(body)["error_code"], "EXTRACTOR_UPDATE_NEEDED")

    def test_other_yt_dlp_error_is_not_mislabeled_youtube_login(self):
        import subprocess
        fail = subprocess.CalledProcessError(1, ["yt-dlp"], output="ERROR: network connection closed")
        with patch("app.convert", side_effect=fail):
            code, body, headers = self.post("/convert")
        self.assertEqual(code, 502)
        self.assertNotIn("error_code", json.loads(body))
        self.assertIsNone(headers.get("Retry-After"))


    def post_media(self, data=b"sample-media-bytes", media_type="video/mp4"):
        request = Request(
            self.base + "/convert-file", method="POST", data=data,
            headers={"Authorization": "Bearer test-only-very-long-api-key", "Content-Type": media_type},
        )
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, response.read(), response.headers
        except HTTPError as exc:
            return exc.code, exc.read(), exc.headers

    def test_uploaded_video_converts_without_youtube(self):
        def fake_encoder(source, target):
            self.assertEqual(source.suffix, ".mp4")
            self.assertEqual(source.read_bytes(), b"video-bytes")
            target.write_bytes(b"ID3-local-mp3")
        with patch("app.encode_local_media", side_effect=fake_encoder) as encoder, \
             patch("app.convert", side_effect=AssertionError("YouTube must not be called")):
            status, data, headers = self.post_media(data=b"video-bytes")
        self.assertEqual(status, 200)
        self.assertEqual(data, b"ID3-local-mp3")
        self.assertEqual(headers.get_content_type(), "audio/mpeg")
        self.assertEqual(encoder.call_count, 1)

    def test_uploaded_audio_converts(self):
        def fake_encoder(source, target):
            self.assertEqual(source.suffix, ".wav")
            target.write_bytes(b"ID3-wav-conversion")
        with patch("app.encode_local_media", side_effect=fake_encoder):
            status, data, _ = self.post_media(data=b"RIFFstuff", media_type="audio/wav")
        self.assertEqual(status, 200)
        self.assertEqual(data, b"ID3-wav-conversion")

    def test_upload_validation_and_non_audio(self):
        status, _, _ = self.post_media(data=b"", media_type="video/mp4")
        self.assertEqual(status, 413)
        status, _, _ = self.post_media(media_type="text/html")
        self.assertEqual(status, 415)
        status, _, _ = self.post_media(data=b"0" * (60 * 1024 * 1024 + 1))
        self.assertEqual(status, 413)
        import subprocess
        failed = subprocess.CalledProcessError(1, ["ffmpeg"])
        with patch("app.encode_local_media", side_effect=failed):
            status, data, _ = self.post_media()
        self.assertEqual(status, 422)
        self.assertIn("ses", json.loads(data)["error"])

    def test_ffmpeg_uses_only_local_media(self):
        from app import encode_local_media
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "input.mp4"
            target = Path(folder) / "output.mp3"
            source.write_bytes(b"fake-media")
            def fake_run(args, **kwargs):
                self.assertIn("-protocol_whitelist", args)
                self.assertEqual(args[args.index("-protocol_whitelist") + 1], "file")
                self.assertIn("-map", args)
                self.assertEqual(args[args.index("-map") + 1], "0:a:0")
                self.assertNotIn("yt-dlp", args)
                target.write_bytes(b"ID3-audio")
            with patch("app.subprocess.run", side_effect=fake_run):
                encode_local_media(source, target)
            self.assertEqual(target.read_bytes(), b"ID3-audio")

    def test_mp3_response(self):
        def fake_converter(url, video_id, folder, quality=192):
            output = folder / (video_id + ".mp3")
            output.write_bytes(b"ID3-test-audio")
            return output
        with patch("app.convert", side_effect=fake_converter):
            code, body, headers = self.post("/convert")
        self.assertEqual(code, 200)
        self.assertEqual(headers.get_content_type(), "audio/mpeg")
        self.assertEqual(body, b"ID3-test-audio")
        self.assertIn("youtube-dQw4w9WgXcQ.mp3", headers.get("Content-Disposition"))


if __name__ == "__main__":
    unittest.main()
