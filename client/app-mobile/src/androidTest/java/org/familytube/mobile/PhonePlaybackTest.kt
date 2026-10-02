package org.familytube.mobile

import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.familytube.core.data.CatalogViewModel
import org.familytube.core.playback.PlaybackPhase
import org.familytube.core.playback.PlaybackViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Supply fixtureServer only for an isolated emulator fixture, never a live library. */
class PhonePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fullscreenRetainsPlayerBackStopsAndSelectionResumes() {
        val origin = InstrumentationRegistry.getArguments().getString("fixtureServer")
        assumeTrue("Pass fixtureServer to run the real playback journey", origin != null)
        runBlocking { compose.activity.settings.saveServerUrl(checkNotNull(origin)) }
        lateinit var catalog: CatalogViewModel
        compose.runOnIdle { catalog = ViewModelProvider(compose.activity)[CatalogViewModel::class.java] }
        // Close setup if this installation had no saved origin.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("catalog").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Open library").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Open library").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Open library").performClick()
        compose.waitUntil(15_000) { catalog.state.value.videos.any { it.id == "story" } }
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("catalog").performScrollToNode(hasTestTag("video:story"))
        compose.onNodeWithTag("video:story").performClick()
        lateinit var playback: PlaybackViewModel
        compose.runOnIdle { playback = ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        compose.waitUntil(20_000) { playback.state.value.phase == PlaybackPhase.PLAYING && playback.state.value.durationMs > 0 }
        compose.onNodeWithContentDescription("Video player").performSemanticsAction(SemanticsActions.OnClick) { it() }
        // Reveal through accessibility semantics, then pause and seek a known resume point.
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.runOnIdle { playback.seekTo(42_000) }
        compose.waitUntil(5_000) { playback.state.value.positionMs in 41_000..43_000 }
        val player = playback.player.value!!
        var itemId = ""
        compose.runOnIdle { itemId = player.currentMediaItem!!.mediaId; playback.resume() }
        compose.waitUntil(5_000) { playback.state.value.phase == PlaybackPhase.PLAYING }
        compose.onNodeWithContentDescription("Video player").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Fullscreen").performClick()
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.runOnIdle {
            assertSame(player, playback.player.value)
            assertEquals(itemId, player.currentMediaItem!!.mediaId)
            assertTrue(playback.state.value.positionMs in 41_000L..70_000L)
            assertEquals(PlaybackPhase.PLAYING, playback.state.value.phase)
        }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.runOnIdle { assertSame(player, playback.player.value); assertEquals(itemId, player.currentMediaItem!!.mediaId) }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { playback.state.value.video == null }
        compose.runOnIdle { assertFalse(player.isPlaying); assertEquals(0, player.mediaItemCount) }
        compose.onNodeWithTag("video:story").assertIsDisplayed().performClick()
        compose.waitUntil(20_000) { playback.state.value.phase == PlaybackPhase.PLAYING }
        compose.runOnIdle { assertEquals("story", playback.state.value.video!!.id); assertTrue(playback.state.value.positionMs >= 41_000) }
        compose.runOnIdle { playback.seekTo(playback.state.value.durationMs) }
        compose.waitUntil(10_000) { playback.state.value.phase == PlaybackPhase.ENDED }
        compose.onNodeWithContentDescription("Replay").performClick()
        compose.waitUntil(10_000) { playback.state.value.phase == PlaybackPhase.PLAYING }
        compose.runOnIdle { assertTrue(playback.state.value.positionMs < 10_000) }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }
}
