package org.familytube.core.data

import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.*
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.familytube.core.model.WatchSnapshot

@Serializable
data class HistoryResponse(val history: Map<String, HistoryDto> = emptyMap())
@Serializable
data class HistoryDto(val position: Double = 0.0, val duration: Double = 0.0, val updatedAt: Long = 0)
@Serializable
data class RecommendationsResponse(val items: List<VideoDto> = emptyList())
@Serializable
data class WatchDto(val videoId: String, val sessionId: String, val sequence: Long,
    val position: Double, val duration: Double, val watchedSeconds: Double, val updatedAt: Long)

internal const val MAX_PLAYBACK_MS = 7L * 86_400 * 1_000
fun secondsToMs(seconds: Double): Long = if (!seconds.isFinite()) 0 else
    (seconds.coerceIn(0.0, MAX_PLAYBACK_MS / 1000.0) * 1000).toLong()
fun WatchSnapshot.toDto() = WatchDto(videoId, sessionId, sequence,
    positionMs.coerceIn(0, MAX_PLAYBACK_MS) / 1000.0,
    durationMs.coerceIn(0, MAX_PLAYBACK_MS) / 1000.0,
    watchedMs.coerceIn(0, MAX_PLAYBACK_MS) / 1000.0, updatedAtEpochMs)
fun OutboxEntity.toSnapshot() = WatchSnapshot(libraryId, videoId, sessionId, sequence,
    positionMs, durationMs, watchedMs, updatedAtEpochMs)

interface FamilyApi {
    @GET("api/videos") suspend fun videos(): List<VideoDto>
    @GET("api/watch/history") suspend fun history(@Header("X-Device-ID") device: String): HistoryResponse
    @GET("api/recommendations") suspend fun recommendations(@Header("X-Device-ID") device: String): RecommendationsResponse
    @POST("api/watch") suspend fun watch(@Header("X-Device-ID") device: String, @Body event: WatchDto)
}

class FamilyApiFactory @Inject constructor(private val network: NetworkClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    fun at(origin: String): FamilyApi = Retrofit.Builder().baseUrl("$origin/")
        .client(network.http).addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(FamilyApi::class.java)
}
