package org.familytube.core.playback

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.familytube.core.model.*

/** Ordered application-lifetime writes survive a watch screen/ViewModel being destroyed. */
@Singleton
class PlaybackPersistence @Inject constructor(private val store: ProgressStore) {
    private sealed interface Command {
        data class Save(val snapshot: WatchSnapshot) : Command
        data class Position(val library: String, val video: String, val result: CompletableDeferred<Long>) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Save -> store.save(command.snapshot)
                        is Command.Position -> command.result.complete(store.resumePositionMs(command.library, command.video))
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e("FamilyTubeProgress", "Local progress operation failed", error)
                    if (command is Command.Position) command.result.complete(0)
                }
            }
        }
    }
    fun save(snapshot: WatchSnapshot) { commands.trySend(Command.Save(snapshot)) }
    suspend fun position(video: Video): Long {
        val result = CompletableDeferred<Long>()
        commands.send(Command.Position(video.libraryId, video.id, result))
        return result.await()
    }
}

/** Monotonic playing time excludes paused/buffering intervals and seek distance. */
internal class WatchSession(val id: String, nowMs: Long) {
    var sequence: Long = 0
        private set
    var watchedMs: Long = 0
        private set
    var playing: Boolean = false
    private var lastMs = nowMs
    fun sample(nowMs: Long) {
        if (playing) watchedMs += (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
    }
    fun nextSequence(): Long = ++sequence
}
