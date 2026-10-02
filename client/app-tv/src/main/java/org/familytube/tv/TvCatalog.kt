package org.familytube.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.familytube.core.data.CatalogState
import org.familytube.core.designsystem.FamilyColors
import org.familytube.core.model.Video
import org.familytube.core.model.resumePositionMs

/** Owned above the watch route so returning retains both axes and the exact card. */
internal class TvBrowseMemory(val verticalState: LazyListState = LazyListState()) {
    var page by mutableStateOf("Home")
    var row = ""
    var video = ""
    var library = ""
    val horizontal = mutableStateMapOf<String, LazyListState>()
    companion object {
        val Saver = listSaver<TvBrowseMemory, Any>(save = {
            listOf(it.page, it.row, it.video, it.library, it.verticalState.firstVisibleItemIndex,
                it.verticalState.firstVisibleItemScrollOffset) + it.horizontal.flatMap { (id, state) ->
                listOf(id, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }
        }, restore = { values -> TvBrowseMemory(LazyListState(values[4] as Int, values[5] as Int)).apply {
            page = values[0] as String; row = values[1] as String; video = values[2] as String; library = values[3] as String
            values.drop(6).chunked(3).forEach { horizontal[it[0] as String] = LazyListState(it[1] as Int, it[2] as Int) }
        } })
    }
}

private data class BrowseRow(val id: String, val title: String, val videos: List<Video>)

@Composable
internal fun TvCatalogScreen(state: CatalogState, memory: TvBrowseMemory, onRefresh: () -> Unit,
    onSettings: () -> Unit, onQuery: (String) -> Unit, onCategory: (String) -> Unit, onSelect: (Video) -> Unit) {
    val scope = rememberCoroutineScope()
    val inputMode = LocalInputModeManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigation = remember { List(5) { FocusRequester() } }
    val search = remember { FocusRequester() }
    val clear = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    val cards = remember { mutableMapOf<String, FocusRequester>() }
    val navIndex = listOf("Home", "Library", "Search").indexOf(memory.page).coerceAtLeast(0)
    val rows = remember(state.videos, state.progress, memory.page) {
        buildList {
            if (memory.page == "Home" && state.query.isBlank() && state.category.isBlank()) {
                val continuing = state.videos.filter { v -> state.progress[v.id]?.let {
                    resumePositionMs(it.positionMs, it.durationMs) > 0
                } == true }.sortedByDescending { state.progress[it.id]?.updatedAtEpochMs ?: 0 }
                if (continuing.isNotEmpty()) add(BrowseRow("continue", "Continue watching", continuing))
            }
            state.videos.groupBy { it.category.ifBlank { "Videos" } }.forEach { (category, videos) ->
                add(BrowseRow("category:$category", category, videos))
            }
        }
    }
    var initialFocusRestored by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    suspend fun focusContent(): Boolean {
        inputMode.requestInputMode(InputMode.Keyboard)
        val row = rows.firstOrNull { it.id == memory.row && it.videos.any { it.id == memory.video } }
            ?: rows.firstOrNull()
        if (row == null) return navigation[navIndex].requestFocus()
        val target = row.videos.firstOrNull { it.id == memory.video } ?: row.videos.first()
        val rowIndex = rows.indexOf(row)
        val column = memory.horizontal.getOrPut(row.id) { LazyListState() }
        if (memory.verticalState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) memory.verticalState.scrollToItem(rowIndex)
        val cardIndex = row.videos.indexOf(target)
        if (column.layoutInfo.visibleItemsInfo.none { it.index == cardIndex }) column.scrollToItem(cardIndex)
        // Wait for lazy targets to attach; never request focus on catalog/progress refreshes.
        repeat(5) {
            withFrameNanos { }
            val result = cards["${row.id}/${target.id}"]?.requestFocus()
            if (result == true) return true
        }
        navigation[navIndex].requestFocus()
        return false
    }
    // History may insert a row during initial loading. Retry against its current keys until
    // focus succeeds, then stop reacting to catalog/progress updates for this screen visit.
    val restoreKey = if (initialFocusRestored) null else rows.map { it.id to it.videos.map(Video::id) }
    LaunchedEffect(state.libraryId, restoreKey, windowFocused) {
        if (state.libraryId.isNotBlank() && memory.library != state.libraryId) {
            val changingLibrary = memory.library.isNotBlank()
            memory.library = state.libraryId
            if (changingLibrary) {
                memory.row = ""; memory.video = ""; memory.horizontal.clear()
                memory.verticalState.scrollToItem(0)
                withFrameNanos { }
            }
            initialFocusRestored = false
        }
        if (!initialFocusRestored && windowFocused) {
            if (memory.page == "Search") initialFocusRestored = search.requestFocus()
            else if (rows.isNotEmpty()) initialFocusRestored = focusContent()
            else navigation[navIndex].requestFocus()
        }
    }
    BackHandler(enabled = memory.page != "Home") {
        onQuery(""); onCategory(""); memory.page = "Home"; navigation[0].requestFocus()
    }
    fun searchKey(event: KeyEvent): Boolean = when (event.key) {
        Key.DirectionDown -> { if (event.type == KeyEventType.KeyDown) { keyboard?.hide(); results.requestFocus() }; true }
        Key.DirectionUp -> { if (event.type == KeyEventType.KeyDown) { keyboard?.hide(); navigation[2].requestFocus() }; true }
        else -> false
    }
    LaunchedEffect(memory.page) {
        if (memory.page == "Search") { withFrameNanos { }; search.requestFocus() }
    }
    Row(Modifier.fillMaxSize().background(FamilyColors.background).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(116.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("FamilyTube", color = FamilyColors.accent, fontSize = 19.sp)
            Spacer(Modifier.height(18.dp))
            listOf("Home", "Library", "Search", "Server", "Refresh").forEachIndexed { index, label ->
                Button(onClick = {
                    when (label) {
                        "Server" -> onSettings()
                        "Refresh" -> onRefresh()
                        else -> { memory.page = label; if (label != "Search") { onQuery(""); onCategory("") } }
                    }
                }, modifier = Modifier.fillMaxWidth().focusRequester(navigation[index]).onPreviewKeyEvent {
                    if (it.key == Key.DirectionRight) {
                        if (it.type == KeyEventType.KeyDown) scope.launch {
                            if (memory.page == "Search") search.requestFocus() else focusContent()
                        }
                        true
                    } else false
                }, colors = ButtonDefaults.colors(containerColor = if (memory.page == label) FamilyColors.accent.copy(alpha = .25f) else FamilyColors.surface)) {
                    Text(label, fontSize = 16.sp)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(memory.page, style = MaterialTheme.typography.headlineMedium)
            Text("${state.videos.size} family videos", color = FamilyColors.muted)
            if (memory.page == "Search") {
                var focused by remember { mutableStateOf(false) }
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(state.query, onQuery, singleLine = true,
                        textStyle = TextStyle(color = FamilyColors.text, fontSize = 20.sp), cursorBrush = SolidColor(FamilyColors.accent),
                        modifier = Modifier.weight(1f).focusRequester(search).onPreInterceptKeyBeforeSoftKeyboard(::searchKey)
                            .onPreviewKeyEvent(::searchKey).onFocusChanged { focused = it.isFocused }.border(2.dp, if (focused) FamilyColors.accent else FamilyColors.muted, RoundedCornerShape(8.dp)).padding(12.dp),
                        decorationBox = { inner -> if (state.query.isEmpty()) Text("Search titles", color = FamilyColors.muted); inner() })
                }
            }
            if (memory.page == "Search") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Button(onClick = { scope.launch { focusContent() } }, modifier = Modifier.focusRequester(results)
                        .focusProperties { up = search; left = navigation[2]; right = clear }.onPreviewKeyEvent {
                            if (it.key == Key.DirectionDown) { if (it.type == KeyEventType.KeyDown) scope.launch { focusContent() }; true } else false
                        }) { Text("View results") }
                    Button(onClick = { onQuery(""); search.requestFocus() }, modifier = Modifier.focusRequester(clear)
                        .focusProperties { up = search; left = results }.onPreviewKeyEvent {
                            if (it.key == Key.DirectionDown) { if (it.type == KeyEventType.KeyDown) scope.launch { focusContent() }; true } else false
                        }) { Text("Clear") }
                }
            }
            if (state.loading) Text("Refreshing library…", color = FamilyColors.muted)
            if (state.error != null) Text("Server unavailable. Saved library remains usable. Choose Refresh to retry.", color = FamilyColors.muted)
            if (!state.loading && rows.isEmpty()) Text(if (state.query.isNotBlank()) "No matching videos." else "No saved videos yet.", modifier = Modifier.padding(top = 24.dp))
            LazyColumn(state = memory.verticalState, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f).testTag("browse-rows")) {
                itemsIndexed(rows, key = { _, row -> row.id }) { rowIndex, row ->
                    Column {
                        Text(row.title, fontSize = 21.sp, modifier = Modifier.padding(start = 12.dp, top = 16.dp))
                        LazyRow(state = memory.horizontal.getOrPut(row.id) { LazyListState() }, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            itemsIndexed(row.videos, key = { _, v -> v.id }) { cardIndex, video ->
                                val id = "${row.id}/${video.id}"
                                val focus = remember(id) { FocusRequester() }
                                DisposableEffect(id) { cards[id] = focus; onDispose { cards.remove(id) } }
                                TvVideoCard(video, state.progress[video.id]?.let { resumePositionMs(it.positionMs, it.durationMs) } ?: 0,
                                    Modifier.focusRequester(focus).focusProperties {
                                        if (cardIndex == 0) left = navigation[navIndex]
                                        if (cardIndex == row.videos.lastIndex) right = FocusRequester.Cancel
                                        if (rowIndex == rows.lastIndex) down = FocusRequester.Cancel
                                        if (rowIndex == 0) up = if (memory.page == "Search") search else navigation[navIndex]
                                    }.onFocusChanged { if (it.isFocused) { memory.row = row.id; memory.video = video.id } }
                                        .testTag("card:$id"), onSelect)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvVideoCard(video: Video, resumeMs: Long = 0, modifier: Modifier = Modifier, onSelect: (Video) -> Unit) {
    Button(onClick = { onSelect(video) }, modifier = modifier.width(210.dp).height(192.dp),
        contentPadding = PaddingValues(8.dp), scale = ButtonDefaults.scale(focusedScale = 1.04f),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(12.dp))),
        colors = ButtonDefaults.colors(containerColor = FamilyColors.surface, contentColor = FamilyColors.text,
            focusedContainerColor = FamilyColors.accent, focusedContentColor = FamilyColors.text)) {
        Column {
            Box(Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(8.dp)).background(FamilyColors.background), contentAlignment = Alignment.Center) {
                Text("▶", fontSize = 32.sp, color = FamilyColors.muted)
                AsyncImage(video.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (video.durationSeconds > 0) Text(formatTime((video.durationSeconds * 1000).toLong()), color = FamilyColors.text,
                    modifier = Modifier.align(Alignment.BottomEnd).background(FamilyColors.background).padding(4.dp))
            }
            Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
            Text(if (resumeMs > 0) "Resume ${formatTime(resumeMs)}" else video.category.ifBlank { "Video" },
                maxLines = 1, fontSize = 14.sp)
        }
    }
}
