# FamilyTube

FamilyTube streams your own family video library from a home server to native Android phone and Android TV apps. Viewing works over local Wi-Fi without internet. The Python backend includes byte-range streaming, per-device watch history, library scanning and a background worker that creates missing thumbnails. Compose also provides MeTube for parent-managed downloads and a password-protected Samba share for browsing the media from another PC.

## Start the backend with Docker Compose

Install Docker Engine with the Compose plugin on Linux, or Docker Desktop using Linux containers on Windows. From this repository's root, use the following for a new setup. Keep an existing `.env` and skip the copy step when updating:

```bash
cp .env.example .env
# Edit .env: set a private FAMILYTUBE_SAMBA_PASSWORD and choose the media folder.
mkdir -p backend/media
# On Linux, for a NEW empty folder using the default download UID/GID:
sudo chown 10001:10001 backend/media
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8000/api/health
```

In PowerShell, use `Copy-Item .env.example .env`, `New-Item -ItemType Directory -Force backend/media`, and `curl.exe`. Linux ownership commands do not apply on Windows. All three services share `backend/media` by default; an existing `FAMILYTUBE_MEDIA_DIR` override remains the shared folder, for example `/srv/media/kids` or `E:/family-videos`. For an existing Linux library, set `FAMILYTUBE_MEDIA_UID/GID` to a user/group with write access instead of changing its ownership. The backend runs as UID/GID 10001 and needs read/traverse access. MeTube writes to the folder; the backend and Samba mount it read-only. A persistent Docker volume stores watch history, scan settings and generated thumbnails.

Open MeTube at `http://<server-LAN-IP>:8081` to download videos and select MP4 output. FamilyTube also supports existing WebM, MOV and M4V files. Completed supported videos are added automatically after about 5–10 seconds of settled folder changes, followed by background thumbnail generation. Refresh the Android library to see the new entries. MeTube downloads need internet; viewing already saved videos does not.

In Windows File Explorer, enter `\\<server-LAN-IP>\media`, then sign in as `familytube` using `FAMILYTUBE_SAMBA_PASSWORD`. The share allows browsing and copying files to your PC. Keep TCP port 445 available on the server; an existing SMB server, including Windows file sharing, may already use it. See the [Samba setup notes](backend/README.md#samba-media-share).

On each client, open the Server settings and enter `http://<server-LAN-IP>:8000`, using your configured port. The device's own `localhost` points to the device; use the server's LAN address. Refresh the library after thumbnail generation finishes.

See the [backend README](backend/README.md#docker-compose) for configuration, logs, scans and retries, and the [deployment guide](backend/DEPLOYMENT.md) for updates and backups. Start/update commands affect the Docker host where you run them.

## Build production Android APKs

Open `client/` in Android Studio and follow the [production build guide](client/docs/PRODUCTION_BUILD.md). It covers the pinned JDK/SDK requirements, phone and TV release builds, creating a private signing key, signing APKs, signature verification and installation/update behavior.

The current command-line release builds are unsigned until you sign them. APKs, keystores and passwords belong outside tracked source. See the [client README](client/README.md) for debug builds and existing verification evidence. Physical-device/performance acceptance and the remaining S6-S8 work are still pending; creating release APKs does not complete those stages.
