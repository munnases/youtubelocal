package org.familytube.tv

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.familytube.core.data.CatalogState
import org.familytube.core.data.ProgressEntity
import org.familytube.core.model.Video
import org.familytube.core.playback.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val video = Video("a", "Family story", "Stories", 100.0, "http://127.0.0.1/a", null)
    private fun key(key: Key) { compose.onRoot().performKeyInput { pressKey(key) } }
    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun seekPreviewWaitsForOkBackCancelsAndBoundsClamp() {
        compose.mainClock.autoAdvance = false
        val seeks = mutableListOf<Long>()
        compose.setContent { TvTheme { TvWatchControls(video, PlaybackState(video, PlaybackPhase.PLAYING, 20_000, 100_000), emptyList(), {}, {}, seeks::add, {}, {}) } }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("transport").assertIsFocused()
        key(Key.DirectionUp); key(Key.DirectionRight); key(Key.DirectionRight)
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Preview 0:40 · OK to seek · Back to cancel").assertIsDisplayed()
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        back(); compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Seek · Left / Right to preview").assertIsDisplayed()
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        repeat(12) { key(Key.DirectionRight) }
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf(100_000L), seeks) }
        repeat(12) { key(Key.DirectionLeft) }
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf(100_000L, 0L), seeks) }
    }

    @Test fun timeoutRevealAndBackStopHaveDistinctRemoteActions() {
        compose.mainClock.autoAdvance = false
        var pauses = 0
        var leaves = 0
        compose.setContent { TvTheme { TvWatchControls(video, PlaybackState(video, PlaybackPhase.PLAYING, 20_000, 100_000), emptyList(), { pauses++ }, {}, {}, { leaves++ }, {}) } }
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithTag("watch-overlay").assertDoesNotExist()
        key(Key.DirectionCenter); compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("transport").assertIsFocused()
        compose.runOnIdle { assertEquals(0, pauses) }
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(1, pauses) }
        back(); compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("watch-overlay").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, leaves) }
        back(); compose.runOnIdle { assertEquals(1, leaves) }
    }

    @Test fun pausedAndFailedControlsStayVisibleAndRelatedNeverStealsFocus() {
        var state by mutableStateOf(PlaybackState(video, PlaybackPhase.PAUSED, 20_000, 100_000))
        var related by mutableStateOf<List<Video>>(emptyList())
        compose.mainClock.autoAdvance = false
        compose.setContent { TvTheme { TvWatchControls(video, state, related, {}, {}, {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("transport").assertIsFocused()
        compose.runOnIdle { related = listOf(video.copy(id = "b", title = "Another story"), video.copy(id = "c", title = "Third story")) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("transport").assertIsFocused()
        repeat(3) { key(Key.DirectionRight) }; key(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("related:b").assertIsFocused()
        key(Key.DirectionRight)
        compose.runOnIdle { related = related.map { it.copy(title = it.title + " updated") } }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("related:c").assertIsFocused()
        back(); compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("transport").assertIsFocused()
        compose.runOnIdle { state = state.copy(phase = PlaybackPhase.FAILED, error = "Server unavailable") }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun remoteBrowsingRestoresScrolledCardAndShowsContinueWatching() {
        val videos = (0..17).map { video.copy(id = "$it", title = "Story $it") }
        lateinit var memory: TvBrowseMemory
        var watching by mutableStateOf(false)
        compose.setContent {
            memory = remember { TvBrowseMemory() }
            TvTheme {
                if (watching) TvWatchControls(videos[8], PlaybackState(videos[8], PlaybackPhase.PAUSED, 20_000, 100_000), emptyList(), {}, {}, {}, { watching = false }, {})
                else TvCatalogScreen(CatalogState(libraryId = "fixture", videos = videos, allVideos = videos,
                    progress = mapOf("0" to ProgressEntity("", "0", 20_000, 100_000, 1))), memory, {}, {}, {}, {}, { watching = true })
            }
        }
        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        // Down to the category row; scroll it using remote keys, then open the selected card.
        key(Key.DirectionDown)
        repeat(8) { key(Key.DirectionRight) }
        compose.onNodeWithTag("card:category:Stories/8").assertIsFocused()
        val offset = memory.horizontal.getValue("category:Stories").firstVisibleItemIndex
        assertTrue(offset > 0)
        key(Key.DirectionCenter)
        compose.onNodeWithTag("watch-overlay").assertIsDisplayed()
        back(); back()
        compose.onNodeWithTag("card:category:Stories/8").assertIsFocused()
        compose.runOnIdle { assertEquals(offset, memory.horizontal.getValue("category:Stories").firstVisibleItemIndex) }
    }
    @Test fun searchHasRemoteExitClearAndNoMatchRecoveryWithoutFocusSteal() {
        val videos = listOf(video)
        var query by mutableStateOf("")
        lateinit var memory: TvBrowseMemory
        compose.setContent {
            memory = remember { TvBrowseMemory().apply { page = "Search" } }
            TvTheme { TvCatalogScreen(CatalogState(videos = videos.filter { it.title.contains(query, true) }, allVideos = videos, query = query),
                memory, {}, {}, { query = it }, {}, {}) }
        }
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("missing")
        compose.onNodeWithText("No matching videos.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithText("View results").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithText("Clear").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals("", query) }
        compose.onNode(hasSetTextAction()).performTextInput("story")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        key(Key.DirectionDown); key(Key.DirectionDown)
        compose.onNodeWithTag("card:category:Stories/a").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        back()
        compose.runOnIdle { assertEquals("Home", memory.page) }
        compose.onNode(hasText("Home") and isFocused()).assertExists()
    }

}
