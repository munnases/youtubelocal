package org.familytube.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.familytube.core.data.CatalogState
import org.familytube.core.data.ProgressEntity
import org.familytube.core.designsystem.FamilyColors
import org.familytube.core.model.Video
import org.familytube.core.model.resumePositionMs

@Composable
internal fun PhoneCatalog(state: CatalogState, tab: String, onTab: (String) -> Unit,
    homeScroll: LazyListState, libraryScroll: LazyListState, continueScroll: LazyListState,
    onRefresh: () -> Unit, onSettings: () -> Unit, onQuery: (String) -> Unit,
    onCategory: (String) -> Unit, onSelect: (Video) -> Unit) {
    var searching by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = searching || state.query.isNotBlank()) { onQuery(""); searching = false }
    Scaffold(containerColor = FamilyColors.background, bottomBar = {
        NavigationBar(containerColor = FamilyColors.background) {
            for ((name, icon) in listOf("Home" to Glyph.HOME, "Library" to Glyph.LIBRARY)) {
                NavigationBarItem(selected = tab == name, onClick = { onTab(name) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = FamilyColors.accent.copy(alpha = .15f)),
                    icon = { FamilyIcon(icon, color = if (tab == name) FamilyColors.accent else FamilyColors.muted) }, label = { Text(name) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = FamilyColors.accent, shape = RoundedCornerShape(7.dp)) { FamilyIcon(Glyph.PLAY, Modifier.padding(6.dp)) }
                Text("FamilyTube", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                ActionIcon("Search", Glyph.SEARCH, { searching = true })
                ActionIcon("Refresh library", Glyph.REFRESH, onRefresh, enabled = !state.loading)
                ActionIcon("Server", Glyph.SERVER, onSettings)
            }
            if (searching || state.query.isNotBlank()) {
                OutlinedTextField(state.query, onQuery, singleLine = true, label = { Text("Search your library") },
                    trailingIcon = { ActionIcon("Clear search", Glyph.CLOSE, { onQuery(""); searching = false }) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search"))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CategoryChip("All", state.category.isBlank()) { onCategory("") } }
                items(state.categories, key = { it }) { category ->
                    CategoryChip(category, state.category == category) { onCategory(category) }
                }
            }
            LazyColumn(state = if (tab == "Home") homeScroll else libraryScroll,
                modifier = Modifier.weight(1f).testTag("catalog"), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading) item(key = "loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                    Text("Updating library…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = FamilyColors.muted)
                }
                if (state.error != null) item(key = "error") {
                    Surface(Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(12.dp), color = FamilyColors.surface) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Server unavailable", style = MaterialTheme.typography.titleSmall)
                            Text(if (state.allVideos.isEmpty()) "Connect to your home Wi-Fi and check the server address."
                                else "Your saved library is available. Reconnect to stream videos.", color = FamilyColors.muted)
                            TextButton(onClick = onRefresh) { Text("Retry") }
                        }
                    }
                }
                val continuing = state.videos.filter { video -> state.progress[video.id]?.let { resumePositionMs(it.positionMs, it.durationMs) > 0 } == true }
                    .sortedByDescending { state.progress[it.id]?.updatedAtEpochMs }
                if (tab == "Home" && continuing.isNotEmpty()) {
                    item(key = "continue") {
                        Column {
                            Text("Continue watching", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                            LazyRow(state = continueScroll, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(continuing, key = { it.id }) { video -> VideoCard(video, state.progress[video.id], { onSelect(video) }, Modifier.width(240.dp)) }
                            }
                        }
                    }
                }
                item(key = "heading") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (tab == "Home") "From your library" else "All videos", style = MaterialTheme.typography.titleMedium)
                        Text("${state.videos.size} ${if (state.videos.size == 1) "video" else "videos"}", style = MaterialTheme.typography.bodySmall, color = FamilyColors.muted)
                    }
                }
                if (!state.loading && state.videos.isEmpty()) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FamilyIcon(Glyph.LIBRARY, Modifier.size(48.dp), FamilyColors.muted)
                        Text(if (state.query.isNotBlank() || state.category.isNotBlank()) "No matching videos"
                            else if (state.error == null) "Your library is empty" else "No saved library yet",
                            Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                        Text(if (state.query.isNotBlank() || state.category.isNotBlank()) "Try another title or category."
                            else "Add videos to your home server, then refresh.", color = FamilyColors.muted)
                    }
                }
                items(state.videos, key = { "video:${it.id}" }, contentType = { "video" }) { video ->
                    VideoCard(video, state.progress[video.id], { onSelect(video) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FamilyColors.text, selectedLabelColor = FamilyColors.background))
}

@Composable
internal fun VideoCard(video: Video, saved: ProgressEntity?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val resume = saved?.let { resumePositionMs(it.positionMs, it.durationMs) } ?: 0
    Column(modifier.clickable(onClickLabel = "Play ${video.title}", onClick = onClick).testTag("video:${video.id}")) {
        Thumbnail(video, Modifier.fillMaxWidth(), if (saved != null && saved.durationMs > 0) saved.positionMs.toFloat() / saved.durationMs else 0f)
        Text(video.title, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Text(video.category.ifBlank { "Family video" } + if (resume > 0) " · Resume ${formatTime(resume)}" else "",
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = FamilyColors.muted)
    }
}
