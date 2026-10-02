package org.familytube.core.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.familytube.core.model.Video

data class CatalogState(
    val serverUrl: String = "",
    val settingsLoaded: Boolean = false,
    val videos: List<Video> = emptyList(),
    val allVideos: List<Video> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val libraryId: String = "",
    val query: String = "",
    val category: String = "",
    val categories: List<String> = emptyList(),
    val progress: Map<String, ProgressEntity> = emptyMap(),
)

fun filterCatalog(videos: List<Video>, query: String, category: String): List<Video> = videos.filter {
    it.title.contains(query.trim(), ignoreCase = true) && (category.isBlank() || it.category == category)
}

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val settings: ServerSettingsRepository,
    private val db: LibraryDatabase,
    private val progress: ProgressRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var library: LibraryEntity? = null
    private var allVideos: List<Video> = emptyList()

    init {
        viewModelScope.launch {
            progress.scheduleSync()
            settings.serverUrl.collectLatest { url ->
                loadJob?.cancel()
                library = null
                allVideos = emptyList()
                mutableState.value = CatalogState(serverUrl = url, settingsLoaded = true)
                if (url.isBlank()) return@collectLatest
                val selected = repository.libraryAt(url)
                library = selected
                mutableState.value = mutableState.value.copy(libraryId = selected.id)
                coroutineScope {
                    launch {
                        combine(repository.observe(selected), db.dao().progress(selected.id)) { videos, saved -> videos to saved }
                            .collect { (videos, saved) ->
                                allVideos = videos
                                val state = mutableState.value
                                mutableState.value = state.copy(videos = filterCatalog(videos, state.query, state.category),
                                    allVideos = videos,
                                    categories = videos.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
                                    progress = saved.associateBy { it.videoId })
                            }
                    }
                    refresh()
                    awaitCancellation()
                }
            }
        }
    }

    fun setQuery(query: String) {
        mutableState.value = mutableState.value.copy(query = query,
            videos = filterCatalog(allVideos, query, mutableState.value.category))
    }

    suspend fun related(video: Video): List<Video> = repository.related(video)
    fun setCategory(category: String) {
        mutableState.value = mutableState.value.copy(category = category,
            videos = filterCatalog(allVideos, mutableState.value.query, category))
    }

    fun onForeground() {
        val selected = library ?: return
        viewModelScope.launch {
            val latest = db.dao().library(selected.id) ?: return@launch
            if (System.currentTimeMillis() - latest.refreshedAtEpochMs > 60_000 && library?.id == latest.id) refresh()
            progress.scheduleSync()
        }
    }

    fun refresh() {
        val selected = library ?: return
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                repository.refresh(selected)
                if (library?.id == selected.id) mutableState.value = mutableState.value.copy(loading = false)
                launch {
                    try { progress.refreshHistory(selected) }
                    catch (error: Exception) { if (error is CancellationException) throw error }
                }
                launch {
                    try { repository.refreshRecommendations(selected, settings.installationId()) }
                    catch (error: Exception) { if (error is CancellationException) throw error }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (library?.id == selected.id) mutableState.value = mutableState.value.copy(loading = false,
                    error = error.localizedMessage ?: "Server unavailable")
            }
        }
    }
}
