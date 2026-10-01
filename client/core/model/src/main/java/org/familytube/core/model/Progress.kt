package org.familytube.core.model

data class WatchSnapshot(
    val libraryId: String,
    val videoId: String,
    val sessionId: String,
    val sequence: Long,
    val positionMs: Long,
    val durationMs: Long,
    val watchedMs: Long,
    val updatedAtEpochMs: Long,
)

/** Implemented by data; playback never depends on Room, networking, or app modules. */
interface ProgressStore {
    suspend fun resumePositionMs(libraryId: String, videoId: String): Long
    suspend fun save(snapshot: WatchSnapshot)
}

fun resumePositionMs(positionMs: Long, durationMs: Long): Long {
    val position = positionMs.coerceAtLeast(0)
    if (durationMs <= 0) return position
    val clamped = position.coerceAtMost(durationMs)
    return if (durationMs - clamped <= 10_000 || clamped.toDouble() / durationMs >= 0.95) 0 else clamped
}
