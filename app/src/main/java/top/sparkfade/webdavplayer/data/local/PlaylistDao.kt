package top.sparkfade.webdavplayer.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import top.sparkfade.webdavplayer.data.model.Playlist
import top.sparkfade.webdavplayer.data.model.PlaylistSongCrossRef
import top.sparkfade.webdavplayer.data.model.Song

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    // [新增] 批量添加 (用于保存播放队列)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongCrossRefs(crossRefs: List<PlaylistSongCrossRef>)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId 
        ORDER BY playlist_song_cross_ref.addedAt ASC
    """)
    fun getSongsForPlaylist(playlistId: Long): Flow<List<Song>>

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId 
        ORDER BY playlist_song_cross_ref.addedAt ASC
    """)
    suspend fun getSongsForPlaylistSync(playlistId: Long): List<Song>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)")
    fun isSongInPlaylist(playlistId: Long, songId: Long): Flow<Boolean>

    @Query("SELECT playlistId FROM playlist_song_cross_ref WHERE songId = :songId")
    fun getPlaylistIdsForSong(songId: Long): Flow<List<Long>>
}