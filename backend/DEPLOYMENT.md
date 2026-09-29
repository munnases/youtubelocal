# Debian Docker deployment

The backend is deployed at `http://192.168.10.151:8000` from `/home/debian/familytube`.
Set this address in **FamilyTube > Settings > Video server > Save and connect** on each device.
The existing APK can use this server without a rebuild.

## Storage

- `/srv/media/kids` on the host is mounted read-only at `/media`. Put new videos and their matching thumbnail/metadata files here, including in subfolders.
- Docker volume `familytube_backend-data` holds `/data/familytube.sqlite3`, including each device's watch history, suggestions, and scan settings.
- Scanning defaults to every 15 minutes, also runs at startup, and can be changed or triggered in the app's **Library scanning** settings.
- The container runs as UID/GID `10001:10001`. Media files must be readable and their directories traversable by this user; media ownership does not need to change.

## Start or update

Copy `compose.yaml`, `.dockerignore`, and the Dockerfile/Python files/catalog from `backend/` to the deployment directory, keeping that folder structure. The media directory must already exist.

```bash
cd /home/debian/familytube
docker compose config --quiet
docker compose build --pull
docker compose up -d
docker compose ps
curl --fail http://127.0.0.1:8000/api/health
curl --fail http://127.0.0.1:8000/api/scan
```

The restart policy starts the service again after a crash or Docker/host restart, unless it was explicitly stopped. Docker must be enabled at boot. Normal image rebuilds and container replacement retain the data volume. Do not use `docker compose down -v` unless intentionally deleting all watch history and settings.

```bash
docker compose logs --tail=100 backend
docker compose restart backend
```

Port 8000 is for devices on the home network. The device ID provides personalization, not authentication. `FAMILYTUBE_BIND_IP` optionally restricts the published port to a particular host IP.

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
