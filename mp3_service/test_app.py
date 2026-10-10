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

from app import Handler, canonical_youtube_url, convert


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
                self.assertEqual(cmd[-1], "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                Path(directory, "dQw4w9WgXcQ.mp3").write_bytes(b"ID3fake")
            with patch("app.subprocess.run", side_effect=fake_run):
                mp3 = convert("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", Path(directory))
            self.assertEqual(mp3.read_bytes(), b"ID3fake")


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

    def test_mp3_response(self):
        def fake_converter(url, video_id, folder):
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
