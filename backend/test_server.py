import json
import base64
import os
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from http.client import HTTPConnection
from pathlib import Path
from http.server import ThreadingHTTPServer
from urllib.parse import quote
from urllib.parse import urlsplit

from backend.server import RequestHandler, list_videos, media_snapshot
from backend.recommendations import WatchStore
from backend.catalog_scan import CatalogScanner, MediaWatcher
from backend.thumbnail_worker import ThumbnailWorker


class MediaServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        for channel in ('Channel A', 'Channel B'):
            folder = self.root / channel
            folder.mkdir()
            (folder / 'Video #1 🎬.mp4').write_bytes(b'0123456789')
        (self.root / 'Channel A' / 'Video #1 🎬.info.json').write_text(
            json.dumps({'title': 'My video', 'duration': 12}), encoding='utf-8'
        )
        self.catalog = self.root / 'catalog.json'
        self.catalog.write_text('[]', encoding='utf-8')
        self.thumbnail_dir = self.root / 'generated-artwork'
        self.worker = None
        store = WatchStore(self.root / 'watch.sqlite3')
        self.scanner = CatalogScanner(store, lambda: list_videos(self.root, self.catalog, self.thumbnail_dir))
        self.scanner.scan_once()
        self.scanner.start()
        handler = type('TestHandler', (RequestHandler,), {
            'media_dir': self.root, 'catalog_path': self.catalog,
            'store': store, 'scanner': self.scanner,
            'thumbnail_dir': self.thumbnail_dir, 'thumbnail_worker': None,
            'log_message': lambda *args: None,
        })
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.scanner.close()
        if self.worker:
            self.worker.close()
        self.directory.cleanup()

    def request(self, method, path, headers=None, body=None):
        connection = HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_recursive_discovery_metadata_and_unique_ids(self):
        videos = list_videos(self.root, self.catalog)
        self.assertEqual(len(videos), 2)
        self.assertNotEqual(videos[0]['id'], videos[1]['id'])
        self.assertEqual(videos[0]['title'], 'My video')
        self.assertEqual(videos[0]['category'], 'Channel A')
        self.assertEqual(videos[0]['duration'], 12)
        self.assertGreater(videos[0]['addedAt'], 0)
        self.assertEqual(videos[0]['addedAt'], list_videos(self.root, self.catalog)[0]['addedAt'])
        status, _, body = self.request('GET', '/api/videos')
        self.assertEqual(status, 200)
        video = json.loads(body)[0]
        status, _, body = self.request('GET', '/media/' + quote(video['id'], safe=''))
        self.assertEqual((status, body), (200, b'0123456789'))

    def test_downloader_private_directories_and_partial_files_are_not_catalogued(self):
        before = media_snapshot(self.root, self.catalog)
        for name in ('.metube', '.metube-tmp'):
            folder = self.root / name / 'nested'
            folder.mkdir(parents=True)
            (folder / 'unfinished.mp4').write_bytes(b'partial')
            (folder / 'unfinished.info.json').write_text('{}', encoding='utf-8')
        (self.root / 'unfinished.mp4.part').write_bytes(b'partial')
        self.assertEqual(media_snapshot(self.root, self.catalog), before)
        self.scanner.scan_once()
        videos = json.loads(self.request('GET', '/api/videos')[2])
        self.assertEqual(len(videos), 2)
        self.assertTrue(all('unfinished' not in video['file'] for video in videos))

    def test_watcher_publishes_download_metadata_replacement_and_removal_with_schedule_off(self):
        self.scanner.set_interval(0)
        watcher = MediaWatcher(
            lambda: media_snapshot(self.root, self.catalog),
            lambda: self.scanner.request_scan(queue_if_scanning=True), interval_seconds=.02)
        watcher.start()

        def wait_for(predicate):
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                videos = json.loads(self.request('GET', '/api/videos')[2])
                if predicate(videos):
                    return videos
                time.sleep(.01)
            self.fail('Watcher did not publish the expected catalog')

        try:
            staging = self.root / '.metube-tmp'
            staging.mkdir()
            (staging / 'Downloaded [abc].mp4').write_bytes(b'new-video-data')
            (staging / 'Downloaded [abc].info.json').write_text(
                json.dumps({'title': 'Downloaded title', 'duration': 42}), encoding='utf-8')
            target = self.root / 'Downloaded [abc].mp4'
            metadata = target.with_suffix('.info.json')
            (staging / metadata.name).rename(metadata)
            (staging / target.name).rename(target)
            videos = wait_for(lambda videos: len(videos) == 3)
            downloaded = next(v for v in videos if v['file'] == target.name)
            self.assertEqual((downloaded['title'], downloaded['duration']), ('Downloaded title', 42))
            self.assertEqual(self.request('GET', '/media/' + quote(downloaded['id'], safe=''),
                                          {'Range': 'bytes=0-2'})[::2], (206, b'new'))
            metadata.write_text(json.dumps({'title': 'Updated title'}), encoding='utf-8')
            wait_for(lambda videos: any(v['title'] == 'Updated title' for v in videos))
            target.unlink()
            wait_for(lambda videos: len(videos) == 2)
            self.assertEqual(self.scanner.status()['intervalMinutes'], 0)
            self.assertIsNone(self.scanner.status()['nextScanAt'])
        finally:
            watcher.close()

    def test_stream_ranges_and_head(self):
        video = list_videos(self.root, self.catalog)[0]
        path = '/media/' + video['id']
        for value, expected in [('bytes=2-4', b'234'), ('bytes=7-', b'789'), ('bytes=-3', b'789'), ('bytes=8-99', b'89')]:
            with self.subTest(value=value):
                status, headers, body = self.request('GET', path, {'Range': value})
                self.assertEqual(status, 206)
                self.assertEqual(body, expected)
                self.assertEqual(int(headers['Content-Length']), len(expected))
        status, headers, body = self.request('HEAD', path)
        self.assertEqual((status, headers['Content-Length'], body), (200, '10', b''))
        for value in ('bytes=99-', 'bytes=-0', 'bytes=-', 'invalid=1-2'):
            status, headers, _ = self.request('GET', path, {'Range': value})
            self.assertEqual(status, 416)
            self.assertEqual(headers['Content-Range'], 'bytes */10')

    def test_custom_ids_are_url_encoded_and_cannot_escape_media(self):
        self.catalog.write_text(json.dumps([{'file': 'Channel A/Video #1 🎬.mp4', 'id': 'custom #?🎬'}]), encoding='utf-8')
        self.scanner.scan_once()
        _, _, body = self.request('GET', '/api/videos')
        video = json.loads(body)[0]
        self.assertTrue(video['streamUrl'].endswith(quote(video['id'], safe='')))
        status, _, _ = self.request('GET', '/media/' + quote(video['id'], safe=''))
        self.assertEqual(status, 200)
        status, _, _ = self.request('GET', '/media/../../catalog.json')
        self.assertEqual(status, 404)

    def test_thumbnail_discovery_streaming_and_head(self):
        thumbnail = self.root / 'Channel A' / 'Video #1 🎬.png'
        image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=')
        thumbnail.write_bytes(image)
        self.scanner.scan_once()
        _, _, body = self.request('GET', '/api/videos')
        videos = json.loads(body)
        self.assertIsNone(videos[1]['thumbnailUrl'])
        url = urlsplit(videos[0]['thumbnailUrl'])
        self.assertIn('%23', url.path)
        status, headers, body = self.request('GET', url.path + '?' + url.query)
        self.assertEqual((status, body), (200, image))
        self.assertEqual(headers['Content-Type'], 'image/png')
        self.assertIn('max-age=', headers['Cache-Control'])
        status, headers, body = self.request('HEAD', url.path)
        self.assertEqual((status, body), (200, b''))
        self.assertEqual(int(headers['Content-Length']), len(image))

    def test_thumbnail_version_changes_when_image_changes(self):
        thumbnail = self.root / 'Channel A' / 'Video #1 🎬.webp'
        thumbnail.write_bytes(b'thumbnail')
        self.scanner.scan_once()
        _, _, body = self.request('GET', '/api/videos')
        old_url = json.loads(body)[0]['thumbnailUrl']
        stat = thumbnail.stat()
        os.utime(thumbnail, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1_000_000_000))
        self.scanner.scan_once()
        _, _, body = self.request('GET', '/api/videos')
        self.assertNotEqual(old_url, json.loads(body)[0]['thumbnailUrl'])

    def test_thumbnail_route_rejects_missing_non_image_and_traversal(self):
        for path in ('/thumbnails/missing.webp', '/thumbnails/catalog.json',
                     '/thumbnails/%2e%2e/private.png', '/thumbnails/Channel%20A/Video%20%231%20%F0%9F%8E%AC.mp4'):
            with self.subTest(path=path):
                self.assertEqual(self.request('GET', path)[0], 404)

    def test_background_thumbnails_publish_without_blocking_requests(self):
        entered, release = threading.Event(), threading.Event()
        image = b'\xff\xd8generated-test-image\xff\xd9'
        def extract(source, destination, seconds):
            entered.set()
            if not release.wait(3):
                raise TimeoutError('Test did not release extraction')
            destination.write_bytes(image)
        self.worker = ThumbnailWorker(self.root, self.thumbnail_dir, self.scanner)
        self.server.RequestHandlerClass.thumbnail_worker = self.worker
        self.scanner.on_scan = self.worker.request_work
        headers = {'X-Device-ID': 'test-device-123'}
        with patch.object(self.worker, '_extract', side_effect=extract):
            self.worker.start()
            self.assertEqual(self.request('POST', '/api/thumbnails', headers, '{}')[0], 202)
            self.assertTrue(entered.wait(2))
            try:
                self.assertTrue(json.loads(self.request('GET', '/api/thumbnails')[2])['running'])
                self.assertEqual(self.request('GET', '/api/health')[0], 200)
                before = json.loads(self.request('GET', '/api/videos')[2])
                self.assertTrue(all(video['thumbnailUrl'] is None for video in before))
                self.assertEqual(self.request('GET', '/media/' + before[0]['id'], {'Range': 'bytes=2-4'})[2], b'234')
            finally:
                release.set()
            with self.worker.condition:
                self.assertTrue(self.worker.condition.wait_for(
                    lambda: not self.worker.running and not self.worker.requested, timeout=3))
        after = json.loads(self.request('GET', '/api/videos')[2])
        for video in after:
            self.assertNotIn('thumbnailGenerated', video)
            url = urlsplit(video['thumbnailUrl'])
            self.assertTrue(url.path.startswith('/generated-thumbnails/'))
            status, response_headers, body = self.request('GET', url.path + '?' + url.query)
            self.assertEqual((status, body), (200, image))
            self.assertEqual(response_headers['Content-Type'], 'image/jpeg')
            self.assertEqual(self.request('HEAD', url.path)[2], b'')
        self.assertEqual(json.loads(self.request('GET', '/api/thumbnails')[2])['generatedCount'], 2)

    def test_generated_thumbnail_routes_are_confined_and_disabled_worker_is_explicit(self):
        headers = {'X-Device-ID': 'test-device-123'}
        self.assertEqual(json.loads(self.request('GET', '/api/thumbnails')[2]), {'enabled': False})
        self.assertEqual(self.request('POST', '/api/thumbnails', headers, '{}')[0], 503)
        self.assertEqual(self.request('POST', '/api/thumbnails', body='{}')[0], 400)
        self.thumbnail_dir.mkdir()
        (self.thumbnail_dir / 'secret.jpg').write_bytes(b'private file')
        for path in ('/generated-thumbnails/secret.jpg', '/generated-thumbnails/%2e%2e/catalog.json',
                     '/generated-thumbnails/' + 'a' * 64 + '.jpg', '/generated-thumbnails/foo.png'):
            self.assertEqual(self.request('GET', path)[0], 404)

    def test_watch_and_recommendation_api(self):
        headers = {'X-Device-ID': 'test-device-123', 'Content-Type': 'application/json'}
        videos = list_videos(self.root, self.catalog)
        event = dict(videoId=videos[0]['id'], sessionId='playback-session-123', sequence=1,
                     watchedSeconds=12, position=12, duration=60, updatedAt=1800000000000)
        status, _, body = self.request('POST', '/api/watch', headers, json.dumps(event))
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)['countedView'])
        self.assertEqual(self.request('POST', '/api/watch', headers, json.dumps(event))[0], 200)
        _, _, body = self.request('GET', '/api/watch/history', headers)
        self.assertEqual(json.loads(body)['history'][videos[0]['id']]['views'], 1)
        _, _, body = self.request('GET', '/api/recommendations?limit=1', headers)
        first = json.loads(body)
        self.assertEqual(len(first['items']), 1)
        self.assertIn('streamUrl', first['items'][0])
        _, _, body = self.request('GET', f"/api/recommendations?limit=1&offset=1&session={first['sessionId']}", headers)
        second = json.loads(body)
        self.assertNotEqual(first['items'][0]['id'], second['items'][0]['id'])
        self.assertIsNone(second['nextOffset'])
        _, _, body = self.request('GET', '/api/recommendations?exclude=' + quote(videos[0]['id']), headers)
        self.assertEqual([v['id'] for v in json.loads(body)['items']], [videos[1]['id']])
        other = {'X-Device-ID': 'other-device-123'}
        self.assertEqual(self.request('GET', f"/api/recommendations?session={first['sessionId']}", other)[0], 410)
        self.assertEqual(json.loads(self.request('GET', '/api/watch/history', other)[2])['history'], {})

    def test_api_rejects_bad_events_and_pagination(self):
        headers = {'X-Device-ID': 'test-device-123'}
        self.assertEqual(self.request('GET', '/api/recommendations')[0], 400)
        for query in ('limit=0', 'limit=1000', 'offset=-1', 'offset=2', 'limit=nope'):
            self.assertEqual(self.request('GET', '/api/recommendations?' + query, headers)[0], 400)
        for body in ('[]', '{bad', '{}'):
            self.assertIn(self.request('POST', '/api/watch', headers, body)[0], (400, 404))
        event = dict(videoId=list_videos(self.root, self.catalog)[0]['id'], sessionId='playback-123', sequence=1,
                     watchedSeconds=12, position=12, duration=60, updatedAt=1800000000000)
        for key, value in [('position', -1), ('watchedSeconds', float('nan')), ('sequence', True), ('duration', None)]:
            self.assertEqual(self.request('POST', '/api/watch', headers, json.dumps({**event, key: value}))[0], 400)

    def test_cached_requests_and_manual_scan_api(self):
        headers = {'X-Device-ID': 'test-device-123'}
        (self.root / 'New arrival.mp4').write_bytes(b'new-video')
        with patch('backend.server.list_videos', side_effect=AssertionError('Unexpected folder scan')):
            self.assertEqual(len(json.loads(self.request('GET', '/api/videos')[2])), 2)
            self.assertEqual(json.loads(self.request('GET', '/api/health')[2])['videos'], 2)
            self.assertEqual(self.request('GET', '/api/recommendations', headers)[0], 200)
            self.assertEqual(self.request('HEAD', '/media/' + self.scanner.videos()[0]['id'])[0], 200)
        status, _, body = self.request('POST', '/api/scan/settings', headers, json.dumps({'intervalMinutes': 0}))
        self.assertEqual(status, 200)
        self.assertIsNone(json.loads(body)['nextScanAt'])
        self.assertEqual(self.request('POST', '/api/scan', headers, '{}')[0], 202)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline and self.scanner.status()['scanning']:
            time.sleep(.01)
        self.assertEqual(json.loads(self.request('GET', '/api/scan')[2])['videoCount'], 3)
        self.assertEqual(len(json.loads(self.request('GET', '/api/videos')[2])), 3)
        for value in (-1, 10081, True, '15', None, 1.5):
            self.assertEqual(self.request('POST', '/api/scan/settings', headers, json.dumps({'intervalMinutes': value}))[0], 400)

    def test_cached_deleted_video_returns_not_found(self):
        video = self.scanner.videos()[0]
        (self.root / video['file']).unlink()
        self.assertEqual(self.request('GET', '/media/' + video['id'])[0], 404)


if __name__ == '__main__':
    unittest.main()
