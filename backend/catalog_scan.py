"""Cached catalog with a persistent, server-wide background scan schedule."""
import json
import threading
import time


class CatalogScanner:
    def __init__(self, store, discover, clock=time.time):
        self.store = store
        self.discover = discover
        self.clock = clock
        self.condition = threading.Condition()
        self.scanning = False
        self.requested = False
        self.stopped = False
        self.thread = None
        self.on_scan = None
        with store.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS catalog_scan_state (
                    id INTEGER PRIMARY KEY CHECK(id=1), interval_minutes INTEGER NOT NULL,
                    last_scan_at INTEGER, last_error TEXT);
                INSERT OR IGNORE INTO catalog_scan_state VALUES (1,15,NULL,NULL);
                CREATE TABLE IF NOT EXISTS cached_videos (id TEXT PRIMARY KEY, metadata TEXT NOT NULL);
            ''')
            state = db.execute('SELECT * FROM catalog_scan_state WHERE id=1').fetchone()
            self.interval = state['interval_minutes']
            self.last_scan_at = state['last_scan_at']
            self.last_error = state['last_error']
            self.cached = [json.loads(row['metadata']) for row in db.execute('SELECT metadata FROM cached_videos ORDER BY rowid')]
        self.next_scan_at = self.clock() + self.interval * 60 if self.interval else None

    def videos(self):
        with self.condition:
            # API serialization must not modify the cached metadata.
            return [dict(video) for video in self.cached]

    def status(self):
        with self.condition:
            return {'intervalMinutes': self.interval, 'scanning': self.scanning or self.requested,
                    'videoCount': len(self.cached), 'lastScanAt': self.last_scan_at,
                    'nextScanAt': int(self.next_scan_at * 1000) if self.next_scan_at else None,
                    'lastError': self.last_error}

    def publish_thumbnail(self, video_id, file, artwork):
        """Publish worker artwork without decoding or rescanning on request threads."""
        with self.condition:
            for index, video in enumerate(self.cached):
                if video['id'] == video_id and video['file'] == file:
                    updated = {**video, **artwork}
                    if updated == video:
                        return
                    with self.store.connect() as db:
                        db.execute('UPDATE cached_videos SET metadata=? WHERE id=?',
                                   (json.dumps(updated), video_id))
                    self.cached[index] = updated
                    return

    def set_interval(self, minutes):
        if type(minutes) is not int or not 0 <= minutes <= 10080:
            raise ValueError('intervalMinutes must be an integer from 0 (off) to 10080 (one week).')
        with self.condition:
            with self.store.connect() as db:
                db.execute('UPDATE catalog_scan_state SET interval_minutes=? WHERE id=1', (minutes,))
            self.interval = minutes
            self.next_scan_at = self.clock() + minutes * 60 if minutes else None
            self.condition.notify_all()
        return self.status()

    def request_scan(self):
        with self.condition:
            if not self.scanning:
                self.requested = True
                self.condition.notify_all()
        return self.status()

    def scan_once(self):
        """Synchronous startup scan; HTTP requests use request_scan instead."""
        with self.condition:
            if self.scanning:
                return False
            self.scanning = True
            self.requested = False
        return self._perform_scan()

    def _perform_scan(self):
        succeeded = False
        try:
            videos = self.store.catalog_dates(self.discover())
            completed = int(self.clock() * 1000)
            with self.condition:
                with self.store.connect() as db:
                    db.execute('DELETE FROM cached_videos')
                    db.executemany('INSERT INTO cached_videos VALUES (?,?)', [(v['id'], json.dumps(v)) for v in videos])
                    db.execute('UPDATE catalog_scan_state SET last_scan_at=?,last_error=NULL WHERE id=1', (completed,))
                self.cached = videos
                self.last_scan_at = completed
                self.last_error = None
            succeeded = True
        except Exception as error:
            message = str(error) or type(error).__name__
            with self.condition:
                self.last_error = message
            try:
                with self.store.connect() as db:
                    db.execute('UPDATE catalog_scan_state SET last_error=? WHERE id=1', (message,))
            except Exception:
                pass  # Preserve the previous in-memory catalog even if storage is unavailable.
        finally:
            with self.condition:
                self.scanning = False
                self.next_scan_at = self.clock() + self.interval * 60 if self.interval else None
                self.condition.notify_all()
        if succeeded and self.on_scan is not None:
            self.on_scan()
        return succeeded

    def start(self):
        with self.condition:
            if self.thread is not None:
                return
            self.thread = threading.Thread(target=self._run, name='catalog-scanner', daemon=True)
            self.thread.start()

    def _run(self):
        while True:
            with self.condition:
                while not self.stopped:
                    due = self.next_scan_at is not None and self.clock() >= self.next_scan_at
                    if not self.scanning and (self.requested or due):
                        self.scanning = True
                        self.requested = False
                        break
                    timeout = max(0, self.next_scan_at - self.clock()) if self.next_scan_at and not self.scanning else None
                    self.condition.wait(timeout)
                if self.stopped:
                    return
            self._perform_scan()

    def close(self):
        with self.condition:
            self.stopped = True
            self.condition.notify_all()
        if self.thread is not None:
            self.thread.join(timeout=5)
