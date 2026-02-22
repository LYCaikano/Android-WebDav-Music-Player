package top.sparkfade.webdavplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

/**
 * 播放列表 Bottom Sheet 组件。 PlayerScreen / MainScreen / DetailScreen 共用。
 *
 * @param viewModel 主 ViewModel，用于获取 playlist、currentSong 和操作队列
 * @param onDismiss 关闭 Sheet 的回调
 * @param onAddSong 可选，传入时在标题栏右侧显示「+」按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistBottomSheet(
        viewModel: MainViewModel,
        onDismiss: () -> Unit,
        onAddSong: (() -> Unit)? = null
) {
    val playlist by viewModel.currentPlaylist.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val listState = rememberLazyListState()

    // 打开时自动滚动到当前播放歌曲
    LaunchedEffect(Unit) {
        val index = playlist.indexOfFirst { it.id == currentSong?.id }
        if (index != -1) listState.scrollToItem(index)
    }

    ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f)) {
            // 标题栏
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                        text = "Current Queue (${playlist.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center)
                )

                if (onAddSong != null) {
                    IconButton(
                            onClick = onAddSong,
                            modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)
                    ) { Icon(Icons.Default.Add, "Add Song") }
                }
            }

            Divider()

            if (playlist.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Queue is empty")
                }
            } else {
                LazyColumn(
                        state = listState,
                        contentPadding =
                                PaddingValues(
                                        bottom =
                                                16.dp +
                                                        WindowInsets.navigationBars
                                                                .asPaddingValues()
                                                                .calculateBottomPadding()
                                ),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(playlist.size) { index ->
                        val song = playlist[index]
                        val isSelected = song.id == currentSong?.id

                        ListItem(
                                leadingContent = {
                                    Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color =
                                                    if (isSelected)
                                                            MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(30.dp),
                                            textAlign = TextAlign.Center
                                    )
                                },
                                headlineContent = {
                                    Text(
                                            text = song.title,
                                            maxLines = 1,
                                            color =
                                                    if (isSelected)
                                                            MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                supportingContent = { Text(song.artist, maxLines = 1) },
                                trailingContent = {
                                    if (!isSelected) {
                                        IconButton(onClick = { viewModel.removeFromQueue(song) }) {
                                            Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove",
                                                    modifier = Modifier.size(20.dp),
                                                    tint =
                                                            MaterialTheme.colorScheme
                                                                    .onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { viewModel.skipToQueueItem(index) }
                        )
                    }
                }
            }
        }
    }
}
