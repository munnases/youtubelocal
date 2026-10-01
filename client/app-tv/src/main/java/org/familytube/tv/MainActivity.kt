package org.familytube.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
                var watchLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(catalogState.libraryId) {
                    if (watchLibraryId != null && catalogState.libraryId.isNotBlank() && watchLibraryId != catalogState.libraryId) {
                        playback.stop(); watchId = null; watchLibraryId = null
                    }
                }
                LaunchedEffect(catalogState.settingsLoaded, catalogState.serverUrl) {
                    if (catalogState.settingsLoaded && catalogState.serverUrl.isBlank()) showSettings = true
                }
                val video = catalogState.videos.firstOrNull { it.id == watchId }
                    ?: playbackVideo?.takeIf { it.id == watchId && it.libraryId == watchLibraryId }
                fun leaveWatch() { playback.stop(); watchId = null }
                BackHandler(enabled = watchId != null || showSettings) {
                    if (watchId != null) leaveWatch() else showSettings = false
                }
                when {
                    !catalogState.settingsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Opening FamilyTube…", color = FamilyColors.text)
                    }
                    watchId != null -> WatchScreen(video, playback, ::leaveWatch)
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
    val serverFocus = remember { FocusRequester() }
    val allFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(FamilyColors.background).padding(horizontal = 72.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("FamilyTube", style = MaterialTheme.typography.headlineLarge, color = FamilyColors.text)
            Button(onClick = onSettings, modifier = Modifier.focusRequester(serverFocus)) { Text("Server") }
        }
        Text("${state.videos.size} videos", color = FamilyColors.muted)
        Text("Search titles", color = FamilyColors.text)
        var searchFocused by remember { mutableStateOf(false) }
        BasicTextField(value = state.query, onValueChange = onQuery, singleLine = true,
            textStyle = TextStyle(color = FamilyColors.text, fontSize = 20.sp), cursorBrush = SolidColor(FamilyColors.accent),
            modifier = Modifier.fillMaxWidth().onPreInterceptKeyBeforeSoftKeyboard {
                if (it.key in listOf(Key.DirectionDown, Key.DirectionUp)) {
                    if (it.type == KeyEventType.KeyDown) {
                        if (it.key == Key.DirectionDown) allFocus.requestFocus() else serverFocus.requestFocus()
                    }
                    true
                } else false
            }.onFocusChanged { searchFocused = it.isFocused }
                .border(2.dp, if (searchFocused) FamilyColors.accent else FamilyColors.muted).padding(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            item { Button(onClick = { onCategory("") }, modifier = Modifier.focusRequester(allFocus)) {
                Text(if (state.category.isBlank()) "All ✓" else "All")
            } }
            items(state.categories) { category ->
                Button(onClick = { onCategory(category) }) { Text(if (category == state.category) "$category ✓" else category) }
            }
            item { Button(onClick = onRefresh) { Text("Refresh") } }
        }
        if (state.loading) Text("Loading library…", color = FamilyColors.text)
        if (state.error != null) {
            Text("Server unavailable. Saved library remains usable. ${state.error}", color = FamilyColors.text)
            Button(onClick = onRefresh) { Text("Retry") }
        }
        if (!state.loading && state.videos.isEmpty()) Text(
            if (state.query.isNotBlank() || state.category.isNotBlank()) "No matching videos."
            else if (state.error == null) "No videos on this server yet." else "No saved library yet.", color = FamilyColors.text)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).padding(top = 16.dp)) {
            items(state.videos, key = { it.id }) { video ->
                var focused by remember(video.id) { mutableStateOf(false) }
                Button(onClick = { onSelect(video) },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(video.title, style = MaterialTheme.typography.titleMedium,
                            color = if (focused) Color.Black else FamilyColors.text)
                        Text(video.category.ifBlank { "Video" },
                            color = if (focused) Color.DarkGray else FamilyColors.muted)
                        state.progress[video.id]?.let { saved ->
                            val resumeMs = org.familytube.core.model.resumePositionMs(saved.positionMs, saved.durationMs)
                            if (resumeMs > 0) Text("Resume at ${formatTime(resumeMs)}", color = if (focused) Color.DarkGray else FamilyColors.muted)
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun WatchScreen(video: Video?, playback: PlaybackViewModel, onBack: () -> Unit) {
    val state by playback.state.collectAsState()
    val player by playback.player.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (player != null) ContentFrame(player = player!!, modifier = Modifier.fillMaxSize())
            else Text("Paused", color = Color.White)
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 64.dp, vertical = 20.dp)) {
            Text(video?.title ?: "Video unavailable", style = MaterialTheme.typography.headlineSmall, color = FamilyColors.text)
            Text("${formatTime(state.positionMs)} / ${formatTime(state.durationMs)} · ${state.phase}",
                color = FamilyColors.muted)
            if (state.phase == PlaybackPhase.FAILED) Text(state.error ?: "Playback failed", color = Color.Red)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = {
                    if (state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING) playback.pause()
                    else if (state.video == null && video != null) playback.play(video)
                    else playback.resume()
                }, enabled = video != null) {
                    Text(if (state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING) "Pause" else "Play")
                }
                Button(onClick = { playback.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) },
                    enabled = state.durationMs > 0) { Text("Back 10 seconds") }
                Button(onClick = { playback.seekTo((state.positionMs + 10_000).coerceAtMost(state.durationMs)) },
                    enabled = state.durationMs > 0) { Text("Forward 10 seconds") }
                Button(onClick = onBack) { Text("Leave video") }
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
    val connectFocus = remember { FocusRequester() }
    val savedUrl by settings.serverUrl.collectAsState(initial = "")
    var draft by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Enter your home server address to connect.") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(savedUrl) { if (!edited) draft = savedUrl }
    Column(Modifier.fillMaxSize().background(FamilyColors.background).padding(horizontal = 72.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center) {
        Text("FamilyTube", style = MaterialTheme.typography.headlineLarge, color = FamilyColors.text)
        Spacer(Modifier.height(12.dp))
        Text("Connect to your family video server on the home network.", color = FamilyColors.muted)
        Spacer(Modifier.height(36.dp))
        Text("Server address", color = FamilyColors.text)
        Spacer(Modifier.height(8.dp))
        BasicTextField(value = draft, onValueChange = { draft = it; edited = true }, singleLine = true,
            textStyle = TextStyle(color = FamilyColors.text, fontSize = 22.sp),
            cursorBrush = SolidColor(FamilyColors.accent),
            modifier = Modifier.fillMaxWidth(0.7f).onPreInterceptKeyBeforeSoftKeyboard {
                if (it.key == Key.DirectionDown) {
                    if (it.type == KeyEventType.KeyDown) connectFocus.requestFocus()
                    true
                } else false
            }.onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) FamilyColors.accent else FamilyColors.muted)
                .background(FamilyColors.surface).padding(16.dp),
            decorationBox = { inner ->
                if (draft.isEmpty()) Text("http://192.168.1.10:8000", color = FamilyColors.muted)
                inner()
            })
        Spacer(Modifier.height(20.dp))
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
        }, enabled = !busy && draft.isNotBlank(), modifier = Modifier.focusRequester(connectFocus)) {
            Text(if (busy) "Checking…" else "Save and connect")
        }
        Spacer(Modifier.height(20.dp))
        Text(status, color = FamilyColors.muted)
        if (savedUrl.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone) { Text("Open library") }
        }
    }
}

private fun HealthResult.message(): String = if (reachable) {
    "Connected to FamilyTube server · ${videoCount ?: 0} videos"
} else {
    "Could not connect: ${detail ?: "Unknown error"}. Check the address and local Wi-Fi."
}
