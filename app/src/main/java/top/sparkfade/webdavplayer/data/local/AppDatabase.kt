package top.sparkfade.webdavplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import top.sparkfade.webdavplayer.data.model.Playlist
import top.sparkfade.webdavplayer.data.model.PlaylistSongCrossRef
import top.sparkfade.webdavplayer.data.model.Song
import top.sparkfade.webdavplayer.data.model.WebDavAccount

@Database(
    entities = [Song::class, WebDavAccount::class, Playlist::class, PlaylistSongCrossRef::class],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun accountDao(): WebDavAccountDao
    abstract fun playlistDao(): PlaylistDao
}
