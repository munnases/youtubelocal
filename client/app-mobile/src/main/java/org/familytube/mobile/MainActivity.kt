package org.familytube.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
            FamilyTheme {
                val state by catalog.state.collectAsState()
                val playbackVideo by remember { playback.state.map { it.video }.distinctUntilChanged() }.collectAsState(null)
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var watchId by rememberSaveable { mutableStateOf<String?>(null) }
                var watchLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
                var tab by rememberSaveable { mutableStateOf("Home") }
                // Keep scroll states above navigation, including the continue-watching row.
                val homeScroll = rememberLazyListState()
                val libraryScroll = rememberLazyListState()
                val continueScroll = rememberLazyListState()
                val fullscreen = watchId != null && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                DisposableEffect(fullscreen) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
                    else controller.show(WindowInsetsCompat.Type.systemBars())
                    onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
                }
                LaunchedEffect(state.libraryId) {
                    if (watchLibraryId != null && state.libraryId.isNotBlank() && watchLibraryId != state.libraryId) {
                        playback.stop(); watchId = null; watchLibraryId = null
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                LaunchedEffect(state.settingsLoaded, state.serverUrl) {
                    if (state.settingsLoaded && state.serverUrl.isBlank()) showSettings = true
                }
                LaunchedEffect(playbackVideo) {
                    if (watchId != null && playbackVideo?.libraryId == watchLibraryId) watchId = playbackVideo?.id
                }
                val video = playbackVideo?.takeIf { watchId != null && it.libraryId == watchLibraryId }
                    ?: state.allVideos.firstOrNull { it.id == watchId }
                    ?: playbackVideo?.takeIf { it.id == watchId && it.libraryId == watchLibraryId }
                var related by remember(video?.libraryId, video?.id) { mutableStateOf<List<Video>>(emptyList()) }
                LaunchedEffect(video, state.allVideos) {
                    related = if (video != null) catalog.related(video) else emptyList()
                }
                fun select(selected: Video) {
                    watchId = selected.id; watchLibraryId = selected.libraryId
                    playback.play(selected)
                }
                fun leaveWatch() {
                    if (fullscreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else {
                        playback.stop(); watchId = null; watchLibraryId = null
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                BackHandler(enabled = watchId != null || showSettings) {
                    if (watchId != null) leaveWatch() else showSettings = false
                }
                when {
                    !state.settingsLoaded -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator() }
                    watchId != null -> PhoneWatch(video, related, playback, fullscreen, ::leaveWatch,
                        onFullscreen = {
                            requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }, onSelect = ::select)
                    showSettings || state.serverUrl.isBlank() -> Surface(Modifier.fillMaxSize()) {
                        ServerSetup(settings, healthChecker) { showSettings = false }
                    }
                    else -> PhoneCatalog(state, tab, { tab = it }, homeScroll, libraryScroll, continueScroll,
                        catalog::refresh, { showSettings = true }, catalog::setQuery, catalog::setCategory, ::select)
                }
                KeepScreenAwake(watchId != null)
            }
        }
    }

    @Composable
    private fun KeepScreenAwake(watching: Boolean) {
        val active by remember { playback.state.map {
            it.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING)
        }.distinctUntilChanged() }.collectAsState(false)
        DisposableEffect(watching, active) {
            if (watching && active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) playback.onBackground()
        super.onStop()
    }
    override fun onStart() { super.onStart(); catalog.onForeground() }
}

@Composable
internal fun FamilyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = FamilyColors.accent, onPrimary = FamilyColors.text,
        background = FamilyColors.background, surface = FamilyColors.surface,
        onBackground = FamilyColors.text, onSurface = FamilyColors.text)) {
        CompositionLocalProvider(LocalContentColor provides FamilyColors.text, content = content)
    }
}
