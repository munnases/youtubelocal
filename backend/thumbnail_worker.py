"""Single background FFmpeg worker; original media and artwork are read-only inputs."""
import hashlib
import json
import os
import subprocess
import tempfile
import threading
import time
from pathlib import Path


THUMBNAIL_TYPES = {'.webp': 'image/webp', '.jpg': 'image/jpeg',
                   '.jpeg': 'image/jpeg', '.png': 'image/png'}


def source_key(media_dir, path):
    root = media_dir.resolve()
    resolved = path.resolve()
    if not resolved.is_relative_to(root) or not resolved.is_file():
        raise OSError('Video is outside the media folder or unavailable.')
    stat = resolved.stat()
    identity = ['thumbnail-v1', str(root), path.relative_to(media_dir).as_posix(),
                stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns]
    return hashlib.sha256(json.dumps(identity).encode('utf-8')).hexdigest()


def find_thumbnail(media_dir, path, thumbnail_dir=None):
    """Return private catalog fields, preferring supplied sidecar artwork."""
    root = media_dir.resolve()
    for suffix in THUMBNAIL_TYPES:
        candidate = path.with_suffix(suffix)
        if candidate.is_file() and candidate.resolve().is_relative_to(root):
            return dict(thumbnailFile=candidate.relative_to(media_dir).as_posix(),
                        thumbnailVersion=str(candidate.stat().st_mtime_ns),
                        thumbnailGenerated=False)
    if thumbnail_dir is not None:
        candidate = thumbnail_dir / (source_key(media_dir, path) + '.jpg')
        if (candidate.is_file() and candidate.stat().st_size > 0
                and candidate.resolve().is_relative_to(thumbnail_dir.resolve())):
            return dict(thumbnailFile=candidate.name,
                        thumbnailVersion=str(candidate.stat().st_mtime_ns),
                        thumbnailGenerated=True)
    return dict(thumbnailFile=None, thumbnailVersion=None, thumbnailGenerated=False)


class ThumbnailWorker:
    def __init__(self, media_dir, thumbnail_dir, scanner, ffmpeg='ffmpeg', timeout=60):
        self.media_dir = Path(media_dir).resolve()
        self.thumbnail_dir = Path(thumbnail_dir).resolve()
        self.scanner = scanner
        self.ffmpeg = str(ffmpeg)
        self.timeout = timeout
        self.condition = threading.Condition()
        self.stop_event = threading.Event()
        self.thread = None
        self.requested = False
        self.running = False
        self.generated = 0
        self.failed = 0
        self.last_error = None
        self.last_run_at = None
        self.current_video_id = None

    def status(self):
        with self.condition:
            return dict(enabled=True, running=self.running, requested=self.requested,
                        generatedCount=self.generated, failedCount=self.failed,
                        currentVideoId=self.current_video_id, lastError=self.last_error,
                        lastRunAt=self.last_run_at)

    def request_work(self):
        with self.condition:
            if not self.stop_event.is_set():
                self.requested = True
                self.condition.notify_all()
        return self.status()

    def start(self):
        with self.condition:
            if self.thread is None and not self.stop_event.is_set():
                self.thread = threading.Thread(target=self._run, name='thumbnail-worker', daemon=True)
                self.thread.start()

    def _run(self):
        while True:
            with self.condition:
                self.condition.wait_for(lambda: self.requested or self.stop_event.is_set())
                if self.stop_event.is_set():
                    return
                self.requested = False
                self.running = True
                self.last_error = None
            try:
                for video in self.scanner.videos():
                    if self.stop_event.is_set():
                        break
                    with self.condition:
                        self.current_video_id = video['id']
                    try:
                        created = self._prepare(video)
                        with self.condition:
                            self.generated += int(created)
                    except Exception as error:
                        if self.stop_event.is_set():
                            break
                        with self.condition:
                            self.failed += 1
                            self.last_error = f"{video['file']}: {str(error) or type(error).__name__}"[:1600]
                        # A missing executable affects every item. Retry on the next scan/request.
                        if isinstance(error, FileNotFoundError) and error.filename == self.ffmpeg:
                            break
            finally:
                with self.condition:
                    self.running = False
                    self.current_video_id = None
                    self.last_run_at = int(time.time() * 1000)
                    self.condition.notify_all()

    def _prepare(self, video):
        source = self.media_dir / video['file']
        key = source_key(self.media_dir, source)
        artwork = find_thumbnail(self.media_dir, source, self.thumbnail_dir)
        created = False
        if artwork['thumbnailFile'] is None:
            self.thumbnail_dir.mkdir(parents=True, exist_ok=True)
            destination = self.thumbnail_dir / (key + '.jpg')
            descriptor, name = tempfile.mkstemp(prefix='.thumbnail-', suffix='.jpg', dir=self.thumbnail_dir)
            os.close(descriptor)
            temporary = Path(name)
            try:
                # One second avoids many opening black frames; zero supports very short clips.
                for seconds in (1, 0):
                    self._extract(source.resolve(), temporary, seconds)
                    if temporary.stat().st_size:
                        break
                image = temporary.read_bytes()
                if not image.startswith(b'\xff\xd8') or not image.endswith(b'\xff\xd9'):
                    raise RuntimeError('FFmpeg did not produce a complete JPEG frame.')
                if self.stop_event.is_set() or source_key(self.media_dir, source) != key:
                    return False  # Removed/replaced input must never publish a stale poster.
                os.replace(temporary, destination)
                created = True
            finally:
                temporary.unlink(missing_ok=True)
            artwork = find_thumbnail(self.media_dir, source, self.thumbnail_dir)
        if source_key(self.media_dir, source) == key and not self.stop_event.is_set():
            self.scanner.publish_thumbnail(video['id'], video['file'], artwork)
        return created

    def _extract(self, source, destination, seconds):
        destination.write_bytes(b'')
        command = [self.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
                   '-threads', '1', '-ss', str(seconds), '-i', str(source),
                   '-map', '0:v:0', '-frames:v', '1', '-an', '-sn',
                   '-vf', 'scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1',
                   '-threads', '1', '-filter_threads', '1', '-pix_fmt', 'yuvj420p', '-q:v', '3',
                   '-f', 'image2', '-update', '1', str(destination)]
        try:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        except FileNotFoundError as error:
            raise FileNotFoundError(error.errno, 'FFmpeg executable not found', self.ffmpeg) from error
        deadline = time.monotonic() + self.timeout
        try:
            while True:
                if self.stop_event.is_set():
                    raise RuntimeError('Thumbnail worker stopped.')
                if time.monotonic() >= deadline:
                    raise TimeoutError(f'FFmpeg exceeded {self.timeout} seconds.')
                try:
                    _, stderr = process.communicate(timeout=.2)
                    break
                except subprocess.TimeoutExpired:
                    continue
            if process.returncode:
                raise RuntimeError(stderr.decode('utf-8', errors='replace').strip()[-1200:]
                                   or f'FFmpeg exited with code {process.returncode}.')
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate()

    def close(self):
        with self.condition:
            self.stop_event.set()
            self.requested = False
            self.condition.notify_all()
        if self.thread is not None:
            self.thread.join(timeout=5)
