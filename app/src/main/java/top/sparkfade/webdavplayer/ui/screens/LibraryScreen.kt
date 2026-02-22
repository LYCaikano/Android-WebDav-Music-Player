package top.sparkfade.webdavplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.min
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.ui.components.PlaylistSelectionDialog
import top.sparkfade.webdavplayer.ui.components.SongDetailDialog
import top.sparkfade.webdavplayer.ui.components.SongListItem
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

private const val PAGE_SIZE = 20

@Composable
fun LibraryScreen(viewModel: MainViewModel, onNavigateToPlayer: () -> Unit) {
    val allFilteredSongs by viewModel.filteredSongs.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()

    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var songForDetails by remember { mutableStateOf<Song?>(null) }

    var displayCount by remember { mutableIntStateOf(PAGE_SIZE) }

    // 搜索词变化时重置分页
    val searchQuery by viewModel.searchQuery.collectAsState()
    LaunchedEffect(searchQuery) { displayCount = PAGE_SIZE }

    val totalSize = allFilteredSongs.size

    if (totalSize == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No songs") }
    } else {
        val listState = rememberLazyListState()
        val visibleCount = min(displayCount, totalSize)
        val hasMore = displayCount < totalSize

        // derivedStateOf 高效追踪是否到达列表底部
        val reachedBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems == 0) return@derivedStateOf false
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= totalItems - 3
            }
        }

        // 当到达底部且还有更多数据时，自动加载下一页
        // key = displayCount 保证每次加载后会重新评估，防止重复触发
        if (reachedBottom && hasMore) {
            LaunchedEffect(displayCount) {
                displayCount = (displayCount + PAGE_SIZE).coerceAtMost(totalSize)
            }
        }

        LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 150.dp)
        ) {
            items(count = visibleCount, key = { index -> allFilteredSongs[index].id }) { index ->
                val song = allFilteredSongs[index]
                SongListItem(
                        song = song,
                        downloadProgress = downloadProgressMap[song.id],
                        onClick = {
                            viewModel.playSong(song, allFilteredSongs)
                            onNavigateToPlayer()
                        },
                        onDownload = { viewModel.downloadSong(song) },
                        onDelete = { viewModel.deleteLocalSong(song) },
                        onAddToPlaylist = { songForPlaylist = song },
                        onViewDetails = { songForDetails = song }
                )
            }

            if (hasMore) {
                item {
                    Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }

    if (songForPlaylist != null) {
        PlaylistSelectionDialog(
                song = songForPlaylist!!,
                viewModel = viewModel,
                onDismiss = { songForPlaylist = null }
        )
    }

    if (songForDetails != null) {
        SongDetailDialog(song = songForDetails!!, onDismiss = { songForDetails = null })
    }
}
