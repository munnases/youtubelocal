package org.familytube.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchSessionTest {
    @Test fun countsPlayingTimeRatherThanSeekDistanceAndPreservesSequence() {
        val session = WatchSession("one-session", 100)
        session.sample(1_000) // Preparing.
        session.playing = true
        session.sample(3_000)
        session.playing = false
        session.sample(9_000) // Paused or buffering.
        session.playing = true
        session.sample(10_000)
        assertEquals(3_000, session.watchedMs)
        assertEquals(1, session.nextSequence())
        assertEquals(2, session.nextSequence())
    }
}
