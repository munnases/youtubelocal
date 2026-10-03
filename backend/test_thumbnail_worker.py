import os
import json
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from backend.catalog_scan import CatalogScanner
from backend.recommendations import WatchStore
from backend.server import list_videos
from backend.thumbnail_worker import ThumbnailWorker, source_key


JPEG = b'\xff\xd8test-frame\xff\xd9'


class ThumbnailFixture:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.media = self.root / 'media'
        self.media.mkdir()
        self.cache = self.root / 'data' / 'thumbnails'
        self.catalog = self.root / 'catalog.json'
        self.catalog.write_text('[]', encoding='utf-8')
        self.source = self.media / 'Video #1 🎬.mp4'
        self.source.write_bytes(b'original video')
        self.store = WatchStore(self.root / 'data' / 'watch.sqlite3')
        self.scanner = CatalogScanner(self.store, lambda: list_videos(self.media, self.catalog, self.cache))
        self.worker = ThumbnailWorker(self.media, self.cache, self.scanner)
        self.scanner.on_scan = self.worker.request_work

    def tearDown(self):
        self.scanner.close()
        self.worker.close()
        self.temp.cleanup()

    def wait_idle(self):
        with self.worker.condition:
            self.assertTrue(self.worker.condition.wait_for(
                lambda: not self.worker.running and not self.worker.requested, timeout=3))

    def extract(self, source, destination, seconds):
        destination.write_bytes(JPEG)

    def run_worker(self):
        self.assertTrue(self.scanner.scan_once(), self.scanner.status())
        self.worker.start()
        self.wait_idle()


