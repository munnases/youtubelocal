# FamilyTube backend

The Python standard-library HTTP server streams the local family library, caches its catalog, and stores per-device watch history in SQLite. See [deployment instructions](DEPLOYMENT.md) for the Debian Docker installation.

## Docker Compose

The repository-root [compose.yaml](../compose.yaml) builds this backend with FFmpeg and starts MeTube and Samba alongside it. Docker Engine with the Compose plugin, or Docker Desktop with Linux containers, must be installed. Run commands from the repository root. For an existing installation, retain `.env` and skip the initial copy step; add the new Samba password setting before updating:

```bash
cp .env.example .env
# Edit .env: choose the shared media path and set FAMILYTUBE_SAMBA_PASSWORD.
mkdir -p backend/media
# Only for a NEW empty Linux folder, using the default MeTube UID/GID:
sudo chown 10001:10001 backend/media
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8000/api/health
curl --fail http://127.0.0.1:8000/api/thumbnails
```

PowerShell uses `Copy-Item .env.example .env`, `New-Item -ItemType Directory -Force backend/media`, and `curl.exe`; omit Linux ownership commands. Set absolute Windows media paths with forward slashes, for example `FAMILYTUBE_MEDIA_DIR=E:/family-videos`; Docker Desktop must have access to that drive. `.env` is ignored by Git. Environment variables in your shell override values from `.env`.

| Setting | Default | Purpose |
| --- | --- | --- |
| `FAMILYTUBE_MEDIA_DIR` | `./backend/media` | Shared host folder: backend `/media` and Samba `/shares/media` read-only, MeTube `/downloads` writable; a missing folder fails startup |
| `FAMILYTUBE_BIND_IP` | `0.0.0.0` | Host interface for all published ports; use the server's LAN IP to restrict them, or `127.0.0.1` for local testing |
| `FAMILYTUBE_PORT` | `8000` | Host port; container port remains 8000 |
| `FAMILYTUBE_DATA_VOLUME` | `familytube_backend-data` | Persistent volume for SQLite history/settings/catalog and generated thumbnails; keep the existing name when upgrading |
| `FAMILYTUBE_METUBE_PORT` | `8081` | MeTube web interface host port |
| `FAMILYTUBE_MEDIA_UID/GID` | `10001` / `10001` | MeTube writer identity on Linux; UID is also used for the Samba reader |
| `FAMILYTUBE_MEDIA_WATCH_SECONDS` | `5` | Polling interval for automatic change discovery, independent of saved periodic scanning; `0` disables watching |
| `FAMILYTUBE_SAMBA_PASSWORD` | Required | Password for the `familytube` SMB account; no guest access |
| `FAMILYTUBE_METUBE_IMAGE` | `ghcr.io/alexta69/metube:latest` | Optional image tag/digest override |
| `FAMILYTUBE_SAMBA_IMAGE` | `ghcr.io/servercontainers/samba:smbd-only-latest` | Optional image tag/digest override |

