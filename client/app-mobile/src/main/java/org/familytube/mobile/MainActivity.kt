package org.familytube.mobile

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.familytube.core.data.*
import org.familytube.core.designsystem.FamilyColors
import org.familytube.core.model.Video
import org.familytube.core.playback.*

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: ServerSettingsRepository
    @Inject lateinit var healthChecker: ServerHealthChecker
    private val catalog: CatalogViewModel by viewModels()
    private val playback: PlaybackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = FamilyColors.accent, onPrimary = FamilyColors.text,
                background = FamilyColors.background, surface = FamilyColors.surface,
                onBackground = FamilyColors.text, onSurface = FamilyColors.text,
            )) {
                val catalogState by catalog.state.collectAsState()
                val playbackVideo by remember { playback.state.map { it.video }.distinctUntilChanged() }.collectAsState(initial = null)
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var watchId by rememberSaveable { mutableStateOf<String?>(null) }
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                var watchLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
                DisposableEffect(fullscreen) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
                    else controller.show(WindowInsetsCompat.Type.systemBars())
                    onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
                }
                LaunchedEffect(catalogState.libraryId) {
                    if (watchLibraryId != null && catalogState.libraryId.isNotBlank() && watchLibraryId != catalogState.libraryId) {
                        playback.stop(); watchId = null; watchLibraryId = null; fullscreen = false
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                LaunchedEffect(catalogState.settingsLoaded, catalogState.serverUrl) {
                    if (catalogState.settingsLoaded && catalogState.serverUrl.isBlank()) showSettings = true
                }
                val video = catalogState.videos.firstOrNull { it.id == watchId }
                    ?: playbackVideo?.takeIf { it.id == watchId && it.libraryId == watchLibraryId }
                fun leaveWatch() {
                    if (fullscreen) {
                        fullscreen = false
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else {
                        playback.stop()
                        watchId = null
                    }
                }
                BackHandler(enabled = watchId != null || showSettings) {
                    if (watchId != null) leaveWatch() else showSettings = false
                }
                when {
                    !catalogState.settingsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Opening FamilyTube…", color = FamilyColors.text)
                    }
                    watchId != null -> WatchScreen(video, playback, fullscreen, ::leaveWatch) {
                        fullscreen = !fullscreen
                        requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    showSettings || catalogState.serverUrl.isBlank() -> ServerSetup(settings, healthChecker) {
                        showSettings = false
                    }
                    else -> CatalogScreen(catalogState, catalog::refresh, { showSettings = true }, catalog::setQuery, catalog::setCategory) {
                        watchId = it.id
                        watchLibraryId = it.libraryId
                        playback.play(it)
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) playback.onBackground()
        super.onStop()
    }

    override fun onStart() { super.onStart(); catalog.onForeground() }
}

@Composable
private fun CatalogScreen(state: CatalogState, onRefresh: () -> Unit, onSettings: () -> Unit,
    onQuery: (String) -> Unit, onCategory: (String) -> Unit, onSelect: (Video) -> Unit) {
    Column(Modifier.fillMaxSize().background(FamilyColors.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("FamilyTube", style = MaterialTheme.typography.headlineMedium, color = FamilyColors.text)
            OutlinedButton(onClick = onSettings) { Text("Server") }
        }
        Text("${state.videos.size} videos", modifier = Modifier.padding(horizontal = 16.dp), color = FamilyColors.muted)
        OutlinedTextField(value = state.query, onValueChange = onQuery, label = { Text("Search titles") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            item { OutlinedButton(onClick = { onCategory("") }) { Text(if (state.category.isBlank()) "All ✓" else "All") } }
            items(state.categories) { category ->
                OutlinedButton(onClick = { onCategory(category) }) { Text(if (category == state.category) "$category ✓" else category) }
            }
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Refresh") }
        if (state.loading) Text("Loading library…", Modifier.padding(16.dp), color = FamilyColors.text)
        if (state.error != null) {
            Text("Server unavailable. Saved library remains usable. ${state.error}", Modifier.padding(16.dp), color = FamilyColors.text)
            Button(onClick = onRefresh, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Retry") }
        }
        if (!state.loading && state.videos.isEmpty()) {
            Text(if (state.query.isNotBlank() || state.category.isNotBlank()) "No matching videos."
                else if (state.error == null) "No videos on this server yet." else "No saved library yet.",
                Modifier.padding(16.dp), color = FamilyColors.text)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.videos, key = { it.id }) { video ->
                Column(Modifier.fillMaxWidth().clickable { onSelect(video) }.padding(16.dp)) {
                    Text(video.title, style = MaterialTheme.typography.titleMedium, color = FamilyColors.text)
                    Text(video.category.ifBlank { "Video" }, color = FamilyColors.muted)
                    state.progress[video.id]?.let { saved ->
                        val resumeMs = org.familytube.core.model.resumePositionMs(saved.positionMs, saved.durationMs)
                        if (resumeMs > 0) Text("Resume at ${formatTime(resumeMs)}", color = FamilyColors.muted)
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun WatchScreen(video: Video?, playback: PlaybackViewModel, fullscreen: Boolean,
                        onBack: () -> Unit, onFullscreen: () -> Unit) {
    val state by playback.state.collectAsState()
    val player by playback.player.collectAsState()
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val duration = state.durationMs.coerceAtLeast(0)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                .align(if (fullscreen) Alignment.Center else Alignment.TopCenter),
            contentAlignment = Alignment.Center,
        ) {
            if (player != null) ContentFrame(player = player!!, modifier = Modifier.fillMaxSize())
            else Text("Paused", color = Color.White)
        }
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text(video?.title ?: "Video unavailable", maxLines = 1, color = FamilyColors.text)
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .background(Color.Black.copy(alpha = if (fullscreen) 0.65f else 1f)).padding(12.dp)) {
            val shown = scrubMs ?: state.positionMs
            Text("${formatTime(shown)} / ${formatTime(duration)} · ${state.phase}", color = FamilyColors.muted)
            Slider(value = shown.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
                onValueChange = { scrubMs = it.toLong() },
                onValueChangeFinished = { playback.seekTo(scrubMs ?: state.positionMs); scrubMs = null },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0)
            if (state.phase == PlaybackPhase.FAILED) Text(state.error ?: "Playback failed", color = Color.Red)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING) playback.pause()
                    else if (state.video == null && video != null) playback.play(video)
                    else playback.resume()
                }, enabled = video != null) {
                    Text(if (state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING) "Pause" else "Play")
                }
                OutlinedButton(onClick = { playback.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) },
                    enabled = duration > 0) { Text("−10s") }
                OutlinedButton(onClick = { playback.seekTo((state.positionMs + 10_000).coerceAtMost(duration)) },
                    enabled = duration > 0) { Text("+10s") }
                OutlinedButton(onClick = onFullscreen) { Text(if (fullscreen) "Inline" else "Fullscreen") }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun ServerSetup(settings: ServerSettingsRepository, healthChecker: ServerHealthChecker, onDone: () -> Unit) {
    val savedUrl by settings.serverUrl.collectAsState(initial = "")
    var draft by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Enter your home server address to connect.") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(savedUrl) { if (!edited) draft = savedUrl }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("FamilyTube", style = MaterialTheme.typography.headlineLarge, color = FamilyColors.text)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(draft, onValueChange = { draft = it; edited = true },
            label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8000") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            scope.launch {
                busy = true
                try {
                    val url = settings.saveServerUrl(draft)
                    draft = url
                    edited = false
                    status = healthChecker.check(url).message()
                } catch (error: IllegalArgumentException) {
                    status = error.message ?: "Invalid address"
                } finally { busy = false }
            }
        }, enabled = !busy && draft.isNotBlank()) { Text(if (busy) "Checking…" else "Save and connect") }
        Spacer(Modifier.height(12.dp))
        Text(status, color = FamilyColors.muted)
        if (savedUrl.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDone) { Text("Open library") }
        }
    }
}

private fun HealthResult.message(): String = if (reachable) {
    "Connected to FamilyTube server · ${videoCount ?: 0} videos"
} else {
    "Could not connect: ${detail ?: "Unknown error"}. Check the address and local Wi-Fi."
}
