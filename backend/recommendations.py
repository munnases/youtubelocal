"""Device-specific watch records and stable, paginated recommendation snapshots."""
import hashlib
import json
import math
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

DAY = 86400000
WEIGHTS = dict(mostWatched=25, recentViews=20, recentlyAdded=20, random=25, discovery=10)


def rank_videos(videos, history, now, seed):
    max_views = max([1] + [item['views'] for item in history.values()])
    recent = {key: sum(math.exp(-max(0, now - t) / (7 * DAY)) for t in value['recentViews'])
              for key, value in history.items()}
    max_recent = max([1] + list(recent.values()))
    remaining = []
    for video in videos:
        watched = history.get(video['id'], {})
        views = watched.get('views', 0)
        signals = {
            'mostWatched': math.log1p(views) / math.log1p(max_views),
            'recentViews': recent.get(video['id'], 0) / max_recent,
            'recentlyAdded': math.exp(-max(0, now - video['addedAt']) / (14 * DAY)),
            'random': int(hashlib.sha256(f"{seed}:{video['id']}".encode()).hexdigest()[:13], 16) / 16**13,
            'discovery': 1 / (1 + views),
        }
        score = sum(WEIGHTS[key] * value for key, value in signals.items())
        if watched.get('lastViewedAt') and now - watched['lastViewedAt'] < 2 * 3600000:
            score *= 0.35
        remaining.append({'id': video['id'], 'category': video['category'], 'score': score})
    ranked, categories = [], {}
    while remaining:
        remaining.sort(key=lambda item: (-item['score'] / (1 + categories.get(item['category'], 0) * 0.7), item['id']))
        item = remaining.pop(0)
        item['score'] /= 1 + categories.get(item['category'], 0) * 0.7
        categories[item['category']] = categories.get(item['category'], 0) + 1
        ranked.append(item)
    return ranked


