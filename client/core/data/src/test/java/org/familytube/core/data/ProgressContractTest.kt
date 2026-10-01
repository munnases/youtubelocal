package org.familytube.core.data

import org.familytube.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ProgressContractTest {
    @Test fun convertsFractionalSecondsAndKeepsRetryIdentity() {
        val snapshot = WatchSnapshot("library", "video", "session-uuid", 7, 42_500, 240_000, 18_250, 1_790_000_000_123)
        val dto = snapshot.toDto()
        assertEquals(42.5, dto.position, 0.0)
        assertEquals(18.25, dto.watchedSeconds, 0.0)
        assertEquals(snapshot.updatedAtEpochMs, dto.updatedAt)
        assertEquals(42_500, secondsToMs(dto.position))
        assertEquals(dto, snapshot.toDto())
        assertEquals(0, secondsToMs(Double.NaN))
        assertEquals(0, secondsToMs(-3.0))
    }
    @Test fun nearEndRestartsAndUnknownDurationDoesNotImplyCompletion() {
        assertEquals(50_000, resumePositionMs(50_000, 0))
        assertEquals(0, resumePositionMs(95_000, 100_000))
        assertEquals(0, resumePositionMs(191_000, 200_000))
        assertEquals(80_000, resumePositionMs(80_000, 100_000))
        assertEquals(0, resumePositionMs(-3, 100_000))
    }
    @Test fun pendingLocalSnapshotsWinAndOlderHistoryCannotRollBackProgress() {
        val local = ProgressEntity("library", "video", 90_000, 240_000, 200)
        val old = local.copy(positionMs = 10_000, updatedAtEpochMs = 100)
        val new = local.copy(positionMs = 95_000, updatedAtEpochMs = 300)
        assertFalse(shouldMergeHistory(local, old, false))
        assertFalse(shouldMergeHistory(local, new, true))
        assertTrue(shouldMergeHistory(local, new, false))
    }
    @Test fun searchAndCategoryUseOnlyLocalTitles() {
        val videos = listOf(Video("1", "Song Alpha", "Songs", 0.0, "url", null),
            Video("2", "Story Beta", "Stories", 0.0, "url", null))
        assertEquals(listOf(videos[0]), filterCatalog(videos, " ALPHA ", ""))
        assertTrue(filterCatalog(videos, "alpha", "Stories").isEmpty())
        assertEquals(listOf(videos[1]), filterCatalog(videos, "", "Stories"))
    }
}
