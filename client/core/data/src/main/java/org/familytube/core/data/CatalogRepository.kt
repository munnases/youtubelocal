package org.familytube.core.data

import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.familytube.core.model.Video
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

@Serializable
data class VideoDto(
    val id: String,
    val title: String,
    val category: String = "",
    @SerialName("duration") val durationSeconds: Double = 0.0,
    val streamUrl: String,
    val thumbnailUrl: String? = null,
    val addedAt: Long = 0,
)

@Singleton
class CatalogRepository @Inject constructor(private val db: LibraryDatabase, private val api: FamilyApiFactory) {
    suspend fun libraryAt(serverUrl: String): LibraryEntity {
        val url = normalizeServerUrl(serverUrl).toHttpUrl().toString().trimEnd('/')
        db.dao().insertLibrary(LibraryEntity(UUID.randomUUID().toString(), url))
        return checkNotNull(db.dao().libraryAt(url))
    }

    fun observe(library: LibraryEntity) = db.dao().videos(library.id).map { entries ->
        entries.map { it.toVideo(library.serverUrl) }
    }

    suspend fun refresh(library: LibraryEntity) {
        val origin = library.serverUrl.toHttpUrl()
        val videos = api.at(library.serverUrl).videos().mapNotNull { it.toVideoAt(origin) }
        db.withTransaction {
            db.dao().deleteVideos(library.id)
            db.dao().insertVideos(videos.map { VideoEntity(library.id, it.id, it.title, it.category,
                it.durationSeconds, it.streamUrl, it.thumbnailUrl, it.addedAtEpochMs) })
            db.dao().refreshed(library.id, System.currentTimeMillis())
        }
    }

    suspend fun refreshRecommendations(library: LibraryEntity, deviceId: String) {
        val ids = api.at(library.serverUrl).recommendations(deviceId).items.map { it.id }
        db.withTransaction { ids.forEachIndexed { rank, id -> db.dao().rank(library.id, id, rank) } }
    }

    suspend fun related(video: Video): List<Video> = db.dao().recommendationCandidates(video.libraryId)
        .filter { it.id != video.id }
        .sortedWith(compareBy<VideoEntity> { it.recommendationOrder }
            .thenBy { if (it.category == video.category) 0 else 1 }.thenByDescending { it.addedAtEpochMs })
        .take(5).map { it.toVideo(video.serverUrl) }
}

/** Never use a stream or artwork URL pointing away from the parent's configured server. */
fun VideoDto.toVideoAt(origin: HttpUrl): Video? {
    val stream = streamUrl.toHttpUrlOrNull()?.takeIf { it.sameOrigin(origin) } ?: return null
    val artwork = thumbnailUrl?.toHttpUrlOrNull()?.takeIf { it.sameOrigin(origin) }
    return Video(
        id = id,
        title = title,
        category = category,
        durationSeconds = durationSeconds.takeIf { it.isFinite() && it > 0 } ?: 0.0,
        streamUrl = stream.toString(),
        thumbnailUrl = artwork?.toString(),
        serverUrl = origin.toString().trimEnd('/'),
        addedAtEpochMs = addedAt.coerceAtLeast(0),
    )
}

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port &&
        username.isEmpty() && password.isEmpty()
