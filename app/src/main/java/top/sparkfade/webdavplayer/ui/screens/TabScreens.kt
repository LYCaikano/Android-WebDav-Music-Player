package top.sparkfade.webdavplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import top.sparkfade.webdavplayer.data.model.Playlist
import top.sparkfade.webdavplayer.ui.components.MarqueeText
import top.sparkfade.webdavplayer.ui.viewmodel.MainViewModel
import java.io.File

@Composable
fun GridItemCard(
    title: String,
    subtitle: String,
    imagePath: String? = null,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
    isMenuExpanded: Boolean = false,
    onDismissMenu: () -> Unit = {},
    menuContent: @Composable (ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth() 
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. 封面区域 (不变)
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imagePath != null) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    fallbackIcon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .widthIn(max = 160.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // 居中显示文字，左右留出足够空间以免覆盖按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MarqueeText(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                MarqueeText(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // 菜单按钮
            if (onMoreClick != null) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = onMoreClick, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(16.dp))
                    }
                    
                    if (menuContent != null) {
                        DropdownMenu(
                            expanded = isMenuExpanded,
                            onDismissRequest = onDismissMenu,
                            content = menuContent
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumsPage(viewModel: MainViewModel, onAlbumClick: (String) -> Unit) {
    val albums by viewModel.albums.collectAsState()
    if (albums.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Albums") } } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(albums) { album -> GridItemCard(album.name, "${album.count} Songs", album.artPath, Icons.Default.Album, { onAlbumClick(album.name) }) }
        }
    }
}

@Composable
fun ArtistsPage(viewModel: MainViewModel, onArtistClick: (String) -> Unit) {
    val artists by viewModel.artists.collectAsState()
    if (artists.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Artists") } } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(artists) { artist -> GridItemCard(artist.name, "${artist.count} Songs", null, Icons.Default.Person, { onArtistClick(artist.name) }) }
        }
    }
}

@Composable
fun PlaylistsPage(viewModel: MainViewModel, onPlaylistClick: (String) -> Unit) {
    val playlists by viewModel.playlists.collectAsState()
    var activePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    
    if (playlists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Playlists") }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(playlists) { playlist ->
                var menuExpanded by remember { mutableStateOf(false) }
                
                GridItemCard(
                    title = playlist.name,
                    subtitle = if(playlist.isSystem) "System" else "User",
                    fallbackIcon = Icons.Default.MusicNote,
                    onClick = { onPlaylistClick(playlist.id.toString()) },
                    onMoreClick = if (!playlist.isSystem) { { menuExpanded = true } } else null,
                    isMenuExpanded = menuExpanded,
                    onDismissMenu = { menuExpanded = false },
                    menuContent = {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                menuExpanded = false
                                activePlaylist = playlist
                                showRenameDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                viewModel.deletePlaylist(playlist)
                            }
                        )
                    }
                )
            }
        }
    }

    if (showRenameDialog && activePlaylist != null) {
        var newName by remember { mutableStateOf(activePlaylist!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist") },
            text = { 
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true) 
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) { viewModel.renamePlaylist(activePlaylist!!, newName); showRenameDialog = false }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }
}