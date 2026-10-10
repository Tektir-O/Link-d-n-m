FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir "yt-dlp>=2025.1.1"
WORKDIR /srv
COPY app.py /srv/app.py
USER nobody
ENV PYTHONUNBUFFERED=1
EXPOSE 8080
CMD ["python", "-u", "app.py"]
