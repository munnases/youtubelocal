import argparse
import hashlib
import json
import math
import mimetypes
import re
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

if __package__:
    from .recommendations import WatchStore
    from .catalog_scan import CatalogScanner
else:
    from recommendations import WatchStore
    from catalog_scan import CatalogScanner

PALETTES = [
    ["#6C63FF", "#9B8AFB"],
    ["#FF6B6B", "#FFA45B"],
    ["#00B894", "#55EFC4"],
    ["#0984E3", "#74B9FF"],
    ["#E84393", "#FD79A8"],
    ["#FDCB6E", "#F39C12"],
]
VIDEO_SUFFIXES = {".mp4", ".m4v", ".mov", ".webm"}
THUMBNAIL_TYPES = {'.webp': 'image/webp', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png'}


def load_catalog(path):
    if not path.exists():
        return {}
    try:
        items = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return {item.get("file"): item for item in items if item.get("file")}


def list_videos(media_dir, catalog_path):
    if not media_dir.is_dir():
        raise OSError('The media folder is unavailable.')
    catalog = load_catalog(catalog_path)
    videos = []
    for path in sorted(media_dir.rglob('*'), key=lambda value: value.as_posix().lower()):
        if not path.is_file() or path.suffix.lower() not in VIDEO_SUFFIXES:
            continue
        if not path.resolve().is_relative_to(media_dir.resolve()):
            continue
        relative_path = path.relative_to(media_dir).as_posix()
        try:
            details = json.loads(path.with_suffix('.info.json').read_text(encoding='utf-8'))
            if not isinstance(details, dict):
                details = {}
        except (OSError, json.JSONDecodeError):
            details = {}
        metadata = {**details, **catalog.get(relative_path, {})}
        stem = path.stem
        title = metadata.get("title") or re.sub(r"[-_]+", " ", stem).strip().title()
        video_id = catalog.get(relative_path, {}).get('id') or (
            stem if path.parent == media_dir else hashlib.sha256(relative_path.encode('utf-8')).hexdigest()[:20]
        )
        palette = PALETTES[len(videos) % len(PALETTES)]
        file_stat = path.stat()
        thumbnail = next((path.with_suffix(suffix) for suffix in THUMBNAIL_TYPES
                          if path.with_suffix(suffix).is_file()
                          and path.with_suffix(suffix).resolve().is_relative_to(media_dir.resolve())), None)
        videos.append(
            {
                "id": video_id,
                "title": title,
                "description": metadata.get("description", "A video from your local family library."),
                "category": metadata.get("category") or (path.parent.name if path.parent != media_dir else "All videos"),
                "duration": metadata.get("duration", 0),
                "file": relative_path,
                "addedAt": int(getattr(file_stat, 'st_birthtime', file_stat.st_mtime) * 1000),
                "thumbnailFile": thumbnail.relative_to(media_dir).as_posix() if thumbnail else None,
                "thumbnailVersion": str(thumbnail.stat().st_mtime_ns) if thumbnail else None,
                "accent": metadata.get("accent", palette),
                "age": metadata.get("age", "Family friendly"),
            }
        )
    return videos


class RequestHandler(BaseHTTPRequestHandler):
    media_dir = Path("backend/media")
    catalog_path = Path("backend/catalog.json")
    store = None
    scanner = None

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Range, Content-Type, X-Device-ID")
        self.end_headers()

    def do_HEAD(self):
        self.route(send_body=False)

    def do_GET(self):
        self.route(send_body=True)

    def device_id(self):
        device = self.headers.get('X-Device-ID', '')
        if not re.fullmatch(r'[A-Za-z0-9_-]{8,100}', device):
            raise ValueError('A valid X-Device-ID header is required.')
        return device

    def public_videos(self):
        videos = self.scanner.videos()
        host = self.headers.get('Host', f'127.0.0.1:{self.server.server_port}')
        for video in videos:
            video['streamUrl'] = f"http://{host}/media/{quote(str(video['id']), safe='')}"
            thumbnail = video.pop('thumbnailFile')
            version = video.pop('thumbnailVersion')
            video['thumbnailUrl'] = f"http://{host}/thumbnails/{quote(thumbnail, safe='/')}?v={version}" if thumbnail else None
        return videos

    def do_POST(self):
        path = urlparse(self.path).path
        if path not in ('/api/watch', '/api/watch/import', '/api/scan', '/api/scan/settings'):
            self.send_json({'error': 'Not found'}, 404)
            return
        try:
            device = self.device_id()
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 262144:
                raise ValueError('Request body must be between 1 and 262144 bytes.')
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError('Expected a JSON object.')
            if path == '/api/scan':
                self.send_json(self.scanner.request_scan(), 202)
                return
            if path == '/api/scan/settings':
                self.send_json(self.scanner.set_interval(body.get('intervalMinutes')))
                return
            valid_ids = {v['id'] for v in self.scanner.videos()}
            if path == '/api/watch/import':
                history = body.get('history')
                if not isinstance(history, dict) or len(history) > 5000:
                    raise ValueError('Invalid watch history.')
                cleaned = {}
                for video_id, item in history.items():
                    if video_id not in valid_ids:
                        continue
                    if not isinstance(item, dict) or type(item.get('views')) is not int or not 1 <= item['views'] <= 100000000:
                        raise ValueError('Invalid view count.')
                    recent = item.get('recentViews')
                    if not isinstance(recent, list) or len(recent) > 200:
                        raise ValueError('Invalid recent views.')
                    if any(type(t) not in (int, float) or not math.isfinite(t) or t <= 0 for t in [item.get('lastViewedAt'), *recent]):
                        raise ValueError('Invalid view date.')
                    cleaned[video_id] = item
                self.store.import_history(device, cleaned)
                self.send_json({'ok': True})
                return
            if body.get('videoId') not in valid_ids:
                self.send_json({'error': 'Video not found'}, 404)
                return
            if not isinstance(body.get('sessionId'), str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,100}', body['sessionId']):
                raise ValueError('Invalid playback session.')
            if type(body.get('sequence')) is not int or not 1 <= body['sequence'] <= 100000000:
                raise ValueError('Invalid sequence.')
            for key in ('position', 'duration', 'watchedSeconds', 'updatedAt'):
                value = body.get(key)
                maximum = 8640000000000000 if key == 'updatedAt' else 7 * 86400
                if type(value) not in (float, int) or not math.isfinite(value) or not 0 <= value <= maximum:
                    raise ValueError(f'Invalid {key}.')
            self.send_json(self.store.record_watch(device, body))
        except (ValueError, TypeError, OverflowError) as error:
            self.send_json({'error': str(error)}, 400)
        except sqlite3.Error:
            self.send_json({'error': 'Watch storage is temporarily unavailable. Please retry.'}, 503)

    def route(self, send_body):
        path = unquote(urlparse(self.path).path)
        if path == "/api/health":
            self.send_json({"ok": True, "videos": self.scanner.status()['videoCount']})
            return
        if path == '/api/scan':
            self.send_json(self.scanner.status())
            return
        if path == "/api/videos":
            self.send_json(self.public_videos())
            return
        if path in ('/api/recommendations', '/api/watch/history'):
            try:
                device = self.device_id()
                if path == '/api/watch/history':
                    self.send_json({'history': self.store.history(device)})
                    return
                query = parse_qs(urlparse(self.path).query)
                offset = int(query.get('offset', ['0'])[0])
                limit = int(query.get('limit', ['20'])[0])
                if offset < 0 or not 1 <= limit <= 50:
                    raise ValueError('Invalid page size or offset.')
                self.send_json(self.store.recommendations(device, self.public_videos(),
                    session=query.get('session', [None])[0], offset=offset, limit=limit,
                    exclude=query.get('exclude', [''])[0]))
            except LookupError as error:
                self.send_json({'error': str(error)}, 410)
            except (ValueError, TypeError, OverflowError) as error:
                self.send_json({'error': str(error)}, 400)
            except sqlite3.Error:
                self.send_json({'error': 'Suggestion storage is temporarily unavailable. Please retry.'}, 503)
            return
        if path.startswith('/thumbnails/'):
            self.serve_thumbnail(path.removeprefix('/thumbnails/'), send_body)
            return
        if path.startswith("/media/"):
            self.stream_video(path.removeprefix("/media/"), send_body)
            return
        self.send_error(404)

    def send_json(self, value, status=200):
        body = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def serve_thumbnail(self, relative_path, send_body):
        try:
            path = (self.media_dir / relative_path).resolve()
            if (not path.is_relative_to(self.media_dir.resolve())
                    or path.suffix.lower() not in THUMBNAIL_TYPES or not path.is_file()):
                self.send_error(404)
                return
            size = path.stat().st_size
            body = path.read_bytes() if send_body else None
        except (OSError, ValueError):
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', THUMBNAIL_TYPES[path.suffix.lower()])
        self.send_header('Content-Length', str(size))
        self.send_header('Cache-Control', 'public, max-age=86400')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        if body is not None:
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    def stream_video(self, video_id, send_body):
        videos = self.scanner.videos()
        match = next((video for video in videos if video["id"] == video_id), None)
        if match is None:
            self.send_error(404)
            return
        # Cached files can be removed or replaced between scans. Recheck their resolved path.
        try:
            path = (self.media_dir / match['file']).resolve()
            if not path.is_relative_to(self.media_dir.resolve()) or not path.is_file():
                self.send_error(404)
                return
            size = path.stat().st_size
        except OSError:
            self.send_error(404)
            return
        start, end = 0, size - 1
        status = 200
        range_header = self.headers.get("Range")
        if range_header:
            try:
                match_range = re.fullmatch(r'bytes=(\d*)-(\d*)', range_header)
                if not match_range or not any(match_range.groups()):
                    raise ValueError
                start_text, end_text = match_range.groups()
                if start_text:
                    start = int(start_text)
                    end = min(int(end_text), size - 1) if end_text else size - 1
                else:
                    suffix_length = int(end_text)
                    if suffix_length <= 0:
                        raise ValueError
                    start = max(0, size - suffix_length)
                if start < 0 or end < start or start >= size:
                    raise ValueError
                status = 206
            except (ValueError, OverflowError):
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Access-Control-Allow-Origin", "*")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if not send_body:
            return
        with path.open("rb") as file:
            file.seek(start)
            remaining = length
            while remaining:
                chunk = file.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    break
                remaining -= len(chunk)

    def log_message(self, format_string, *args):
        print(f"{self.address_string()} - {format_string % args}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--media", type=Path, default=Path("backend/media"))
    parser.add_argument("--catalog", type=Path, default=Path("backend/catalog.json"))
    parser.add_argument('--db', type=Path, default=Path('backend/data/familytube.sqlite3'))
    parser.add_argument('--scan-interval-minutes', type=int, default=None,
                        help='Override and save the scan interval: 0 disables periodic scans, 1-10080 sets minutes. Default: saved value or 15.')
    args = parser.parse_args()
    if args.scan_interval_minutes is not None and not 0 <= args.scan_interval_minutes <= 10080:
        parser.error('--scan-interval-minutes must be between 0 and 10080')
    args.media.mkdir(parents=True, exist_ok=True)
    RequestHandler.media_dir = args.media.resolve()
    RequestHandler.catalog_path = args.catalog.resolve()
    RequestHandler.store = WatchStore(args.db.resolve())
    RequestHandler.scanner = CatalogScanner(RequestHandler.store, lambda: list_videos(RequestHandler.media_dir, RequestHandler.catalog_path))
    if args.scan_interval_minutes is not None:
        RequestHandler.scanner.set_interval(args.scan_interval_minutes)
    RequestHandler.scanner.scan_once()
    RequestHandler.scanner.start()
    server = ThreadingHTTPServer((args.host, args.port), RequestHandler)
    print(f"FamilyTube is serving {RequestHandler.media_dir} at http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        RequestHandler.scanner.close()
        server.server_close()


if __name__ == "__main__":
    main()
