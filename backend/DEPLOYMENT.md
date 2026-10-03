# Debian Docker deployment

The recorded home deployment uses `http://192.168.10.151:8000` from `/home/debian/familytube`. This guide does not verify or update that host.
Set this address in **FamilyTube > Settings > Video server > Save and connect** on each device.
The existing APK can use this server without a rebuild.

## Storage

- The recorded deployment's `/srv/media/kids` on the host is mounted read-only at `/media`. Retain that `FAMILYTUBE_MEDIA_DIR` setting when upgrading. New Compose installations default to `./backend/media`. MeTube writes to the same host folder via `/downloads`; Samba exposes it read-only as `media`. Put supplied videos and matching thumbnail/metadata files here, including in subfolders.
- Docker volume `familytube_backend-data` holds `/data/familytube.sqlite3`, including each device's watch history, suggestions, and scan settings.
- Generated thumbnails are stored in `/data/thumbnails` in that same volume. The image includes FFmpeg; a single background worker fills missing artwork after startup and successful scans. Supplied thumbnails remain preferred and `/media` stays read-only. See [worker behavior and API](README.md#missing-thumbnail-worker).
- Periodic scanning defaults to every 15 minutes, also runs at startup, and can be changed or triggered through the existing scan API/settings. Compose additionally polls for settled folder changes every five seconds, normally adding completed downloads within 5–10 seconds and triggering missing thumbnails. This watcher preserves the saved schedule, including zero/off. See [automatic discovery](README.md#metube-downloads-and-automatic-discovery).
- The backend runs as UID/GID `10001:10001`. Media files must be readable and their directories traversable by this user. Set `FAMILYTUBE_MEDIA_UID/GID` to an existing identity with write access for MeTube; do not change existing media ownership. MeTube leaves directory ownership intact and creates files with umask `022`.

## Start or update

Use the root [compose.yaml](../compose.yaml), [.dockerignore](../.dockerignore), and [.env.example](../.env.example), with `backend/` containing its Dockerfile, `server.py`, `recommendations.py`, `catalog_scan.py`, `thumbnail_worker.py`, and `catalog.json`. You can copy these files to `/home/debian/familytube` while keeping this layout; the Android source/build output is unnecessary on the backend host. Install Docker Engine and the Compose plugin, and ensure the media directory already exists.

For a new deployment, copy `.env.example` to `.env`, set `FAMILYTUBE_MEDIA_DIR` to the host library, choose the bind IP/ports, and set a private `FAMILYTUBE_SAMBA_PASSWORD`. For an existing deployment, preserve its `.env`, media path and data volume; add the required Samba password and writer UID/GID. The default `FAMILYTUBE_DATA_VOLUME=familytube_backend-data` matches the recorded volume. Changing that name selects a different data store. See [Compose configuration](README.md#docker-compose) for all settings. Ensure TCP 445 is free for Samba and the chosen library is writable by MeTube before starting the three services.

```bash
cd /home/debian/familytube
docker compose config --quiet
docker compose pull metube samba
docker compose build --pull
docker compose up -d
docker compose ps
curl --fail http://127.0.0.1:8000/api/health
curl --fail http://127.0.0.1:8000/api/scan
curl --fail http://127.0.0.1:8000/api/thumbnails
```

The restart policy starts the service again after a crash or Docker/host restart, unless it was explicitly stopped. Docker must be enabled at boot. Normal image rebuilds and container replacement retain the data volume. Do not use `docker compose down -v` unless intentionally deleting all watch history and settings.

Include `backend/thumbnail_worker.py` when copying the Python files, and rebuild the image to install FFmpeg. This is a compatible update with no database-schema migration or client rebuild. Worker counts reset at restart; generated files persist. Refresh the clients' libraries after the worker finishes. These are deployment instructions; the thumbnail increment has not been deployed to the home server.

```bash
docker compose logs --tail=100 backend
docker compose restart backend
```

Ports 8000 (API), 8081 (MeTube web UI) and TCP 445 (SMB) are for the home network; all honor `FAMILYTUBE_BIND_IP`. Open `http://192.168.10.151:8081` for parent-managed downloads after deployment. From another PC, connect to `\\192.168.10.151\media` with username `familytube` and the password chosen in `.env`. The share permits browsing/copying out, not modification; discovery services are disabled, so use the address directly. MeTube's web UI has no login in this configuration. The backend device ID provides personalization, not authentication. The new stack has not been deployed to the recorded home server.

## Database backup

Use SQLite's backup API so the backup is consistent while the backend is running:

```bash
cd /home/debian/familytube
mkdir -p backups
chmod 700 backups
docker compose exec -T backend python -c "import sqlite3; s=sqlite3.connect('/data/familytube.sqlite3'); d=sqlite3.connect('/data/familytube-backup.sqlite3'); s.backup(d); d.close(); s.close()"
docker compose cp backend:/data/familytube-backup.sqlite3 "backups/familytube-$(date +%Y%m%d-%H%M%S).sqlite3"
```

Copy backups to another disk or machine as well. Back up `/srv/media/kids` separately. For restoration, stop the backend first, retain a backup of the current database, and restore into the data volume with ownership `10001:10001` before starting it again. Never overwrite a live SQLite database or restore it alongside stale WAL/SHM files.

The first deployment imported a consistent backup of the Windows backend's existing database; rebuilding the image does not repeat that import.
