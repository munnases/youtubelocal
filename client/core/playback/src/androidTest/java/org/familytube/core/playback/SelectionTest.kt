package org.familytube.core.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.familytube.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SelectionTest {
    @Test fun delayedResumeLookupCannotReplaceNewSelectionAndBackgroundReleasesPlayer() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Long>()
        val store = object : ProgressStore {
            override suspend fun resumePositionMs(libraryId: String, videoId: String): Long {
                if (videoId == "a") { lookupStarted.complete(Unit); return releaseLookup.await() }
                return 0
            }
            override suspend fun save(snapshot: WatchSnapshot) { }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = withContext(Dispatchers.Main) { PlaybackCoordinator(context, PlaybackPersistence(store)) }
        val a = Video("a", "A", "Songs", 0.0, "http://127.0.0.1:9/media/a", null, "library")
        try {
            withContext(Dispatchers.Main) { coordinator.play(a) }
            withTimeout(5_000) { lookupStarted.await() }
            withContext(Dispatchers.Main) { coordinator.play(a.copy(id = "b", title = "B")) }
            releaseLookup.complete(42_000)
            withTimeout(5_000) { coordinator.player.filterNotNull().first() }
            withContext(Dispatchers.Main) {
                assertEquals("b", coordinator.state.value.video!!.id)
                assertTrue(coordinator.player.value!!.currentMediaItem!!.mediaId.startsWith("b#"))
                coordinator.releaseForBackground()
                assertNull(coordinator.player.value)
                assertEquals(PlaybackPhase.PAUSED, coordinator.state.value.phase)
            }
        } finally { withContext(Dispatchers.Main) { coordinator.release() } }
    }
}
