# FamilyTube

FamilyTube streams your own family video library from a home server to native Android phone and Android TV apps. Viewing works over local Wi-Fi without internet. The Python backend includes byte-range streaming, per-device watch history, library scanning and a background worker that creates missing thumbnails.

## Start the backend with Docker Compose

Install Docker Engine with the Compose plugin on Linux, or Docker Desktop using Linux containers on Windows. From this repository's root, use the following for a new setup. Keep an existing `.env` and skip the copy step when updating:

```bash
cp .env.example .env
# Edit .env: set FAMILYTUBE_MEDIA_DIR to your existing video folder.
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8000/api/health
```

In PowerShell, use `Copy-Item .env.example .env` and `curl.exe` in place of `cp` and `curl`. A Windows media path can be `E:/family-videos`. If you change the port, use that port in the health URL. Videos are mounted read-only; a persistent Docker volume stores watch history, scan settings and generated thumbnails. The server runs as UID/GID 10001. The media folder must already exist and be readable by that user.

On each client, open the Server settings and enter `http://<server-LAN-IP>:8000`, using your configured port. The device's own `localhost` points to the device; use the server's LAN address. Refresh the library after thumbnail generation finishes.

See the [backend README](backend/README.md#docker-compose) for configuration, logs, scans and retries, and the [deployment guide](backend/DEPLOYMENT.md) for updates and backups. Start/update commands affect the Docker host where you run them.

## Build production Android APKs

Open `client/` in Android Studio and follow the [production build guide](client/docs/PRODUCTION_BUILD.md). It covers the pinned JDK/SDK requirements, phone and TV release builds, creating a private signing key, signing APKs, signature verification and installation/update behavior.

The current command-line release builds are unsigned until you sign them. APKs, keystores and passwords belong outside tracked source. See the [client README](client/README.md) for debug builds and existing verification evidence. Physical-device/performance acceptance and the remaining S6-S8 work are still pending; creating release APKs does not complete those stages.
