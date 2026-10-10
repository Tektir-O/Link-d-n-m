# Modern YouTube extraction needs a JavaScript runtime and yt-dlp EJS.
# Deno: official versioned runtime, installed during build (not on every boot).
FROM denoland/deno:bin-2.9.7 AS deno

FROM python:3.12-slim-bookworm
COPY --from=deno /deno /usr/local/bin/deno
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --disable-pip-version-check --no-cache-dir "yt-dlp[default]>=2025.11.12,<2027"
WORKDIR /srv
COPY app.py /srv/app.py
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    DENO_DIR=/tmp/linkmp3-deno \
    HOME=/tmp \
    PORT=8080
USER 65534:65534
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=4s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=2)"
CMD ["python", "-u", "app.py"]
