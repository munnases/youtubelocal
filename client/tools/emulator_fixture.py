"""Isolated backend for emulator checks. Never points at the home server or its databases."""
import argparse
import json
from pathlib import Path
import shutil
import sys
import threading
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from backend.server import RequestHandler, list_videos
from backend.catalog_scan import CatalogScanner
from backend.recommendations import WatchStore


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    media = root / "media"
    catalog = root / "catalog.json"
    for category in ("Songs", "Stories"):
        folder = media / category
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / "sample.mp4"
        if not target.exists():
            shutil.copyfile(args.sample, target)
    catalog.write_text(json.dumps([
        {"file": "Songs/sample.mp4", "id": "song", "title": "Song Alpha", "duration": 0},
        {"file": "Stories/sample.mp4", "id": "story", "title": "Story Beta", "duration": 0},
    ]), encoding="utf-8")
    store = WatchStore(root / "watch.sqlite3")
    scanner = CatalogScanner(store, lambda: list_videos(media, catalog))
    scanner.scan_once()
    state = {"offline": False, "dropWatchResponses": 0, "events": []}
    lock = threading.Lock()

    class Handler(RequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if self.path == "/__test/state":
                with lock:
                    data = {**state, "history": {device: store.history(device) for device in
                            {event["device"] for event in state["events"]}}}
                self.send_json(data)
                return
            if state["offline"]:
                self.send_json({"error": "Fixture offline"}, 503)
                return
            super().do_GET()

        def do_POST(self):
            if self.path == "/__test/control":
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                with lock:
                    for key in ("offline", "dropWatchResponses"):
                        if key in payload:
                            state[key] = payload[key]
                self.send_json({"ok": True})
                if payload.get("shutdown") is True:
                    threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            if state["offline"]:
                self.send_json({"error": "Fixture offline"}, 503)
                return
            super().do_POST()

        def send_json(self, value, status=200):
            if self.path == "/api/watch" and status == 200:
                with lock:
                    state["events"].append({"device": self.device_id(), "result": value})
                    if state["dropWatchResponses"] > 0:
                        state["dropWatchResponses"] -= 1
                        self.close_connection = True
                        return  # Stored by the backend, but response lost: client must retry safely.
            super().send_json(value, status)

    Handler.media_dir = media
    Handler.catalog_path = catalog
    Handler.store = store
    Handler.scanner = scanner
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Fixture listening on 127.0.0.1:{args.port}; output={root}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        scanner.close()


if __name__ == "__main__":
    main()
