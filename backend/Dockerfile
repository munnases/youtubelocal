FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY backend/server.py backend/recommendations.py backend/catalog_scan.py backend/catalog.json ./backend/

RUN mkdir -p /data /media && chown 10001:10001 /data
USER 10001:10001

EXPOSE 8000
CMD ["python", "-u", "backend/server.py", "--host", "0.0.0.0", "--port", "8000", "--media", "/media", "--catalog", "/app/backend/catalog.json", "--db", "/data/familytube.sqlite3"]
