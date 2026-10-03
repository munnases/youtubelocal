package org.familytube.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.familytube.core.data.CatalogViewModel
import org.familytube.core.playback.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Uses an isolated fixture origin, restores settings, and never clears installation identity. */
class TvPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun key(key: Key) { compose.onRoot().performKeyInput { pressKey(key) } }
    private fun back() { compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

    @Test fun remoteSeekMediaKeysBackFocusResumeAndRelatedSelection() {
        val origin = InstrumentationRegistry.getArguments().getString("fixtureServer")
        assumeTrue("Supply an isolated fixtureServer", origin != null)
        val previous = runBlocking { compose.activity.settings.serverUrl.first() }
        try {
            runBlocking { compose.activity.settings.saveServerUrl(checkNotNull(origin)) }
            lateinit var catalog: CatalogViewModel
            lateinit var playback: PlaybackViewModel
            compose.runOnIdle {
                catalog = ViewModelProvider(compose.activity)[CatalogViewModel::class.java]
                playback = ViewModelProvider(compose.activity)[PlaybackViewModel::class.java]
            }
            compose.waitUntil(15_000) { catalog.state.value.videos.size >= 12 }
            if (compose.onAllNodesWithText("Open library").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Open library").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("browse-rows").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("card:") == true
            } }
            // A long horizontal row exercises focus-driven scrolling, then restoration.
            repeat(8) { key(Key.DirectionRight) }
            val selected = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("card:") == true
            }.config[SemanticsProperties.TestTag]
            val selectedId = selected.substringAfterLast('/')
            key(Key.DirectionCenter)
            compose.waitUntil(20_000) { playback.state.value.phase == PlaybackPhase.PLAYING && playback.state.value.durationMs > 0 }
            key(Key.MediaPause)
            compose.waitUntil(5_000) { playback.state.value.phase == PlaybackPhase.PAUSED }
            compose.runOnIdle { playback.seekTo(42_000) }
            compose.waitUntil(5_000) { playback.state.value.positionMs in 41_000..43_000 }
            val player = playback.player.value!!
            compose.onNodeWithTag("transport").assertIsFocused()
            key(Key.DirectionUp); key(Key.DirectionRight)
            compose.runOnIdle { assertTrue(playback.state.value.positionMs in 41_000..43_000) }
            back()
            compose.runOnIdle { assertTrue(playback.state.value.positionMs in 41_000..43_000) }
            key(Key.DirectionRight); key(Key.DirectionCenter)
            compose.waitUntil(5_000) { playback.state.value.positionMs in 51_000..53_000 }
            key(Key.MediaPlay)
            compose.waitUntil(5_000) { playback.state.value.phase == PlaybackPhase.PLAYING }
            key(Key.MediaPause)
            compose.waitUntil(5_000) { playback.state.value.phase == PlaybackPhase.PAUSED }
            back()
            compose.onNodeWithTag("watch-overlay").assertDoesNotExist()
            compose.runOnIdle { assertEquals(selectedId, playback.state.value.video!!.id) }
            back()
            compose.waitUntil(5_000) { playback.state.value.video == null }
            compose.runOnIdle { assertFalse(player.isPlaying); assertEquals(0, player.mediaItemCount) }
            compose.onNodeWithTag(selected).assertIsFocused().assertIsDisplayed()
            key(Key.DirectionCenter)
            compose.waitUntil(20_000) { playback.state.value.phase == PlaybackPhase.PLAYING }
            compose.runOnIdle { assertEquals(selectedId, playback.state.value.video!!.id); assertTrue(playback.state.value.positionMs >= 51_000) }
            key(Key.MediaPause)
            compose.waitUntil(5_000) { playback.state.value.phase == PlaybackPhase.PAUSED }
            repeat(3) { key(Key.DirectionRight) }; key(Key.DirectionCenter)
            compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("related:") == true
            } }
            key(Key.DirectionCenter)
            compose.waitUntil(20_000) { playback.state.value.video?.id != selectedId && playback.state.value.phase == PlaybackPhase.PLAYING }
            compose.runOnIdle { assertSame(player, playback.player.value) }
            compose.waitUntil(5_000) { playback.nextState.value.video != null }
            val relatedId = playback.state.value.video!!.id
            compose.runOnIdle { playback.seekTo(playback.state.value.durationMs) }
            compose.waitUntil(10_000) { playback.nextState.value.secondsRemaining != null }
            compose.onNodeWithTag("next-countdown").assertIsDisplayed()
            back()
            val cancelledAt = android.os.SystemClock.elapsedRealtime()
            compose.waitUntil(7_000) { android.os.SystemClock.elapsedRealtime() - cancelledAt >= 5_500 }
            compose.runOnIdle { assertEquals(relatedId, playback.state.value.video!!.id) }
            key(Key.MediaPlay)
            compose.waitUntil(10_000) { playback.state.value.phase == PlaybackPhase.PLAYING }
            val automatic = playback.nextState.value.video!!
            compose.runOnIdle { playback.seekTo(playback.state.value.durationMs) }
            compose.waitUntil(10_000) { playback.nextState.value.secondsRemaining != null }
            compose.waitUntil(15_000) { playback.state.value.video?.id == automatic.id && playback.state.value.phase == PlaybackPhase.PLAYING }
            compose.onNodeWithText(automatic.title).assertExists()
            compose.runOnIdle { assertSame(player, playback.player.value) }
            compose.waitUntil(5_000) { playback.nextState.value.video != null }
            val immediateId = playback.nextState.value.video!!.id
            key(Key.MediaNext)
            compose.waitUntil(20_000) { playback.state.value.video?.id == immediateId && playback.state.value.phase == PlaybackPhase.PLAYING }
            key(Key.MediaStop)
            compose.waitUntil(5_000) { playback.state.value.video == null }
            compose.onNodeWithTag(selected).assertIsFocused()
        } finally {
            compose.runOnIdle { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java].stop() }
            if (previous.isNotBlank()) runBlocking { compose.activity.settings.saveServerUrl(previous) }
        }
    }
}
