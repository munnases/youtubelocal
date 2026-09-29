import tempfile
import threading
import time
import unittest
from pathlib import Path

from backend.catalog_scan import CatalogScanner
from backend.recommendations import WatchStore


class CatalogScanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = WatchStore(Path(self.temp.name) / 'watch.sqlite3')
        self.clock = 1000
        self.source = [dict(id='a', title='First', addedAt=100000, category='Learning')]
        self.scanner = CatalogScanner(self.store, lambda: [dict(v) for v in self.source], clock=lambda: self.clock)

    def tearDown(self):
        self.scanner.close()
        self.temp.cleanup()

    def wait_complete(self):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline and self.scanner.status()['scanning']:
            time.sleep(.005)
        self.assertFalse(self.scanner.status()['scanning'])

    def test_schedule_and_cached_metadata_survive_restart(self):
        self.assertEqual(self.scanner.status()['intervalMinutes'], 15)
        self.scanner.set_interval(60)
        self.assertTrue(self.scanner.scan_once())
        cached = self.scanner.videos()
        cached[0]['title'] = 'Do not mutate the cache'
        restored = CatalogScanner(self.store, lambda: [], clock=lambda: self.clock)
        self.assertEqual(restored.status()['intervalMinutes'], 60)
        self.assertEqual(restored.videos()[0]['title'], 'First')
        self.assertEqual(restored.status()['lastScanAt'], 1000000)

    def test_background_scanner_runs_when_schedule_is_due(self):
        called = threading.Event()
        self.scanner.discover = lambda: (called.set(), list(self.source))[1]
        self.scanner.set_interval(1)
        self.clock += 61
        self.scanner.start()
        self.assertTrue(called.wait(2))
        self.wait_complete()
        self.assertEqual(self.scanner.status()['videoCount'], 1)
        self.assertEqual(self.scanner.status()['nextScanAt'], (self.clock + 60) * 1000)

    def test_off_disables_schedule_but_manual_scan_still_works(self):
        self.scanner.set_interval(0)
        self.clock += 100000
        self.scanner.start()
        self.assertEqual(self.scanner.status()['videoCount'], 0)
        self.assertIsNone(self.scanner.status()['nextScanAt'])
        self.scanner.request_scan()
        self.wait_complete()
        self.assertEqual(self.scanner.status()['videoCount'], 1)
        self.assertIsNone(self.scanner.status()['nextScanAt'])

    def test_add_remove_and_update_are_published_together_after_scan(self):
        self.scanner.scan_once()
        self.source = [dict(id='b', title='New', addedAt=200000, category='Music')]
        self.assertEqual([v['id'] for v in self.scanner.videos()], ['a'])
        self.scanner.scan_once()
        self.assertEqual([v['id'] for v in self.scanner.videos()], ['b'])
        self.source[0]['title'] = 'Updated'
        self.scanner.scan_once()
        self.assertEqual(self.scanner.videos()[0]['title'], 'Updated')

    def test_failed_scan_preserves_last_catalog_and_recovers(self):
        self.scanner.scan_once()
        self.scanner.discover = lambda: (_ for _ in ()).throw(OSError('Folder is offline'))
        self.assertFalse(self.scanner.scan_once())
        self.assertEqual(self.scanner.videos()[0]['id'], 'a')
        self.assertEqual(self.scanner.status()['lastError'], 'Folder is offline')
        self.scanner.discover = lambda: []
        self.assertTrue(self.scanner.scan_once())
        self.assertEqual(self.scanner.videos(), [])
        self.assertIsNone(self.scanner.status()['lastError'])

    def test_overlapping_requests_share_one_scan_and_readers_keep_old_snapshot(self):
        self.scanner.scan_once()
        started, release = threading.Event(), threading.Event()
        calls = []
        def discover():
            calls.append(1)
            started.set()
            release.wait(2)
            return []
        self.scanner.discover = discover
        self.scanner.start()
        self.scanner.request_scan()
        self.assertTrue(started.wait(2))
        try:
            for _ in range(10):
                self.assertTrue(self.scanner.request_scan()['scanning'])
            self.assertFalse(self.scanner.scan_once())
            self.assertEqual(self.scanner.videos()[0]['id'], 'a')
        finally:
            release.set()
        self.wait_complete()
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.scanner.videos(), [])


if __name__ == '__main__':
    unittest.main()
