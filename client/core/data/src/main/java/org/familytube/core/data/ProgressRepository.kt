package org.familytube.core.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.familytube.core.model.*
import retrofit2.HttpException

fun shouldMergeHistory(local: ProgressEntity?, remote: ProgressEntity, hasPending: Boolean): Boolean =
    !hasPending && (local == null || remote.updatedAtEpochMs > local.updatedAtEpochMs)

internal suspend fun LibraryDatabase.persistSnapshot(snapshot: WatchSnapshot) = withTransaction {
    val dao = dao()
    val existing = dao.eventFor(snapshot.libraryId, snapshot.sessionId)
    if (existing != null && existing.sequence >= snapshot.sequence) return@withTransaction
    val previous = dao.progressFor(snapshot.libraryId, snapshot.videoId)
    if (previous == null || snapshot.updatedAtEpochMs >= previous.updatedAtEpochMs) {
        dao.putProgress(ProgressEntity(snapshot.libraryId, snapshot.videoId,
            snapshot.positionMs.coerceAtLeast(0), snapshot.durationMs.coerceAtLeast(0), snapshot.updatedAtEpochMs))
    }
    dao.putOutbox(OutboxEntity(snapshot.libraryId, snapshot.videoId, snapshot.sessionId,
        snapshot.sequence, snapshot.positionMs, snapshot.durationMs, snapshot.watchedMs, snapshot.updatedAtEpochMs))
}

@Singleton
class ProgressRepository @Inject constructor(
    private val db: LibraryDatabase, private val api: FamilyApiFactory,
    private val settings: ServerSettingsRepository, @ApplicationContext private val context: Context,
) : ProgressStore {
    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val dao get() = db.dao()
    init { scope.launch { for (request in syncRequests) syncPending() } }

    override suspend fun resumePositionMs(libraryId: String, videoId: String): Long {
        val p = dao.progressFor(libraryId, videoId) ?: return 0
        return org.familytube.core.model.resumePositionMs(p.positionMs, p.durationMs)
    }

    override suspend fun save(snapshot: WatchSnapshot) {
        if (snapshot.libraryId.isBlank()) return
        db.persistSnapshot(snapshot)
        // Schedule only after commit. LAN-only Wi-Fi must not need validated internet.
        scheduleSync()
        syncRequests.trySend(Unit)
    }

    fun scheduleSync() {
        val work = OneTimeWorkRequestBuilder<WatchSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("familytube-watch-sync", ExistingWorkPolicy.KEEP, work)
        // Periodic recovery also covers process death between commit/enqueue and an enqueue/worker-completion race.
        manager.enqueueUniquePeriodicWork("familytube-watch-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchSyncWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    suspend fun refreshHistory(library: LibraryEntity) {
        val history = api.at(library.serverUrl).history(settings.installationId()).history
        db.withTransaction {
            for ((id, item) in history) {
                val remote = ProgressEntity(library.id, id, secondsToMs(item.position), secondsToMs(item.duration), item.updatedAt)
                if (shouldMergeHistory(dao.progressFor(library.id, id), remote, dao.pendingFor(library.id, id) > 0)) dao.putProgress(remote)
            }
        }
    }

    /** Identical persisted session/sequence is reused after a retryable failure. */
    suspend fun syncPending(): Boolean = syncMutex.withLock {
        val device = settings.installationId()
        var retryNeeded = false
        val failedLibraries = mutableSetOf<String>()
        for (event in dao.pendingEvents()) {
            if (event.libraryId in failedLibraries) continue
            val library = dao.library(event.libraryId) ?: continue
            try {
                api.at(library.serverUrl).watch(device, event.toSnapshot().toDto())
                // Do not delete a newer snapshot coalesced while HTTP was in flight.
                dao.acknowledge(event.libraryId, event.sessionId, event.sequence)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is HttpException && error.code() in listOf(400, 404, 410)) {
                    Log.w("FamilyTubeSync", "Discarding rejected watch event HTTP ${error.code()}; local progress retained")
                    dao.acknowledge(event.libraryId, event.sessionId, event.sequence)
                } else {
                    Log.w("FamilyTubeSync", "Watch sync deferred: ${error.javaClass.simpleName}")
                    retryNeeded = true
                    failedLibraries += event.libraryId
                }
            }
        }
        !retryNeeded && dao.nextEvent() == null
    }
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface WatchWorkerEntryPoint { fun progressRepository(): ProgressRepository }

class WatchSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, WatchWorkerEntryPoint::class.java)
            .progressRepository()
        return try {
            if (repository.syncPending()) Result.success()
            else if (runAttemptCount < 7) Result.retry() else Result.failure()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < 7) Result.retry() else Result.failure()
        }
    }
}
