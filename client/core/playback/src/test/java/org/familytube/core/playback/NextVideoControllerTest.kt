package org.familytube.core.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.familytube.core.model.Video
import org.junit.Assert.*
import org.junit.Test

class NextVideoControllerTest {
    private val a = Video("a", "A", "Stories", 10.0, "http://server/a", null, "library")
    private val b = a.copy(id = "b", title = "B")
    private val c = a.copy(id = "c", title = "C")

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val played = mutableListOf<Video>()
        val controller = NextVideoController(scope, played::add) { ticks.receive() }
        fun tick() { check(ticks.trySend(Unit).isSuccess) }
    }

    @Test fun advancesOnlyAfterFiveSecondsAndMetadataRefreshDoesNotRestartCountdown() {
        val h = Harness()
        try {
            h.controller.updatePlayback(a, false)
            h.controller.setCandidates(a, listOf(a, b, c))
            assertNull(h.controller.state.value.secondsRemaining)
            h.controller.updatePlayback(a, true)
            assertEquals(5, h.controller.state.value.secondsRemaining)
            repeat(4) { index ->
                h.tick()
                h.controller.updatePlayback(a, true)
                h.controller.setCandidates(a, listOf(b.copy(title = "Updated B"), c))
                assertEquals(4 - index, h.controller.state.value.secondsRemaining)
                assertTrue(h.played.isEmpty())
            }
            h.tick()
            assertEquals(listOf("b"), h.played.map { it.id })
            h.controller.updatePlayback(a, true)
            assertNull(h.controller.state.value.secondsRemaining)
            assertEquals(1, h.played.size)
        } finally { h.scope.cancel() }
    }

    @Test fun cancellationSurvivesProgressAndCandidateUpdatesButReplayCanStartANewCountdown() {
        val h = Harness()
        try {
            h.controller.updatePlayback(a, false)
            h.controller.setCandidates(a, listOf(b))
            h.controller.updatePlayback(a, true)
            h.tick()
            h.controller.cancel()
            h.controller.updatePlayback(a, true)
            h.controller.setCandidates(a, listOf(c))
            assertNull(h.controller.state.value.secondsRemaining)
            assertTrue(h.played.isEmpty())
            h.controller.updatePlayback(a, false)
            h.controller.updatePlayback(a, true)
            assertEquals(5, h.controller.state.value.secondsRemaining)
            repeat(5) { h.tick() }
            assertEquals(listOf(c), h.played)
        } finally { h.scope.cancel() }
    }

    @Test fun selectionStopAndBackgroundCancellationCannotAdvanceAnOldVideo() {
        for (transition in listOf("selection", "stop", "background")) {
            val h = Harness()
            try {
                h.controller.updatePlayback(a, false)
                h.controller.setCandidates(a, listOf(b))
                h.controller.updatePlayback(a, true)
                h.tick()
                when (transition) {
                    "selection" -> h.controller.updatePlayback(c, false)
                    "stop" -> h.controller.reset()
                    else -> h.controller.cancel()
                }
                h.controller.setCandidates(a, listOf(b))
                repeat(6) { h.tick() }
                assertTrue(transition, h.played.isEmpty())
                assertNull(h.controller.state.value.secondsRemaining)
            } finally { h.scope.cancel() }
        }
    }

    @Test fun immediateNextCancelsTimerAndRejectsSelfOtherLibrariesAndStaleCandidates() {
        val h = Harness()
        try {
            h.controller.updatePlayback(a, true)
            h.controller.setCandidates(a, listOf(a, b.copy(libraryId = "other")))
            assertNull(h.controller.state.value.video)
            h.controller.setCandidates(c, listOf(b))
            assertNull(h.controller.state.value.video)
            h.controller.setCandidates(a, listOf(b))
            assertEquals(5, h.controller.state.value.secondsRemaining)
            h.controller.playNext()
            repeat(6) { h.tick() }
            assertEquals(listOf(b), h.played)
        } finally { h.scope.cancel() }
    }
}