class WatchStore:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS video_dates (id TEXT PRIMARY KEY, added_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS watch_stats (
                    device_id TEXT NOT NULL, video_id TEXT NOT NULL, views INTEGER NOT NULL DEFAULT 0,
                    last_viewed_at INTEGER NOT NULL DEFAULT 0, watched_seconds REAL NOT NULL DEFAULT 0,
                    position REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (device_id, video_id));
                CREATE TABLE IF NOT EXISTS watch_sessions (
                    device_id TEXT NOT NULL, id TEXT NOT NULL, video_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL, watched_seconds REAL NOT NULL, counted_at INTEGER,
                    PRIMARY KEY (device_id, id));
                CREATE INDEX IF NOT EXISTS watch_recent ON watch_sessions(device_id, video_id, counted_at);
                CREATE TABLE IF NOT EXISTS recommendation_sessions (
                    id TEXT PRIMARY KEY, device_id TEXT NOT NULL, excluded_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL, ranking TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS recommendation_expiry ON recommendation_sessions(created_at);
                CREATE TABLE IF NOT EXISTS history_imports (device_id TEXT PRIMARY KEY);
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def touch_device(self, db, device, now):
        db.execute('INSERT INTO devices VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen', (device, now, now))

    def catalog_dates(self, videos):
        with self.connect() as db:
            db.executemany('INSERT OR IGNORE INTO video_dates VALUES (?, ?)', [(v['id'], v['addedAt']) for v in videos])
            dates = dict(db.execute('SELECT id, added_at FROM video_dates').fetchall())
        return [{**video, 'addedAt': dates[video['id']]} for video in videos]

    def record_watch(self, device, event, now=None):
        now = now if now is not None else int(time.time() * 1000)
        at = min(event['updatedAt'], now)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            self.touch_device(db, device, now)
            previous = db.execute('SELECT * FROM watch_sessions WHERE device_id=? AND id=?', (device, event['sessionId'])).fetchone()
            if previous and previous['video_id'] != event['videoId']:
                raise ValueError('A playback session belongs to one video.')
            if previous and previous['sequence'] >= event['sequence']:
                return {'ok': True, 'duplicate': True}
            db.execute('INSERT OR IGNORE INTO watch_stats(device_id,video_id) VALUES (?,?)', (device, event['videoId']))
            old_seconds = previous['watched_seconds'] if previous else 0
            watched = max(old_seconds, event['watchedSeconds'])
            counted_at = previous['counted_at'] if previous else None
            count_view = False
            if counted_at is None and watched >= 10:
                nearby = db.execute('SELECT 1 FROM watch_sessions WHERE device_id=? AND video_id=? AND counted_at BETWEEN ? AND ? LIMIT 1',
                                    (device, event['videoId'], at - 1800000, at + 1800000)).fetchone()
                # -1 records an already-qualified reopening suppressed by cooldown.
                counted_at = -1 if nearby else at
                count_view = not nearby
            db.execute('INSERT INTO watch_sessions VALUES (?,?,?,?,?,?) ON CONFLICT(device_id,id) DO UPDATE SET sequence=excluded.sequence, watched_seconds=excluded.watched_seconds, counted_at=excluded.counted_at',
                       (device, event['sessionId'], event['videoId'], event['sequence'], watched, counted_at))
            db.execute('''UPDATE watch_stats SET views=views+?, watched_seconds=watched_seconds+?,
                last_viewed_at=MAX(last_viewed_at,?),
                position=CASE WHEN updated_at<=? THEN ? ELSE position END,
                duration=CASE WHEN updated_at<=? THEN ? ELSE duration END,
                updated_at=MAX(updated_at,?) WHERE device_id=? AND video_id=?''',
                       (int(count_view), watched - old_seconds, at if count_view else 0,
                        at, event['position'], at, event['duration'], at, device, event['videoId']))
        return {'ok': True, 'countedView': count_view}

    def history(self, device, now=None):
        now = now if now is not None else int(time.time() * 1000)
        with self.connect() as db:
            stats = db.execute('SELECT * FROM watch_stats WHERE device_id=?', (device,)).fetchall()
            recent = db.execute('SELECT video_id,counted_at FROM watch_sessions WHERE device_id=? AND counted_at>?', (device, now - 30 * DAY)).fetchall()
        result = {row['video_id']: {'views': row['views'], 'lastViewedAt': row['last_viewed_at'],
                  'watchedSeconds': row['watched_seconds'], 'position': row['position'],
                  'duration': row['duration'], 'updatedAt': row['updated_at'], 'recentViews': []} for row in stats}
        for row in recent:
            result[row['video_id']]['recentViews'].append(row['counted_at'])
        return result

    def import_history(self, device, history, now=None):
        now = now if now is not None else int(time.time() * 1000)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            self.touch_device(db, device, now)
            if db.execute('SELECT 1 FROM history_imports WHERE device_id=?', (device,)).fetchone():
                return
            for video_id, item in history.items():
                db.execute('''INSERT INTO watch_stats(device_id,video_id,views,last_viewed_at) VALUES (?,?,?,?)
                    ON CONFLICT(device_id,video_id) DO UPDATE SET views=views+excluded.views,
                    last_viewed_at=MAX(last_viewed_at,excluded.last_viewed_at)''',
                           (device, video_id, item['views'], min(now, item['lastViewedAt'])))
                for i, at in enumerate(item['recentViews']):
                    db.execute('INSERT OR IGNORE INTO watch_sessions VALUES (?,?,?,?,?,?)',
                               (device, f'import:{video_id}:{i}', video_id, 0, 0, min(now, at)))
            db.execute('INSERT INTO history_imports VALUES (?)', (device,))

    def recommendations(self, device, videos, session=None, offset=0, limit=20, exclude='', now=None):
        now = now if now is not None else int(time.time() * 1000)
        with self.connect() as db:
            self.touch_device(db, device, now)
            db.execute('DELETE FROM recommendation_sessions WHERE created_at<?', (now - DAY,))
            if session:
                row = db.execute('SELECT * FROM recommendation_sessions WHERE id=? AND device_id=? AND excluded_id=?', (session, device, exclude)).fetchone()
                if row is None:
                    raise LookupError('This suggestion list expired. Refresh to load a new list.')
                ranking = json.loads(row['ranking'])
            else:
                if offset:
                    raise ValueError('A session is required for subsequent pages.')
                session = secrets.token_hex(16)
                ranking = rank_videos([v for v in videos if v['id'] != exclude], self.history(device, now), now, session)
                db.execute('INSERT INTO recommendation_sessions VALUES (?,?,?,?,?)', (session, device, exclude, now, json.dumps(ranking)))
        by_id = {v['id']: v for v in videos}
        page = ranking[offset:offset + limit]
        next_offset = offset + len(page)
        return {'sessionId': session, 'items': [{**by_id[item['id']], 'recommendationScore': round(item['score'], 4)} for item in page if item['id'] in by_id],
                'nextOffset': next_offset if next_offset < len(ranking) else None, 'total': len(ranking)}
