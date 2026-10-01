package org.familytube.core.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.familytube.core.model.Video

@HiltViewModel
class PlaybackViewModel @Inject constructor(private val coordinator: PlaybackCoordinator) : ViewModel() {
    val state = coordinator.state
    val player = coordinator.player
    private var positionJob: Job? = null

    fun play(video: Video) {
        coordinator.play(video)
        startPositionUpdates()
    }

    fun resume() {
        coordinator.resume()
        startPositionUpdates()
    }

    fun pause() = coordinator.pause()
    fun seekTo(positionMs: Long) = coordinator.seekTo(positionMs)

    fun stop() {
        positionJob?.cancel()
        coordinator.stop()
    }

    fun onBackground() {
        positionJob?.cancel()
        coordinator.releaseForBackground()
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                coordinator.samplePosition()
                delay(250)
            }
        }
    }

    override fun onCleared() {
        coordinator.release()
    }
}
