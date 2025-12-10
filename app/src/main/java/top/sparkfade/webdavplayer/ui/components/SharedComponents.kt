package top.sparkfade.webdavplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongSelectionDialog(
    title: String = "Add to Queue",
    allSongs: List<Song>,
    disabledSongIds: Set<Long> = emptySet(),
    onSongSelected: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredList = remember(searchQuery, allSongs) {
        if (searchQuery.isBlank()) allSongs
        else allSongs.filter { 
            it.title.contains(searchQuery, true) || 
            it.artist.contains(searchQuery, true) 
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filteredList) { song ->
                        val isAdded = disabledSongIds.contains(song.id)
                        SongSelectionItem(
                            song = song,
                            isAdded = isAdded,
                            onClick = { onSongSelected(song) }
                        )
                        Divider(thickness = 0.5.dp)
                    }
                    if (filteredList.isEmpty()) {
                        item { Text("No songs found", modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// 批量添加歌曲弹窗
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchSongSelectionDialog(
    allSongs: List<Song>,
    existingSongIds: Set<Long>, // 当前歌单已有的歌曲ID
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    // 记录用户新选中的歌曲ID
    val selectedIds = remember { mutableStateListOf<Long>() }

    // 搜索过滤
    val filteredList = remember(searchQuery, allSongs) {
        if (searchQuery.isBlank()) allSongs
        else allSongs.filter {
            it.title.contains(searchQuery, true) ||
            it.artist.contains(searchQuery, true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch Add Songs") },
        text = {
            Column {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(8.dp))
                
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filteredList) { song ->
                        val isAlreadyIn = existingSongIds.contains(song.id)
                        val isSelected = selectedIds.contains(song.id)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 如果已存在，不可点击；否则点击切换勾选
                                .clickable(enabled = !isAlreadyIn) {
                                    if (isSelected) selectedIds.remove(song.id)
                                    else selectedIds.add(song.id)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .alpha(if (isAlreadyIn) 0.5f else 1f), // 已存在的半透明
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected || isAlreadyIn,
                                onCheckedChange = { checked ->
                                    if (checked) selectedIds.add(song.id) else selectedIds.remove(song.id)
                                },
                                enabled = !isAlreadyIn // 已存在的禁止取消勾选（这是添加模式，不是管理模式）
                            )
                            Spacer(Modifier.width(8.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                MarqueeText(text = song.title, style = MaterialTheme.typography.bodyLarge)
                                MarqueeText(
                                    text = song.artist, 
                                    style = MaterialTheme.typography.bodySmall, 
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            if (isAlreadyIn) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Added", 
                                    style = MaterialTheme.typography.labelSmall, 
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Divider(thickness = 0.5.dp)
                    }
                    if (filteredList.isEmpty()) {
                        item { Text("No songs found", modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(selectedIds.toList())
                    onDismiss()
                },
                enabled = selectedIds.isNotEmpty()
            ) { 
                Text("Add (${selectedIds.size})") 
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SongSelectionItem(
    song: Song,
    isAdded: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (isAdded) 0.5f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isAdded, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(text = song.title, style = MaterialTheme.typography.bodyLarge)
            MarqueeText(text = song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isAdded) {
            Spacer(Modifier.width(8.dp))
            Text("Added", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
        }
    }
}

@Composable
fun SongDetailDialog(song: Song, duration: Long = 0L, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailItem("Title", song.title)
                DetailItem("Artist", song.artist)
                DetailItem("Album", song.album)
                DetailItem("File Name", song.displayName)
                if (duration > 0) DetailItem("Duration", formatTime(duration))
                DetailItem("Size", "${song.size / 1024 / 1024} MB")
                DetailItem("Format", song.mimeType)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun DetailItem(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        MarqueeText(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
fun PlaylistSelectionDialog(song: Song, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val playlistsWithStatus by viewModel.getPlaylistsWithStatus(song.id).collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlists") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(playlistsWithStatus) { (playlist, isAdded) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleSongInPlaylist(playlist.id, song.id, !isAdded) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (playlist.id == 1L) {
                                Icon(
                                    imageVector = if (isAdded) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Checkbox(checked = isAdded, onCheckedChange = { checked -> viewModel.toggleSongInPlaylist(playlist.id, song.id, checked) })
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            MarqueeText(text = playlist.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (playlistsWithStatus.isEmpty()) item { Text("No playlists available.") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun SongListItem(
    song: Song,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onViewDetails: () -> Unit,
    downloadProgress: Float? = null 
) {
    val isDownloaded = song.localPath != null
    var menuExpanded by remember { mutableStateOf(false) }

    Column {
        ListItem(
            headlineContent = { 
                MarqueeText(text = song.title, style = MaterialTheme.typography.bodyLarge) 
            },
            supportingContent = { 
                Column {
                    MarqueeText(text = song.artist, style = MaterialTheme.typography.bodySmall)
                    
                    if (downloadProgress != null) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = downloadProgress,
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = if (isDownloaded) "Downloaded" else "Remote",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            },
            trailingContent = {
                if (downloadProgress == null) {
                    Box(modifier = Modifier.offset(x = 8.dp)) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Options")
                        }
                        
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (isDownloaded) {
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Download") },
                                    leadingIcon = { Icon(Icons.Default.Download, null) },
                                    onClick = {
                                        menuExpanded = false
                                        onDownload()
                                    }
                                )
                            }
                            
                            DropdownMenuItem(
                                text = { Text("Add to Playlist") },
                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                                onClick = {
                                    menuExpanded = false
                                    onAddToPlaylist()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Details") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = {
                                    menuExpanded = false
                                    onViewDetails()
                                }
                            )
                        }
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            },
            modifier = Modifier.clickable { onClick() }
        )
        Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}