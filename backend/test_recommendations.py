import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from backend.recommendations import DAY, WatchStore, rank_videos

NOW = 1800000000000


class RecommendationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'watch.sqlite3'
        self.store = WatchStore(self.path)
        self.videos = [dict(id=str(i), category=f'category-{i % 4}', addedAt=NOW) for i in range(65)]

    def tearDown(self):
        self.temp.cleanup()

    def event(self, **changes):
        return dict(dict(videoId='0', sessionId='session-1', sequence=1, watchedSeconds=12,
                         position=12, duration=120, updatedAt=NOW), **changes)

    def test_progress_threshold_retries_and_out_of_order_updates(self):
        self.store.record_watch('device-a', self.event(watchedSeconds=5, position=5), NOW)
        self.assertEqual(self.store.history('device-a', NOW)['0']['views'], 0)
        self.store.record_watch('device-a', self.event(sequence=2), NOW)
        self.store.record_watch('device-a', self.event(sequence=2), NOW)
        self.store.record_watch('device-a', self.event(sequence=1, position=1), NOW)
        stats = self.store.history('device-a', NOW)['0']
        self.assertEqual((stats['views'], stats['watchedSeconds'], stats['position']), (1, 12, 12))
        self.store.record_watch('device-a', self.event(sequence=3, watchedSeconds=20, position=50), NOW)
        self.assertEqual(self.store.history('device-a', NOW)['0']['watchedSeconds'], 20)

    def test_cooldown_and_device_isolation_persist_across_restart(self):
        self.store.record_watch('device-a', self.event(), NOW)
        self.store.record_watch('device-a', self.event(sessionId='session-2'), NOW)
        self.store.record_watch('device-b', self.event(), NOW)
        later = NOW + 1800001
        self.store.record_watch('device-a', self.event(sessionId='session-3', updatedAt=later), later)
        restarted = WatchStore(self.path)
        self.assertEqual(restarted.history('device-a', later)['0']['views'], 2)
        self.assertEqual(restarted.history('device-b', later)['0']['views'], 1)

    def test_concurrent_duplicate_events_count_once(self):
        with ThreadPoolExecutor(max_workers=6) as pool:
            list(pool.map(lambda _: self.store.record_watch('device-a', self.event(), NOW), range(12)))
        self.assertEqual(self.store.history('device-a', NOW)['0']['views'], 1)
        self.assertEqual(self.store.history('device-a', NOW)['0']['watchedSeconds'], 12)

    def test_snapshot_paging_no_duplicates_stable_after_new_views_and_restart(self):
        first = self.store.recommendations('device-a', self.videos, limit=20, now=NOW)
        self.store.record_watch('device-a', self.event(), NOW)
        restarted = WatchStore(self.path)
        repeat = restarted.recommendations('device-a', self.videos, session=first['sessionId'], now=NOW)
        self.assertEqual(first, repeat)
        ids = [v['id'] for v in first['items']]
        offset = first['nextOffset']
        while offset is not None:
            page = restarted.recommendations('device-a', self.videos, session=first['sessionId'], offset=offset, now=NOW)
            ids.extend(v['id'] for v in page['items'])
            offset = page['nextOffset']
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(set(ids), {v['id'] for v in self.videos})
        with self.assertRaises(LookupError):
            restarted.recommendations('device-b', self.videos, session=first['sessionId'], now=NOW)

    def test_refresh_reranks_exclusion_expiry_and_removed_files(self):
        first = self.store.recommendations('device-a', self.videos, exclude='0', now=NOW)
        second = self.store.recommendations('device-a', self.videos, exclude='0', now=NOW)
        self.assertNotEqual(first['sessionId'], second['sessionId'])
        self.assertNotEqual(first['items'], second['items'])
        self.assertNotIn('0', [v['id'] for v in first['items']])
        removed = first['items'][0]['id']
        page = self.store.recommendations('device-a', [v for v in self.videos if v['id'] != removed], session=first['sessionId'], exclude='0', now=NOW)
        self.assertNotIn(removed, [v['id'] for v in page['items']])
        with self.assertRaises(LookupError):
            self.store.recommendations('device-a', self.videos, session=first['sessionId'], exclude='0', now=NOW + DAY + 1)

    def test_import_once_and_stable_added_dates(self):
        history = {'0': dict(views=8, lastViewedAt=NOW, recentViews=[NOW])}
        self.store.import_history('device-a', history, NOW)
        self.store.import_history('device-a', history, NOW)
        self.assertEqual(self.store.history('device-a', NOW)['0']['views'], 8)
        first = self.store.catalog_dates(self.videos)
        later = self.store.catalog_dates([{**v, 'addedAt': NOW + DAY} for v in self.videos])
        self.assertEqual(first, later)

    def test_weights_decay_cooldown_diversity_and_empty(self):
        self.assertEqual(rank_videos([], {}, NOW, 'seed'), [])
        ranked = rank_videos(self.videos, {}, NOW, 'seed')
        self.assertEqual(len({v['category'] for v in ranked[:4]}), 4)
        history = {'0': dict(views=100, lastViewedAt=NOW - DAY, recentViews=[NOW - DAY])}
        normal = rank_videos(self.videos, history, NOW, 'seed')
        cooled = rank_videos(self.videos, {'0': {**history['0'], 'lastViewedAt': NOW}}, NOW, 'seed')
        self.assertLess(next(v['score'] for v in cooled if v['id'] == '0'), next(v['score'] for v in normal if v['id'] == '0'))


if __name__ == '__main__':
    unittest.main()
