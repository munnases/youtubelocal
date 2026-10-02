package org.familytube.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.familytube.core.designsystem.FamilyColors
import org.familytube.core.model.Video
import org.familytube.core.playback.*

@Composable
internal fun PhoneWatch(video: Video?, related: List<Video>, playback: PlaybackViewModel, fullscreen: Boolean,
    onBack: () -> Unit, onFullscreen: () -> Unit, onSelect: (Video) -> Unit) {
    val player by playback.player.collectAsState()
    Column(Modifier.fillMaxSize().background(FamilyColors.background).then(if (fullscreen) Modifier else Modifier.safeDrawingPadding())) {
        // Keep the surface in the same composition slot in both presentations.
        Box(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            VideoSurface(player)
            PosterUntilFrame(video, playback, player == null)
            WatchControlsHost(video, playback, fullscreen, onBack, onFullscreen)
        }
        if (!fullscreen) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "title") {
                Column {
                    Text(video?.title ?: "Video unavailable", style = MaterialTheme.typography.headlineSmall)
                    Text(video?.category?.ifBlank { "Family video" } ?: "Choose another item from your library.",
                        color = FamilyColors.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item(key = "related-title") { Text("More from your library", style = MaterialTheme.typography.titleMedium) }
            if (related.isEmpty()) item(key = "related-empty") { Text("No other videos in this library yet.", color = FamilyColors.muted) }
            items(related, key = { it.id }) { next -> VideoCard(next, null, { onSelect(next) }, Modifier.fillMaxWidth()) }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun VideoSurface(player: Player?) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) ContentFrame(player = player, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PosterUntilFrame(video: Video?, playback: PlaybackViewModel, detached: Boolean) {
    val firstFrame by remember(playback) { playback.state.map { it.firstFrameMs }.distinctUntilChanged() }.collectAsState(null)
    if ((firstFrame == null || detached) && video != null) Thumbnail(video, Modifier.fillMaxSize(), badge = false)
}

@Composable
private fun WatchControlsHost(video: Video?, playback: PlaybackViewModel, fullscreen: Boolean, onBack: () -> Unit, onFullscreen: () -> Unit) {
    // Position ticks update controls independently from the surface and related list.
    val state by playback.state.collectAsState()
    PhoneControls(state, video, fullscreen, onBack, onFullscreen,
        onPlayPause = {
            if (state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING || state.phase == PlaybackPhase.PREPARING) playback.pause()
            else if (state.video == null && video != null) playback.play(video)
            else playback.resume()
        }, onSeek = playback::seekTo)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneControls(state: PlaybackState, video: Video?, fullscreen: Boolean,
    onBack: () -> Unit, onFullscreen: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit) {
    var visible by remember(video?.id) { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubMs by remember(video?.id) { mutableStateOf<Long?>(null) }
    var seekFeedback by remember(video?.id) { mutableStateOf<String?>(null) }
    val latestState by rememberUpdatedState(state)
    val latestSeek by rememberUpdatedState(onSeek)
    val duration = state.durationMs.coerceAtLeast(0)
    val playing = state.phase == PlaybackPhase.PLAYING
    val showControls = visible || !playing || scrubMs != null
    fun interact() { visible = true; interaction++ }
    fun relativeSeek(forward: Boolean) {
        val latest = latestState
        if (latest.durationMs <= 0) return
        latestSeek((latest.positionMs + if (forward) 10_000 else -10_000).coerceIn(0, latest.durationMs))
        seekFeedback = if (forward) "+10 seconds" else "−10 seconds"
        interact()
    }
    LaunchedEffect(playing, interaction, scrubMs != null, visible) {
        if (playing && scrubMs == null && visible) { delay(3_000); visible = false }
    }
    LaunchedEffect(state.phase) { if (!playing) visible = true }
    LaunchedEffect(seekFeedback, interaction) { if (seekFeedback != null) { delay(900); seekFeedback = null } }
    Box(Modifier.fillMaxSize().testTag("player-controls").pointerInput(video?.id) {
        detectTapGestures(onTap = { visible = !visible; interaction++ }, onDoubleTap = { relativeSeek(it.x >= size.width / 2f) })
    }.semantics {
        contentDescription = "Video player"
        stateDescription = when (state.phase) {
            PlaybackPhase.PLAYING -> "Playing"
            PlaybackPhase.FAILED -> "Playback failed"
            PlaybackPhase.BUFFERING, PlaybackPhase.PREPARING -> "Loading"
            PlaybackPhase.ENDED -> "Ended"
            else -> "Paused"
        }
        onClick(label = "Show playback controls") { interact(); true }
        customActions = listOf(CustomAccessibilityAction("Rewind 10 seconds") { relativeSeek(false); true },
            CustomAccessibilityAction("Forward 10 seconds") { relativeSeek(true); true })
    }) {
        if (state.phase == PlaybackPhase.PREPARING || state.phase == PlaybackPhase.BUFFERING) {
            CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.TopEnd).padding(2.dp).semantics { contentDescription = "Loading video" }, color = Color.White)
        }
        if (showControls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent, Color.Black.copy(alpha = .9f)))))
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).then(if (fullscreen) Modifier.systemBarsPadding() else Modifier), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(if (fullscreen) "Exit fullscreen" else "Back to library", Glyph.BACK, onBack)
                if (fullscreen) Text(video?.title ?: "Video", maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.align(Alignment.Center).offset(y = (-10).dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ActionIcon("Rewind 10 seconds", Glyph.REWIND, { relativeSeek(false) }, enabled = duration > 0)
                val label = when (state.phase) {
                    PlaybackPhase.FAILED -> "Retry playback"
                    PlaybackPhase.ENDED -> "Replay"
                    PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING, PlaybackPhase.PREPARING -> "Pause"
                    else -> "Play"
                }
                ActionIcon(label, if (label == "Pause") Glyph.PAUSE else Glyph.PLAY, { interact(); onPlayPause() },
                    Modifier.background(Color.Black.copy(alpha = .45f), CircleShape), enabled = video != null)
                ActionIcon("Forward 10 seconds", Glyph.FORWARD, { relativeSeek(true) }, enabled = duration > 0)
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 12.dp)
                .then(if (fullscreen) Modifier.navigationBarsPadding() else Modifier)) {
                if (state.phase == PlaybackPhase.FAILED) Text("Playback failed. Check your server and retry.",
                    color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("playback-error"))
                val shown = scrubMs ?: state.positionMs
                if (state.phase != PlaybackPhase.FAILED) Slider(value = shown.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
                    onValueChange = { scrubMs = it.toLong(); interact() },
                    onValueChangeFinished = { scrubMs?.let(onSeek); scrubMs = null; interact() },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0,
                    colors = SliderDefaults.colors(activeTrackColor = FamilyColors.accent, inactiveTrackColor = Color.White.copy(alpha = .3f)),
                    thumb = { Box(Modifier.size(14.dp).background(FamilyColors.accent, CircleShape)) },
                    track = { slider ->
                        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .3f))) {
                            Box(Modifier.fillMaxWidth((slider.value / duration.coerceAtLeast(1)).coerceIn(0f, 1f))
                                .height(3.dp).background(FamilyColors.accent))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("timeline").semantics {
                        contentDescription = "Playback position"
                        stateDescription = "${formatTime(shown)} of ${formatTime(duration)}"
                    })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatTime(shown)} / ${if (duration > 0) formatTime(duration) else "--:--"}",
                        modifier = Modifier.weight(1f).testTag("position"), style = MaterialTheme.typography.labelMedium, color = Color.White)
                    if (state.phase == PlaybackPhase.ENDED) Text("Finished", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    ActionIcon(if (fullscreen) "Exit fullscreen" else "Fullscreen", if (fullscreen) Glyph.COLLAPSE else Glyph.EXPAND,
                        { interact(); onFullscreen() })
                }
            }
        }
        if (seekFeedback != null) Text(seekFeedback!!, Modifier.align(Alignment.Center).offset(y = (-52).dp)
            .background(Color.Black.copy(alpha = .7f), CircleShape).padding(12.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
