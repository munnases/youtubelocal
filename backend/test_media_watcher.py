import unittest
from unittest.mock import Mock

from backend.catalog_scan import MediaWatcher


class MediaWatcherTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = Mock(return_value=('original',))
        self.changed = Mock()
        self.watcher = MediaWatcher(self.snapshot, self.changed)
        self.watcher.poll()

    def test_changes_settle_before_scan_and_unchanged_files_do_not_rescan(self):
        self.snapshot.return_value = ('original', 'growing')
        self.watcher.poll()
        self.snapshot.return_value = ('original', 'finished')
        self.watcher.poll()
        self.changed.assert_not_called()
        self.watcher.poll()
        self.changed.assert_called_once_with()
        self.watcher.poll()
        self.changed.assert_called_once_with()

    def test_removal_and_replacement_each_request_a_scan(self):
        for value in ((), ('replacement',)):
            self.snapshot.return_value = value
            self.watcher.poll()
            self.watcher.poll()
        self.assertEqual(self.changed.call_count, 2)

    def test_snapshot_failure_retries_even_when_restored_files_are_unchanged(self):
        self.snapshot.side_effect = OSError('Media disconnected')
        with self.assertLogs(level='WARNING'):
            self.watcher.poll()
        self.changed.assert_not_called()
        self.snapshot.side_effect = None
        self.watcher.poll()
        self.changed.assert_not_called()
        self.watcher.poll()
        self.changed.assert_called_once_with()
        self.assertIsNone(self.watcher.last_error)

    def test_failed_callback_is_retried(self):
        self.snapshot.return_value = ('new',)
        self.watcher.poll()
        self.changed.side_effect = RuntimeError('Busy')
        with self.assertLogs(level='WARNING'):
            self.watcher.poll()
        self.changed.side_effect = None
        self.watcher.poll()
        self.watcher.poll()
        self.assertEqual(self.changed.call_count, 2)

    def test_shutdown_interrupts_long_poll_wait(self):
        self.watcher.interval_seconds = 3600
        self.watcher.start()
        self.watcher.close()
        self.assertFalse(self.watcher.thread.is_alive())


if __name__ == '__main__':
    unittest.main()
