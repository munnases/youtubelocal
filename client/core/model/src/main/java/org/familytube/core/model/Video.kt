package org.familytube.core.model

/** Values from the existing server catalog. Duration is expressed in seconds at this boundary. */
data class Video(
    val id: String,
    val title: String,
    val category: String,
    val durationSeconds: Double,
    val streamUrl: String,
    val thumbnailUrl: String?,
    val libraryId: String = "",
    val serverUrl: String = "",
    val addedAtEpochMs: Long = 0,
)
