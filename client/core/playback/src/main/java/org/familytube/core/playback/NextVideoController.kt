package org.familytube.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.familytube.core.model.Video

data class NextVideoState(val video: Video? = null, val secondsRemaining: Int? = null)

/** Main-thread countdown shared by phone and TV; UI recreation does not restart it. */
internal class NextVideoController(
    private val scope: CoroutineScope,
    private val onPlay: (Video) -> Unit,
    private val waitSecond: suspend () -> Unit = { delay(1_000) },
) {
    private val mutableState = MutableStateFlow(NextVideoState())
    val state = mutableState.asStateFlow()
    private var current: Video? = null
    private var ended = false
    private var cancelled = false
    private var job: Job? = null
    private var generation = 0L

    fun updatePlayback(video: Video?, isEnded: Boolean) {
        if (video?.id != current?.id || video?.libraryId != current?.libraryId) {
            reset()
            current = video
        }
        if (!isEnded) {
            cancelCountdown()
            cancelled = false
        }
        ended = isEnded
        startCountdown()
    }

    fun setCandidates(owner: Video, candidates: List<Video>) {
        val selected = current ?: return
        if (owner.id != selected.id || owner.libraryId != selected.libraryId) return
        val next = candidates.firstOrNull { it.id != selected.id && it.libraryId == selected.libraryId }
        if (next?.id != mutableState.value.video?.id) cancelCountdown()
        mutableState.value = mutableState.value.copy(video = next)
        startCountdown()
    }

    fun playNext() {
        val next = mutableState.value.video ?: return
        cancel()
        onPlay(next)
    }

    fun cancel() {
        cancelled = true
        cancelCountdown()
    }

    fun reset() {
        cancelCountdown()
        current = null
        ended = false
        cancelled = false
        mutableState.value = NextVideoState()
    }

    private fun cancelCountdown() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(secondsRemaining = null)
    }

    private fun startCountdown() {
        val next = mutableState.value.video ?: return
        if (!ended || cancelled || job != null) return
        val token = generation
        mutableState.value = mutableState.value.copy(secondsRemaining = 5)
        job = scope.launch {
            for (remaining in 4 downTo 0) {
                waitSecond()
                if (token != generation || !ended || cancelled) return@launch
                if (remaining == 0) {
                    playNext()
                    return@launch
                }
                mutableState.value = mutableState.value.copy(secondsRemaining = remaining)
            }
        }
    }
}
