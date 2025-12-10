package top.sparkfade.webdavplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.ui.components.PlaylistSelectionDialog
import top.sparkfade.webdavplayer.ui.components.SongDetailDialog
import top.sparkfade.webdavplayer.ui.components.SongListItem
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel

@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onNavigateToPlayer: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val songs by viewModel.filteredSongs.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()
    
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var songForDetails by remember { mutableStateOf<Song?>(null) }

    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("No songs")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 150.dp)
        ) {
            items(items = songs, key = { song -> song.id }) { song ->
                SongListItem(
                    song = song,
                    downloadProgress = downloadProgressMap[song.id],
                    onClick = { 
                        viewModel.playSong(song, songs)
                        onNavigateToPlayer() 
                    },
                    onDownload = { viewModel.downloadSong(song) },
                    onDelete = { viewModel.deleteLocalSong(song) },
                    onAddToPlaylist = { songForPlaylist = song },
                    onViewDetails = { songForDetails = song }
                )
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
        SongDetailDialog(
            song = songForDetails!!,
            onDismiss = { songForDetails = null }
        )
    }
}