class ThumbnailWorkerTest(ThumbnailFixture, unittest.TestCase):
    def test_supplied_artwork_is_preserved_and_never_decoded(self):
        poster = self.source.with_suffix('.png')
        poster.write_bytes(b'parent artwork')
        with patch.object(self.worker, '_extract', side_effect=AssertionError('Must not decode')):
            self.run_worker()
        self.assertEqual(poster.read_bytes(), b'parent artwork')
        self.assertEqual(self.source.read_bytes(), b'original video')
        self.assertFalse(self.cache.exists())
        video = self.scanner.videos()[0]
        self.assertEqual(video['thumbnailFile'], poster.name)
        self.assertFalse(video['thumbnailGenerated'])

    def test_generated_artwork_is_reused_and_persisted_across_restart(self):
        with patch.object(self.worker, '_extract', side_effect=self.extract) as extract:
            self.run_worker()
            video = self.scanner.videos()[0]
            self.worker.request_work()
            self.wait_idle()
            self.scanner.scan_once()
            self.wait_idle()
            self.assertEqual(extract.call_count, 1)
        self.assertTrue(video['thumbnailGenerated'])
        self.assertEqual((self.cache / video['thumbnailFile']).read_bytes(), JPEG)
        self.assertEqual(self.source.read_bytes(), b'original video')
        self.assertEqual(list(self.media.iterdir()), [self.source])
        restored = CatalogScanner(self.store, lambda: [])
        self.assertEqual(restored.videos()[0]['thumbnailFile'], video['thumbnailFile'])
        self.assertEqual(list_videos(self.media, self.catalog, self.cache)[0]['thumbnailFile'], video['thumbnailFile'])
        self.assertEqual(list(self.cache.glob('.thumbnail-*')), [])

    def test_replaced_video_uses_new_cache_key_and_supplied_artwork_wins(self):
        with patch.object(self.worker, '_extract', side_effect=self.extract) as extract:
            self.run_worker()
            first = self.scanner.videos()[0]['thumbnailFile']
            self.source.write_bytes(b'replacement with a different size')
            self.scanner.scan_once()
            self.wait_idle()
            second = self.scanner.videos()[0]['thumbnailFile']
            self.assertNotEqual(first, second)
            self.assertEqual(extract.call_count, 2)
            self.source.with_suffix('.webp').write_bytes(b'new parent poster')
            self.scanner.scan_once()
            self.wait_idle()
            self.assertEqual(extract.call_count, 2)
            self.assertFalse(self.scanner.videos()[0]['thumbnailGenerated'])

    def test_same_stem_different_containers_and_nested_files_do_not_collide(self):
        self.source.with_suffix('.webm').write_bytes(b'another video')
        nested = self.media / 'Other channel'
        nested.mkdir()
        (nested / self.source.name).write_bytes(b'original video')
        # Root videos with identical stems need explicit IDs under the existing API semantics.
        self.catalog.write_text(json.dumps([
            {'file': self.source.name, 'id': 'mp4-video'},
            {'file': self.source.with_suffix('.webm').name, 'id': 'webm-video'},
        ]), encoding='utf-8')
        with patch.object(self.worker, '_extract', side_effect=self.extract):
            self.run_worker()
        names = [v['thumbnailFile'] for v in self.scanner.videos()]
        self.assertEqual(len(set(names)), 3)

    def test_short_clip_falls_back_to_first_frame(self):
        def extract(source, destination, seconds):
            destination.write_bytes(b'' if seconds else JPEG)
        with patch.object(self.worker, '_extract', side_effect=extract) as mocked:
            self.run_worker()
        self.assertEqual([call.args[2] for call in mocked.call_args_list], [1, 0])
        self.assertEqual(self.worker.status()['generatedCount'], 1)

    def test_replaced_input_during_extraction_does_not_publish_partial_or_stale_image(self):
        def extract(source, destination, seconds):
            self.extract(source, destination, seconds)
            self.source.write_bytes(b'replaced while worker was decoding')
        with patch.object(self.worker, '_extract', side_effect=extract):
            self.run_worker()
        self.assertIsNone(self.scanner.videos()[0]['thumbnailFile'])
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_failed_item_does_not_block_other_videos_and_can_be_retried(self):
        (self.media / 'Z good.mp4').write_bytes(b'good video')
        def extract(source, destination, seconds):
            if source == self.source:
                destination.write_bytes(b'partial jpeg')
                raise RuntimeError('Unsupported video')
            self.extract(source, destination, seconds)
        with patch.object(self.worker, '_extract', side_effect=extract):
            self.run_worker()
        self.assertEqual(self.worker.status()['generatedCount'], 1)
        self.assertEqual(self.worker.status()['failedCount'], 1)
        self.assertIn('Unsupported video', self.worker.status()['lastError'])
        self.assertIsNone(self.scanner.videos()[0]['thumbnailFile'])
        self.assertIsNotNone(self.scanner.videos()[1]['thumbnailFile'])
        self.assertEqual(list(self.cache.glob('.thumbnail-*')), [])
        with patch.object(self.worker, '_extract', side_effect=self.extract):
            self.worker.request_work()
            self.wait_idle()
        self.assertEqual(self.worker.status()['generatedCount'], 2)
        self.assertIsNone(self.worker.status()['lastError'])

    def test_invalid_frame_is_not_published(self):
        with patch.object(self.worker, '_extract', side_effect=lambda s, d, t: d.write_bytes(b'broken')):
            self.run_worker()
        self.assertIn('complete JPEG', self.worker.status()['lastError'])
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_scans_and_repeated_requests_coalesce_while_catalog_remains_readable(self):
        entered, release = threading.Event(), threading.Event()
        def extract(source, destination, seconds):
            entered.set()
            self.assertTrue(release.wait(3))
            self.extract(source, destination, seconds)
        with patch.object(self.worker, '_extract', side_effect=extract) as mocked:
            self.scanner.set_interval(0)
            self.scanner.scan_once()
            self.worker.start()
            self.assertTrue(entered.wait(2))
            try:
                for _ in range(10):
                    self.worker.request_work()
                self.assertTrue(self.scanner.scan_once())
                self.assertIsNone(self.scanner.videos()[0]['thumbnailFile'])
            finally:
                release.set()
            self.wait_idle()
            self.assertEqual(mocked.call_count, 1)
        self.assertIsNotNone(self.scanner.videos()[0]['thumbnailFile'])

    def test_missing_ffmpeg_is_reported_without_leaking_temporary_files(self):
        self.worker.ffmpeg = str(self.root / 'missing-ffmpeg.exe')
        (self.media / 'Z other.mp4').write_bytes(b'other video')
        self.run_worker()
        self.assertIsNotNone(self.worker.status()['lastError'])
        self.assertEqual(self.worker.status()['failedCount'], 1)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_timeout_and_shutdown_kill_decoder_and_leave_no_partial_files(self):
        real_popen = subprocess.Popen
        entered = threading.Event()
        processes = []
        def sleeping_decoder(*args, **kwargs):
            process = real_popen([sys.executable, '-c', 'import time; time.sleep(30)'], **kwargs)
            processes.append(process)
            entered.set()
            return process
        with patch('backend.thumbnail_worker.subprocess.Popen', side_effect=sleeping_decoder):
            self.worker.timeout = .05
            self.run_worker()
            self.assertIn('exceeded', self.worker.status()['lastError'])
            self.assertIsNotNone(processes[0].poll())
            self.assertEqual(list(self.cache.iterdir()), [])
            self.worker.timeout = 60
            entered.clear()
            self.worker.request_work()
            self.assertTrue(entered.wait(2))
            self.worker.close()
            self.assertFalse(self.worker.thread.is_alive())
            self.assertIsNotNone(processes[-1].poll())
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_source_outside_media_is_rejected(self):
        outside = self.root / 'private.mp4'
        outside.write_bytes(b'private')
        with self.assertRaises(OSError):
            source_key(self.media, self.media / '..' / outside.name)


@unittest.skipUnless(os.environ.get('FAMILYTUBE_TEST_FFMPEG'), 'Set FAMILYTUBE_TEST_FFMPEG for real decoding checks')
class RealThumbnailTest(ThumbnailFixture, unittest.TestCase):
    def test_real_mp4_webm_and_short_clip(self):
        executable = os.environ['FAMILYTUBE_TEST_FFMPEG']
        self.source.unlink()
        self.worker.ffmpeg = executable
        for name, duration, codec in [('Video #1 🎬.mp4', '2', 'mpeg4'),
                                      ('short.mov', '0.2', 'mpeg4'),
                                      ('nested/video.webm', '2', 'libvpx-vp9')]:
            source = self.media / name
            source.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([executable, '-hide_banner', '-loglevel', 'error', '-y',
                            '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:r=10',
                            '-t', duration, '-c:v', codec, '-threads', '1', str(source)],
                           check=True, timeout=15, capture_output=True)
        originals = {path: path.read_bytes() for path in self.media.rglob('*') if path.is_file()}
        self.run_worker()
        self.assertIsNone(self.worker.status()['lastError'])
        self.assertEqual(self.worker.status()['generatedCount'], 3)
        for video in self.scanner.videos():
            image = self.cache / video['thumbnailFile']
            subprocess.run([executable, '-v', 'error', '-i', str(image),
                            '-f', 'null', '-'], check=True, capture_output=True, timeout=10)
        for path, data in originals.items():
            self.assertEqual(path.read_bytes(), data)


if __name__ == '__main__':
    unittest.main()