The existing Dockerfile runs as UID/GID `10001:10001`. Host media must be readable and directories traversable by that user. Data volume ownership is initialized by the image. The API health check reports server/catalog availability; inspect `/api/thumbnails` separately for artwork failures. The service restarts after crashes/host reboot unless explicitly stopped, logs rotate at 10 MB with three files, and Compose sends SIGINT with 15 seconds for normal shutdown. The [Compose specification](https://docs.docker.com/reference/compose-file/) describes the configuration format.

Useful commands:

```bash
docker compose logs -f --tail=100 backend
docker compose restart backend
# After source changes, rebuild and recreate the service, retaining its data:
docker compose up -d --build
# Stop containers and remove the project's network, retaining the data volume:
docker compose down
```

Keep the data volume when updating or stopping; `docker compose down -v` deletes history, settings and generated images. [Back up SQLite](DEPLOYMENT.md#database-backup) before a production update. The `.dockerignore` allowlist excludes media, databases, Android build output, environment files and signing material from the image context. Local `catalog.json` changes are included on the next image rebuild; supplied `.info.json` metadata and artwork stay beside videos.

To discover new files or retry missing artwork immediately:

```bash
curl --fail -X POST http://127.0.0.1:8000/api/scan -H 'X-Device-ID: parent-admin-123' -H 'Content-Type: application/json' -d '{}'
curl --fail -X POST http://127.0.0.1:8000/api/thumbnails -H 'X-Device-ID: parent-admin-123' -H 'Content-Type: application/json' -d '{}'
```

Use `curl.exe` in PowerShell and adjust the port if configured. A successful scan already triggers the thumbnail worker; the second command retries the currently cached library independently. The ID supplies the existing mutation header and is not backend authentication. Clients connect to `http://<server-LAN-IP>:<port>` and refresh the library to receive generated thumbnail URLs. See [client production builds](../client/docs/PRODUCTION_BUILD.md) to package phone and TV APKs.

Verified on 2026-10-03: Compose configuration and image build passed. An isolated local project on port 18080 reached healthy status using read-only fixture media and a fresh test data volume. It generated and served two JPEGs, recorded watch progress, and retained the database/history/artwork across restart without regenerating cached images. UID 10001 and graceful SIGINT shutdown were verified; fixture containers/network/data volume were removed. The home deployment was unchanged.

## MeTube downloads and automatic discovery

Open `http://<server-LAN-IP>:8081` and paste a video or playlist link. [MeTube's configuration reference](https://github.com/alexta69/metube#configuration) describes the upstream directory, identity and yt-dlp settings used here. Downloads, including audio-only files, go into the same host folder configured by `FAMILYTUBE_MEDIA_DIR`. Select MP4 output for videos; FamilyTube indexes `.mp4`, `.m4v`, `.mov` and `.webm`, not audio-only files or other containers. Merged downloads default to MP4. Codec compatibility still depends on the playback device.

Files include the source video ID in their name to distinguish videos with the same title. Playlists/channels get subfolders. `.info.json` sidecars supply original titles, descriptions and durations; supplied thumbnails take priority over generated images. Existing IDs and `catalog.json` overrides remain unchanged. The configured names reduce collisions but do not migrate older manually named downloads.

MeTube uses `.metube-tmp/` for in-progress downloads and postprocessing, on the same filesystem as final output so completed files can be renamed into place. `.metube/` stores the persistent queue/history. Both directories are pruned from startup, scheduled and manual discovery, and hidden from Samba. Keep them when restarting/updating. MeTube starts directly as the configured UID/GID, including creation of these directories, and does not change host-folder ownership (`CHOWN_DIRS=false`); on Linux, match its identity to a writer with existing access. Its `022` umask makes new files readable by the backend's UID 10001.

Compose enables a background watcher with a five-second interval. It compares paths, sizes and nanosecond modification/change times for supported videos, metadata, supplied images and catalog overrides. Two matching observations after a change request a scan, normally within 5–10 seconds; successful scans also request missing-thumbnail work. A change during another scan queues a follow-up. Added, removed and replaced files and sidecar updates therefore reach the cached API automatically. Clients still need a catalog refresh; no push notification or APK change is added.

The watcher leaves the persisted periodic schedule intact and still works when that schedule is off. `FAMILYTUBE_MEDIA_WATCH_SECONDS=0` disables it; local runs opt in with `--media-watch-seconds 5`. Inaccessible folders retain the previous catalog and log a retry. Polling reads file metadata, not video bytes; NAS cost for large libraries still needs measurement. For manual transfers, stage under `.metube-tmp/` and rename finished files into the library: a file-size pause is not proof that a direct copy has finished.

Downloads require internet; serving the saved library and Samba access work entirely on the home network. MeTube has no login configured here, so publish its web port only on the trusted LAN. To update the external images, run `docker compose pull metube samba` before `docker compose up -d --build`; image overrides in `.env` can pin tested digests.

## Samba media share

Set `FAMILYTUBE_SAMBA_PASSWORD` in the ignored `.env` before starting Compose; the configuration rejects a missing/empty password. Use a private password, single-quoted in `.env` if it contains `$` or `#`. Do not commit `.env` or post rendered Compose configuration containing the password.

From another Windows PC, enter `\\<server-LAN-IP>\media` in File Explorer and sign in as `familytube`. On macOS use **Connect to Server** with `smb://<server-LAN-IP>/media`. Linux can use a file manager or `smbclient //<server-LAN-IP>/media -U familytube` (prompts for the password). The authenticated share lists and reads the media; both Samba configuration and its Docker mount are read-only. It cannot upload/delete/rename library files. [The Samba image reference](https://github.com/ServerContainers/samba#environment-variables-and-defaults) documents account and share settings.

Direct connection uses TCP 445; network discovery/NetBIOS services are disabled. Allow that port from the home LAN and keep it available on the server. If the host already runs Samba or Windows file sharing on 445, use that existing server to expose the same directory, or run this stack on the Linux home server; Windows Explorer does not support an arbitrary alternate SMB port. Docker Desktop filesystem behavior may differ from Linux, so verify read access on the actual server. All published ports honor `FAMILYTUBE_BIND_IP`.

## Run locally

From the repository root, with Python 3.13 and FFmpeg installed:

```powershell
python backend/server.py --media "E:/family-videos" --db backend/data/familytube.sqlite3
```

Use your actual library path. Normal operation needs no internet. FFmpeg is used only for background thumbnail extraction, never by catalog, artwork, or Play request handlers. Its [command documentation](https://ffmpeg.org/ffmpeg.html) describes the input seek and single-frame options used by the worker.

## Missing-thumbnail worker

The server starts one thumbnail worker automatically. Every successful startup, scheduled, or manual library scan requests a pass over the cached library. Repeated requests coalesce; a single FFmpeg process runs at a time, with one decoder/encoder/filter thread and a 60-second timeout per extraction attempt. Browsing and byte-range streaming remain available while it runs. CPU and disk contention on the actual NAS still need measurement.

Existing matching `.webp`, `.jpg`, `.jpeg`, and `.png` sidecars take priority and are never overwritten. Missing artwork gets a 640x360 JPEG with aspect-preserving scaling and black padding. The worker extracts at one second and falls back to the first frame for clips too short to provide that frame. Each completed JPEG is checked, atomically renamed, and published into the cached catalog/SQLite immediately. Clients see it on their next catalog refresh; no APK rebuild or API migration is required. Missing/failed artwork keeps the current client placeholder.

Generated images live in `thumbnails/` beside the watch database: `/data/thumbnails` in Docker. The video library remains read-only. `--thumbnail-dir` overrides this location; `--ffmpeg` accepts an executable path; `--no-thumbnail-worker` disables generation while retaining supplied and previously generated artwork. When choosing a custom output path, keep it writable and outside the original video folders.

Cache names hash the generator version, resolved library root, relative video path, file size, modification time, and change time. Changed inputs therefore use different artwork URLs; same-stem videos and different folders have separate cache files. The worker discards output if the source changes during extraction. This is a filesystem-stat identity, not a hash of all video bytes. Old generated versions are retained; automatic garbage collection is outside this increment. The existing root-video ID behavior is unchanged: root files sharing a stem need distinct IDs in `catalog.json`.

An unsupported/corrupt video, unwritable output folder, timeout, or missing FFmpeg appears in worker status. Individual video failures do not prevent other videos from being processed. A missing executable stops that pass. Failed items retry on the next successful scan or manual worker request, without a continuous retry loop. With scan interval zero, startup/manual scans still trigger generation, but periodic retries are disabled. Shutdown kills an active decoder and removes its temporary output; process-crash leftovers named `.thumbnail-*` are never served.

| Route | Behavior |
| --- | --- |
| `GET /api/thumbnails` | Worker enabled/running/requested state, current video, process-lifetime generated/failed counts, latest error and last completed pass time (epoch milliseconds) |
| `POST /api/thumbnails` with `{}` | Return 202 and request a pass over the cached catalog; use a library scan first for newly added videos. Return 503 if disabled. |
| `GET` / `HEAD /generated-thumbnails/{hash}.jpg?v=...` | Serve finished generated artwork with the existing image-cache policy |
| `GET` / `HEAD /thumbnails/{path}?v=...` | Existing supplied-artwork route, unchanged |

POST uses the same valid `X-Device-ID` requirement as existing mutations; this is not authentication. No internal artwork paths/flags are added to public catalog entries; only the existing `thumbnailUrl` becomes populated.

## Verification

The MeTube/Samba increment was verified on 2026-10-03. Windows ran 46 tests (45 passed, one optional FFmpeg test skipped); all 46 passed with FFmpeg in the production Linux image as UID 10001. Compose configuration, default shared `backend/media` paths, missing-password rejection, image build, CLI help and `git diff --check` passed. No database/API migration or client rebuild is required; retain the existing `.env` media path and data volume when updating, adding the private SMB password and writer identity.

An isolated Compose project (`familytube-metube-samba-fixture`) used localhost ports 18080/18081/14445, a new fixture database volume, ignored test media and a synthetic private HTTP source. A real MeTube download was catalogued automatically in 5.9 seconds with periodic scanning off; metadata, exact original bytes, generated/served thumbnail and HTTP 206 passed. Authenticated SMB listing/read passed with matching bytes; private folders were hidden and writes, guest access and wrong passwords were rejected. All three services became healthy and restart retained video, watch progress, scan settings and MeTube state. The downloader runs directly as UID/GID 10001 so its state/temp folders are writable without changing library ownership. Fixture containers/network/database volume were removed. Harnesses `check_download.py` and `check_share.py`, the override and media remain in ignored `client/build/metube-samba-check/`; these are local evidence, not tracked deployment files. Public-site downloads, the actual home-server/PC LAN connection and NAS performance remain unverified.

Verified external image digests (optional pins through the image overrides):

- MeTube: `ghcr.io/alexta69/metube@sha256:8e2fe9beeefc55a02f6e1d04cad0fc3c22e8f067d309188f016551dc99ec27b3`
- Samba: `ghcr.io/servercontainers/samba@sha256:31b90ea7fe3258d30fccd971c85743b92694605f4b43dc8a4df23a202fced06a`

The Linux regression command for this increment was:

```powershell
docker run --rm --network none --mount 'type=bind,source=E:/project/youtubelocal/backend,target=/tests/backend,readonly' --workdir /tests --env FAMILYTUBE_TEST_FFMPEG=ffmpeg --entrypoint python familytube-backend:latest -m unittest discover -s backend -p 'test_*.py'
```

Use this checkout's absolute backend path in the bind source. The locally built image supplies FFmpeg; tests create temporary fixtures and never modify the mounted library or databases.

From the repository root:

```powershell
python -m unittest discover -s backend -p "test_*.py"
# Also enable synthetic MP4/MOV/WebM decoding and JPEG validation:
$env:FAMILYTUBE_TEST_FFMPEG = 'C:/tools/ffmpeg/bin/ffmpeg.exe'
python -m unittest discover -s backend -p "test_*.py"
```

Use an existing FFmpeg executable for the second command. Without that variable, the real decoding test is explicitly skipped. All tests use temporary video/database/artwork fixtures and preserve the family library. Tests cover supplied-artwork preservation, restart reuse, replacement/version changes, filename collisions, short clips, failures/retry, partial-output cleanup, overlapping scans/requests, decoder timeout/shutdown, HTTP status/artwork/HEAD, path confinement and streaming while extraction is blocked.

Verified on 2026-10-02: all **38 tests passed** on Windows (Python 3.13.15, locally staged FFmpeg 7.1) and in the production Linux Docker image (UID 10001, FFmpeg 5.1.9). The image was built with an isolated context containing the Dockerfile and required `backend/` source files under ignored `client/build/thumbnail-docker/`:

```powershell
docker build -f client/build/thumbnail-docker/Dockerfile -t familytube-thumbnail-check client/build/thumbnail-docker
docker run --rm --network none --mount 'type=bind,source=E:/project/youtubelocal/backend,target=/tests/backend,readonly' --workdir /tests --env FAMILYTUBE_TEST_FFMPEG=ffmpeg --entrypoint python familytube-thumbnail-check -m unittest discover -s backend -p 'test_*.py'
```

The absolute mount source is specific to this checkout. A separate local production-image startup check passed with two original H.264 fixture copies mounted read-only, scan interval zero and default writable `/data/thumbnails`. It verified 640x360 dimensions, generated catalog URLs, GET/HEAD, 64-byte HTTP 206 requests, manual retry without regeneration, original-file SHA-256 preservation and graceful shutdown. Python compilation, CLI help and `git diff --check` also passed. The home server has not been updated; full-library decoding and NAS resource contention remain pending.
