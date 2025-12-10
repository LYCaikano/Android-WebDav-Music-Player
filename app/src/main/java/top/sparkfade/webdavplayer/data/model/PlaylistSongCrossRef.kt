package top.sparkfade.webdavplayer.data.model

import androidx.room.Entity

@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val addedAt: Long = System.currentTimeMillis()
)