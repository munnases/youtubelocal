package org.familytube.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.familytube.core.model.WatchSnapshot

class StorageTest {
    @Test fun savesAtomicallyAndCoalescesWithoutStaleSequenceRollback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val snapshot = WatchSnapshot("a", "video", "one-session", 1, 42_500, 240_000, 10_000, 100)
            db.persistSnapshot(snapshot)
            db.persistSnapshot(snapshot.copy(sequence = 3, positionMs = 60_000, watchedMs = 15_000, updatedAtEpochMs = 200))
            db.persistSnapshot(snapshot.copy(sequence = 2, positionMs = 50_000, updatedAtEpochMs = 150))
            assertEquals(60_000, db.dao().progressFor("a", "video")!!.positionMs)
            val outbox = db.dao().pendingEvents()
            assertEquals(1, outbox.size)
            assertEquals(3, outbox.single().sequence)
            assertEquals(15_000, outbox.single().watchedMs)
        } finally { db.close() }
    }

    @Test fun relatedVideosHaveLocalCategoryFallbackWithoutServerAccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repository = CatalogRepository(db, FamilyApiFactory(NetworkClient()))
            val library = repository.libraryAt("http://unreachable.invalid:8000")
            assertEquals(library.id, repository.libraryAt("http://unreachable.invalid:8000/").id)
            db.dao().insertVideos(listOf(
                VideoEntity(library.id, "current", "Current", "Songs", 0.0, "url", null, 1),
                VideoEntity(library.id, "recent", "Recent story", "Stories", 0.0, "url", null, 10),
                VideoEntity(library.id, "same-category", "Older song", "Songs", 0.0, "url", null, 2)))
            val selected = db.dao().videos(library.id).first().first { it.id == "current" }.toVideo(library.serverUrl)
            assertEquals(listOf("same-category", "recent"), repository.related(selected).map { it.id })
        } finally { db.close() }
    }
    @Test fun durableCatalogAndOutboxSurviveReopenAndDoNotMixLibraries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "storage-test-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        var db = open()
        try {
            db.withTransaction {
                db.dao().insertLibrary(LibraryEntity("a", "http://server-a"))
                db.dao().insertLibrary(LibraryEntity("b", "http://server-b"))
                db.dao().insertVideos(listOf(VideoEntity("a", "same-id", "Alpha", "Songs", 0.0, "http://server-a/media/1", null, 1)))
                db.dao().putProgress(ProgressEntity("a", "same-id", 42_500, 240_000, 100))
                db.dao().putOutbox(OutboxEntity("a", "same-id", "persistent-session", 2, 42_500, 240_000, 18_000, 100))
            }
            db.close()
            db = open()
            assertEquals("Alpha", db.dao().videos("a").first().single().title)
            assertTrue(db.dao().videos("b").first().isEmpty())
            assertNull(db.dao().progressFor("b", "same-id"))
            assertEquals(42_500, db.dao().progressFor("a", "same-id")!!.positionMs)
            val retry = db.dao().nextEvent()!!
            assertEquals("persistent-session", retry.sessionId)
            assertEquals(2, retry.sequence)
            db.dao().putOutbox(retry.copy(sequence = 3, positionMs = 50_000))
            db.dao().acknowledge("a", retry.sessionId, retry.sequence)
            assertEquals(3, db.dao().nextEvent()!!.sequence) // Old HTTP response cannot erase new snapshot.
            db.dao().acknowledge("a", retry.sessionId, 3)
            assertNull(db.dao().nextEvent())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun catalogReplacementRollsBackAsOneTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val original = VideoEntity("a", "1", "Original", "Songs", 0.0, "url", null, 1)
            db.dao().insertVideos(listOf(original))
            try {
                db.withTransaction {
                    db.dao().deleteVideos("a")
                    throw IllegalStateException("Interrupted catalog refresh")
                }
            } catch (_: IllegalStateException) { }
            assertEquals(listOf(original), db.dao().videos("a").first())
        } finally { db.close() }
    }
}
