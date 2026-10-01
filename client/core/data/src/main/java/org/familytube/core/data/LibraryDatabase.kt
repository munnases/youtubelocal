package org.familytube.core.data

import android.content.Context
import androidx.room.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.familytube.core.model.Video
import org.familytube.core.model.ProgressStore

@Entity(tableName = "libraries", indices = [Index(value = ["serverUrl"], unique = true)])
data class LibraryEntity(@PrimaryKey val id: String, val serverUrl: String, val refreshedAtEpochMs: Long = 0)

@Entity(tableName = "videos", primaryKeys = ["libraryId", "id"])
data class VideoEntity(
    val libraryId: String, val id: String, val title: String, val category: String,
    val durationSeconds: Double, val streamUrl: String, val thumbnailUrl: String?, val addedAtEpochMs: Long,
    val recommendationOrder: Int = Int.MAX_VALUE,
) {
    fun toVideo(serverUrl: String) = Video(id, title, category, durationSeconds, streamUrl,
        thumbnailUrl, libraryId, serverUrl, addedAtEpochMs)
}

@Entity(tableName = "progress", primaryKeys = ["libraryId", "videoId"])
data class ProgressEntity(val libraryId: String, val videoId: String, val positionMs: Long,
    val durationMs: Long, val updatedAtEpochMs: Long)

@Entity(tableName = "watch_outbox", primaryKeys = ["libraryId", "sessionId"])
data class OutboxEntity(val libraryId: String, val videoId: String, val sessionId: String,
    val sequence: Long, val positionMs: Long, val durationMs: Long, val watchedMs: Long,
    val updatedAtEpochMs: Long)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM libraries WHERE serverUrl = :url LIMIT 1")
    suspend fun libraryAt(url: String): LibraryEntity?
    @Query("SELECT * FROM libraries WHERE id = :id LIMIT 1")
    suspend fun library(id: String): LibraryEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibrary(library: LibraryEntity)
    @Query("UPDATE libraries SET refreshedAtEpochMs = :at WHERE id = :id")
    suspend fun refreshed(id: String, at: Long)
    @Query("SELECT * FROM videos WHERE libraryId = :id ORDER BY addedAtEpochMs DESC, title COLLATE NOCASE, id")
    fun videos(id: String): Flow<List<VideoEntity>>
    @Query("SELECT * FROM videos WHERE libraryId = :id ORDER BY recommendationOrder, addedAtEpochMs DESC, id")
    suspend fun recommendationCandidates(id: String): List<VideoEntity>
    @Query("DELETE FROM videos WHERE libraryId = :id")
    suspend fun deleteVideos(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideos(videos: List<VideoEntity>)
    @Query("UPDATE videos SET recommendationOrder = :rank WHERE libraryId = :library AND id = :video")
    suspend fun rank(library: String, video: String, rank: Int)
    @Query("SELECT * FROM progress WHERE libraryId = :id")
    fun progress(id: String): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE libraryId = :library AND videoId = :video LIMIT 1")
    suspend fun progressFor(library: String, video: String): ProgressEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProgress(progress: ProgressEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutbox(event: OutboxEntity)
    @Query("SELECT * FROM watch_outbox ORDER BY updatedAtEpochMs, sessionId LIMIT 1")
    suspend fun nextEvent(): OutboxEntity?
    @Query("SELECT * FROM watch_outbox ORDER BY updatedAtEpochMs, sessionId LIMIT 100")
    suspend fun pendingEvents(): List<OutboxEntity>
    @Query("SELECT * FROM watch_outbox WHERE libraryId = :library AND sessionId = :session LIMIT 1")
    suspend fun eventFor(library: String, session: String): OutboxEntity?
    @Query("SELECT COUNT(*) FROM watch_outbox WHERE libraryId = :library AND videoId = :video")
    suspend fun pendingFor(library: String, video: String): Int
    @Query("DELETE FROM watch_outbox WHERE libraryId = :library AND sessionId = :session AND sequence = :sequence")
    suspend fun acknowledge(library: String, session: String, sequence: Long)
}

@Database(entities = [LibraryEntity::class, VideoEntity::class, ProgressEntity::class, OutboxEntity::class],
    version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "familytube.db").build()
    @Provides
    fun progressStore(repository: ProgressRepository): ProgressStore = repository
}
