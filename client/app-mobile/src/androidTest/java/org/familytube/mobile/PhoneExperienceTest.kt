package org.familytube.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.familytube.core.data.CatalogState
import org.familytube.core.data.ProgressEntity
import org.familytube.core.model.Video
import org.familytube.core.playback.PlaybackPhase
import org.familytube.core.playback.PlaybackState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhoneExperienceTest {
    @Test fun nextAndCountdownActionsAreAccessibleAndUnavailableNextIsDisabled() {
        var next by mutableStateOf(org.familytube.core.playback.NextVideoState(video.copy(id = "b", title = "Next story"), 5))
        var skips = 0
        var cancellations = 0
        compose.setContent { FamilyTheme {
            PhoneControls(PlaybackState(video, PlaybackPhase.ENDED, 100_000, 100_000), video, false,
                {}, {}, {}, {}, next, { skips++ }, { cancellations++; next = next.copy(secondsRemaining = null) })
        } }
        compose.onNodeWithTag("next-countdown").assertIsDisplayed()
        compose.onNodeWithTag("cancel-next").performClick()
        compose.onNodeWithTag("next-countdown").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next video").performClick()
        compose.runOnIdle { assertEquals(1, skips); assertEquals(1, cancellations); next = org.familytube.core.playback.NextVideoState() }
        compose.onNodeWithContentDescription("Next video").assertIsNotEnabled()
    }
    @get:Rule val compose = createComposeRule()
    private val video = Video("a", "Family story", "Stories", 100.0, "http://127.0.0.1/a", null)

    private fun controls(state: PlaybackState, seek: (Long) -> Unit = {}) {
        compose.setContent {
            FamilyTheme { Box(Modifier.fillMaxWidth().height(260.dp)) { PhoneControls(state, video, false, {}, {}, {}, seek) } }
        }
    }

    @Test fun playingControlsTimeoutAndSingleTapReveals() {
        compose.mainClock.autoAdvance = false
        controls(PlaybackState(video, PlaybackPhase.PLAYING, 20_000, 100_000))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_100)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithTag("player-controls").performTouchInput { click(Offset(width * .1f, height * .4f)) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun pausedAndErrorControlsRemainVisible() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(PlaybackState(video, PlaybackPhase.PAUSED, 20_000, 100_000))
        compose.setContent { FamilyTheme { Box(Modifier.fillMaxWidth().height(300.dp)) { PhoneControls(state, video, false, {}, {}, {}, {}) } } }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(phase = PlaybackPhase.FAILED) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithContentDescription("Retry playback").assertIsDisplayed()
        compose.onNodeWithTag("playback-error").assertIsDisplayed()
    }

    @Test fun doubleTapSeeksBothSidesAndClamps() {
        val seeks = mutableListOf<Long>()
        controls(PlaybackState(video, PlaybackPhase.PAUSED, 5_000, 12_000), seeks::add)
        compose.onNodeWithTag("player-controls").performTouchInput { doubleClick(Offset(width * .1f, height * .4f)) }
        compose.runOnIdle { assertEquals(listOf(0L), seeks) }
        compose.onNodeWithTag("player-controls").performTouchInput { doubleClick(Offset(width * .9f, height * .4f)) }
        compose.runOnIdle { assertEquals(listOf(0L, 12_000L), seeks) }
    }

    @Test fun scrubCommitsOnceOnReleaseAndDoesNotTimeoutWhileDragging() {
        compose.mainClock.autoAdvance = false
        val seeks = mutableListOf<Long>()
        controls(PlaybackState(video, PlaybackPhase.PLAYING, 20_000, 100_000), seeks::add)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("timeline").performTouchInput {
            down(Offset(width * .2f, height / 2f)); moveTo(Offset(width * .7f, height / 2f))
        }
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithTag("timeline").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, seeks.size); assertTrue(seeks.single() in 60_000L..80_000L) }
    }

    @Test fun libraryScrollSurvivesWatchAndHomeHasContinueWatching() {
        val videos = (0..20).map { video.copy(id = "$it", title = "Story $it") }
        compose.setContent {
            val home = rememberLazyListState()
            val library = rememberLazyListState()
            val continuing = rememberLazyListState()
            var tab by remember { mutableStateOf("Library") }
            var watching by remember { mutableStateOf(false) }
            FamilyTheme {
                if (watching) Button(onClick = { watching = false }) { Text("Leave watch") }
                else PhoneCatalog(CatalogState(videos = videos, allVideos = videos,
                    progress = mapOf("0" to ProgressEntity("library", "0", 20_000, 100_000, 1))),
                    tab, { tab = it }, home, library, continuing, {}, {}, {}, {}, { watching = true })
            }
        }
        compose.onNodeWithTag("catalog").performScrollToNode(hasTestTag("video:12"))
        compose.onNodeWithTag("video:12").performClick()
        compose.onNodeWithText("Leave watch").performClick()
        compose.onNodeWithTag("video:12").assertIsDisplayed()
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.onAllNodesWithText("Stories · Resume 0:20")[0].assertIsDisplayed()
    }

    @Test fun reopeningPopulatedSearchKeepsFieldAvailableWhileEditing() {
        compose.setContent {
            var query by remember { mutableStateOf("") }
            val home = rememberLazyListState()
            val library = rememberLazyListState()
            val continuing = rememberLazyListState()
            FamilyTheme {
                PhoneCatalog(CatalogState(query = query, videos = listOf(video).filter { it.title.contains(query) }, allVideos = listOf(video)),
                    "Home", {}, home, library, continuing, {}, {}, { query = it }, {}, {})
            }
        }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithTag("search").performTextInput("Family")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithTag("search").performTextClearance()
        compose.onNodeWithTag("search").assertIsDisplayed().performTextInput("missing")
        compose.onNodeWithText("No matching videos").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("video:a").assertIsDisplayed()
    }
}
