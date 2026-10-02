package org.familytube.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.familytube.core.data.*
import org.familytube.core.designsystem.FamilyColors
import org.familytube.core.playback.*

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: ServerSettingsRepository
    @Inject lateinit var healthChecker: ServerHealthChecker
    private val catalog: CatalogViewModel by viewModels()
    private val playback: PlaybackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            TvTheme {
                val catalogState by catalog.state.collectAsState()
                val playbackVideo by remember { playback.state.map { it.video }.distinctUntilChanged() }
                    .collectAsState(initial = null)
                val browse = rememberSaveable(saver = TvBrowseMemory.Saver) { TvBrowseMemory() }
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
                val video = catalogState.allVideos.firstOrNull { it.id == watchId }
                    ?: playbackVideo?.takeIf { it.id == watchId && it.libraryId == watchLibraryId }
                fun leaveWatch() { playback.stop(); watchId = null; watchLibraryId = null }
                BackHandler(enabled = showSettings && watchId == null) { showSettings = false }
                DisposableEffect(watchId) {
                    if (watchId != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                when {
                    !catalogState.settingsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Opening FamilyTube…")
                    }
                    watchId != null -> TvWatchScreen(video, playback, catalog::related, ::leaveWatch) {
                        watchId = it.id; watchLibraryId = it.libraryId; playback.play(it)
                    }
                    showSettings || catalogState.serverUrl.isBlank() -> ServerSetup(settings, healthChecker) {
                        showSettings = false
                    }
                    else -> TvCatalogScreen(catalogState, browse, catalog::refresh, { showSettings = true },
                        catalog::setQuery, catalog::setCategory) {
                        watchId = it.id; watchLibraryId = it.libraryId; playback.play(it)
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
internal fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = FamilyColors.accent, onPrimary = FamilyColors.text,
        background = FamilyColors.background, surface = FamilyColors.surface,
        onBackground = FamilyColors.text, onSurface = FamilyColors.text)) {
        CompositionLocalProvider(LocalContentColor provides FamilyColors.text, content = content)
    }
}

internal fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
@Composable
internal fun ServerSetup(settings: ServerSettingsRepository, healthChecker: ServerHealthChecker, onDone: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val connectFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; addressFocus.requestFocus() }
    fun addressKey(event: KeyEvent): Boolean {
        if (event.key != Key.DirectionDown) return false
        if (event.type == KeyEventType.KeyDown) { keyboard?.hide(); connectFocus.requestFocus() }
        return true
    }
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
            modifier = Modifier.fillMaxWidth(0.7f).focusRequester(addressFocus).onPreInterceptKeyBeforeSoftKeyboard(::addressKey)
                .onPreviewKeyEvent(::addressKey).onFocusChanged { focused = it.isFocused }
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
            Text(if (busy) "Checking..." else "Save and connect")
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
    "Connected to FamilyTube server - ${videoCount ?: 0} videos"
} else {
    "Could not connect: ${detail ?: "Unknown error"}. Check the address and local Wi-Fi."
}
