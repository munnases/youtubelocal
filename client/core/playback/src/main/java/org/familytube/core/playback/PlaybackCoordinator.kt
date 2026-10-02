package org.familytube.core.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.familytube.core.model.Video
import org.familytube.core.model.WatchSnapshot
import java.util.UUID
import kotlinx.coroutines.*

enum class PlaybackPhase { IDLE, PREPARING, BUFFERING, PAUSED, PLAYING, ENDED, FAILED }

data class PlaybackState(
    val video: Video? = null,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val firstFrameMs: Long? = null,
    val error: String? = null,
)

/** Main-thread owner of the only player and MediaSession in an app session. */
class PlaybackCoordinator @Inject constructor(@ApplicationContext private val context: Context,
    private val persistence: PlaybackPersistence) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val mutablePlayer = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = mutablePlayer.asStateFlow()
    private var exoPlayer: ExoPlayer? = null
    private var session: MediaSession? = null
    private var selection = 0L
    private var startedAtMs = 0L
    private var watchSession: WatchSession? = null
    private var lastSavedMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selectionJob: Job? = null
    private var switching = false

    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val created = ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!switching) { updatePhase(); persistProgress() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!switching) {
                    watchSession?.sample(SystemClock.elapsedRealtime())
                    watchSession?.playing = isPlaying
                    updatePhase()
                    persistProgress()
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) {
                if (!switching && reason == Player.DISCONTINUITY_REASON_SEEK) { samplePosition(); persistProgress() }
            }
            override fun onPlayerError(error: PlaybackException) {
                mutableState.value = mutableState.value.copy(
                    phase = PlaybackPhase.FAILED,
                    error = error.localizedMessage ?: "This video could not be played",
                )
                Log.e("FamilyTubePlayback", "Playback failed for selected video", error)
                persistProgress()
            }
            override fun onRenderedFirstFrame() {
                val current = mutableState.value
                if (current.video != null && current.firstFrameMs == null && selection > 0) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                    mutableState.value = current.copy(firstFrameMs = elapsed)
                    Log.i("FamilyTubePlayback", "first_frame_ms=$elapsed video_id=${current.video.id}")
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) for (index in 0 until group.length) {
                    if (group.isTrackSelected(index)) {
                        val format = group.getTrackFormat(index)
                        Log.i("FamilyTubePlayback", "selected_track mime=${format.sampleMimeType} size=${format.width}x${format.height} codecs=${format.codecs}")
                    }
                }
            }
        })
        exoPlayer = created
        session = MediaSession.Builder(context, created).build()
        mutablePlayer.value = created
        return created
    }

    fun play(video: Video) {
        stop()
        val token = ++selection
        startedAtMs = SystemClock.elapsedRealtime()
        mutableState.value = PlaybackState(video = video, phase = PlaybackPhase.PREPARING)
        selectionJob = scope.launch {
            val position = persistence.position(video)
            if (token != selection) return@launch
            watchSession = WatchSession(UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
            lastSavedMs = SystemClock.elapsedRealtime()
            startPlayer(video, position)
        }
    }

    private fun startPlayer(video: Video, positionMs: Long) {
        val player = ensurePlayer()
        switching = true
        try {
            player.setMediaItem(MediaItem.Builder().setMediaId("${video.id}#$selection")
                .setUri(video.streamUrl).build(), positionMs)
            player.prepare()
            player.play()
        } finally { switching = false }
        watchSession?.playing = player.isPlaying
        updatePhase()
    }

    fun resume() {
        val video = mutableState.value.video ?: return
        if (exoPlayer == null) {
            if (selectionJob?.isActive == true) return
            if (watchSession == null) { play(video); return }
            startPlayer(video, mutableState.value.positionMs)
            return
        }
        exoPlayer?.let { player ->
            if (player.playerError != null) {
                mutableState.value = mutableState.value.copy(error = null, phase = PlaybackPhase.PREPARING)
                player.prepare()
            }
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
        samplePosition()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0))
        samplePosition()
    }

    fun samplePosition() {
        watchSession?.sample(SystemClock.elapsedRealtime())
        val player = exoPlayer ?: return
        mutableState.value = mutableState.value.copy(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
        )
        if (player.isPlaying && SystemClock.elapsedRealtime() - lastSavedMs >= 5_000) persistProgress()
    }

    private fun persistProgress() {
        val video = mutableState.value.video ?: return
        val currentSession = watchSession ?: return
        val now = SystemClock.elapsedRealtime()
        currentSession.sample(now)
        val player = exoPlayer
        val position = player?.currentPosition?.coerceAtLeast(0) ?: mutableState.value.positionMs
        val duration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: mutableState.value.durationMs
        persistence.save(WatchSnapshot(video.libraryId, video.id, currentSession.id,
            currentSession.nextSequence(), position, duration, currentSession.watchedMs, System.currentTimeMillis()))
        lastSavedMs = now
    }

    fun stop() {
        samplePosition()
        persistProgress()
        selectionJob?.cancel()
        selectionJob = null
        selection++
        watchSession = null
        switching = true
        try { exoPlayer?.stop(); exoPlayer?.clearMediaItems() } finally { switching = false }
        mutableState.value = PlaybackState()
    }

    /** A non-configuration background transition releases decoder/network resources. */
    fun releaseForBackground() {
        pause()
        persistProgress()
        selectionJob?.cancel()
        selectionJob = null
        selection++
        val previous = mutableState.value
        session?.release()
        session = null
        exoPlayer?.release()
        exoPlayer = null
        mutablePlayer.value = null
        if (previous.video != null) mutableState.value = previous.copy(phase = PlaybackPhase.PAUSED)
    }

    fun release() {
        samplePosition()
        persistProgress()
        scope.cancel()
        session?.release()
        session = null
        exoPlayer?.release()
        exoPlayer = null
        mutablePlayer.value = null
    }

    private fun updatePhase() {
        val player = exoPlayer ?: return
        if (mutableState.value.video == null) return
        val phase = when {
            player.playerError != null -> PlaybackPhase.FAILED
            player.playbackState == Player.STATE_ENDED -> PlaybackPhase.ENDED
            player.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            player.isPlaying -> PlaybackPhase.PLAYING
            player.playbackState == Player.STATE_READY -> PlaybackPhase.PAUSED
            else -> PlaybackPhase.PREPARING
        }
        mutableState.value = mutableState.value.copy(phase = phase)
        samplePosition()
    }
}
