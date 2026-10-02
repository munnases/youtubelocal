package org.familytube.tv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.familytube.core.designsystem.FamilyColors
import org.familytube.core.model.Video
import org.familytube.core.playback.*

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun TvWatchScreen(video: Video?, playback: PlaybackViewModel,
    related: suspend (Video) -> List<Video>, onLeave: () -> Unit, onSelect: (Video) -> Unit) {
    val state by playback.state.collectAsState()
    val player by playback.player.collectAsState()
    var candidates by remember(video?.id) { mutableStateOf<List<Video>>(emptyList()) }
    LaunchedEffect(video?.libraryId, video?.id) {
        candidates = if (video == null) emptyList() else try { related(video) }
            catch (error: Exception) { if (error is CancellationException) throw error; emptyList() }
    }
    TvWatchControls(video, state, candidates, playback::pause,
        { if (state.video == null && video != null) playback.play(video) else playback.resume() },
        playback::seekTo, onLeave, onSelect) {
        if (player != null) ContentFrame(player = player!!, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Press Play to resume", color = Color.White) }
    }
}

/** Remote input state is independent from the retained player and progress ticks. */
@Composable
internal fun TvWatchControls(video: Video?, state: PlaybackState, related: List<Video>, onPause: () -> Unit,
    onPlay: () -> Unit, onSeek: (Long) -> Unit, onLeave: () -> Unit, onSelect: (Video) -> Unit,
    surface: @Composable () -> Unit = {}) {
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    val relatedFocus = remember { FocusRequester() }
    val firstRelated = remember { FocusRequester() }
    var visible by remember(video?.id) { mutableStateOf(true) }
    var preview by remember(video?.id) { mutableStateOf<Long?>(null) }
    var showingRelated by remember(video?.id) { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    var revealKey by remember { mutableStateOf<Key?>(null) }
    val playing = state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING
    val canSeek = state.durationMs > 0
    LaunchedEffect(video?.id) { withFrameNanos { }; if (video != null) playFocus.requestFocus() else relatedFocus.requestFocus() }
    LaunchedEffect(state.phase) {
        if (state.phase in listOf(PlaybackPhase.PAUSED, PlaybackPhase.FAILED, PlaybackPhase.ENDED)) visible = true
    }
    LaunchedEffect(visible, showingRelated) {
        withFrameNanos { }
        if (!visible) rootFocus.requestFocus()
        else if (showingRelated && related.isNotEmpty()) firstRelated.requestFocus()
        else if (video != null) playFocus.requestFocus() else relatedFocus.requestFocus()
    }
    LaunchedEffect(visible, showingRelated, preview, playing, activity) {
        if (visible && playing && preview == null && !showingRelated) { delay(3_000); visible = false }
    }
    BackHandler {
        when {
            preview != null -> { preview = null; activity++; timelineFocus.requestFocus() }
            showingRelated -> { showingRelated = false; activity++ }
            visible -> visible = false
            else -> onLeave()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        val media = code in listOf(AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_STOP)
        if (media) {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                when (code) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> onPlay()
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> onPause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (playing) onPause() else onPlay()
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (canSeek) onSeek((state.positionMs + 10_000).coerceAtMost(state.durationMs))
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> if (canSeek) onSeek((state.positionMs - 10_000).coerceAtLeast(0))
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> onLeave()
                }
                activity++
            }
            true
        } else if (event.key == revealKey && event.type == KeyEventType.KeyUp) {
            revealKey = null; true
        } else if (event.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter)) {
            if (event.type == KeyEventType.KeyDown) {
                activity++
                if (!visible) { visible = true; revealKey = event.key; true } else false
            } else false
        } else false
    }.focusProperties { canFocus = !visible }.focusable().testTag("watch-root")) {
        surface()
        if (visible) {
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .9f))
                .padding(horizontal = 36.dp, vertical = 22.dp).testTag("watch-overlay")) {
                Text(video?.title ?: "Video unavailable", fontSize = 26.sp, color = FamilyColors.text, maxLines = 1)
                Text(if (state.phase == PlaybackPhase.FAILED) state.error ?: "Playback failed. Choose Retry."
                    else "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)} · ${state.phase}",
                    color = if (state.phase == PlaybackPhase.FAILED) FamilyColors.accent else FamilyColors.muted)
                if (canSeek) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        if (preview != null) { onSeek(preview!!); preview = null }
                        else preview = state.positionMs.coerceIn(0, state.durationMs)
                        activity++
                    }, modifier = Modifier.fillMaxWidth().focusRequester(timelineFocus).focusProperties { down = playFocus }
                        .onPreviewKeyEvent {
                            if (it.key == Key.DirectionLeft || it.key == Key.DirectionRight) {
                                if (it.type == KeyEventType.KeyDown) {
                                    preview = ((preview ?: state.positionMs) + if (it.key == Key.DirectionRight) 10_000 else -10_000).coerceIn(0, state.durationMs)
                                    activity++
                                }
                                true
                            } else false
                        }.semantics { contentDescription = "Seek timeline" }.testTag("timeline"),
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp))),
                        colors = ButtonDefaults.colors(containerColor = FamilyColors.surface, focusedContainerColor = FamilyColors.surface, focusedContentColor = FamilyColors.text)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if (preview == null) "Seek · Left / Right to preview" else "Preview ${formatTime(preview!!)} · OK to seek · Back to cancel", fontSize = 16.sp)
                            Box(Modifier.fillMaxWidth().height(5.dp).background(FamilyColors.muted)) {
                                Box(Modifier.fillMaxWidth(((preview ?: state.positionMs).toFloat() / state.durationMs).coerceIn(0f, 1f)).fillMaxHeight().background(FamilyColors.accent))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { if (playing) onPause() else onPlay(); activity++ }, enabled = video != null,
                        modifier = Modifier.focusRequester(playFocus).focusProperties { if (canSeek) up = timelineFocus; left = FocusRequester.Cancel }.testTag("transport")) {
                        Text(when { playing -> "Pause"; state.phase == PlaybackPhase.FAILED -> "Retry"; state.phase == PlaybackPhase.ENDED -> "Replay"; else -> "Play" })
                    }
                    Button(onClick = { if (canSeek) onSeek((state.positionMs - 10_000).coerceAtLeast(0)); activity++ }, enabled = canSeek) { Text("−10 sec") }
                    Button(onClick = { if (canSeek) onSeek((state.positionMs + 10_000).coerceAtMost(state.durationMs)); activity++ }, enabled = canSeek) { Text("+10 sec") }
                    Button(onClick = { preview = null; showingRelated = !showingRelated; activity++ },
                        modifier = Modifier.focusRequester(relatedFocus).testTag("related-button")) { Text("Related") }
                    Button(onClick = onLeave) { Text("Leave video") }
                }
                if (showingRelated) {
                    Text("Related family videos", fontSize = 20.sp, modifier = Modifier.padding(top = 18.dp))
                    if (related.isEmpty()) Text("No related videos in this library.", color = FamilyColors.muted)
                    else LazyRow(contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(related.size, key = { related[it].id }) { index ->
                            TvVideoCard(related[index], modifier = (if (index == 0) Modifier.focusRequester(firstRelated) else Modifier)
                                .focusProperties { up = relatedFocus; down = FocusRequester.Cancel
                                    if (index == 0) left = FocusRequester.Cancel
                                    if (index == related.lastIndex) right = FocusRequester.Cancel
                                }.testTag("related:${related[index].id}"), onSelect = onSelect)
                        }
                    }
                }
            }
        }
    }
}
