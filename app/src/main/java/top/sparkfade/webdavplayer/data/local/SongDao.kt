package top.sparkfade.webdavplayer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import top.sparkfade.webdavplayer.data.model.Song

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT remotePath FROM songs WHERE accountId = :accountId")
    suspend fun getRemotePathsByAccountId(accountId: Long): List<String>

    @Query("SELECT * FROM songs WHERE accountId = :accountId")
    suspend fun getSongsByAccountId(accountId: Long): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE accountId = :accountId AND remotePath = :path LIMIT 1")
    suspend fun getSongByPath(accountId: Long, path: String): Song?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(songs: List<Song>)

    @Update
    suspend fun updateAll(songs: List<Song>)

    @Update
    suspend fun update(song: Song)

    @Query("DELETE FROM songs")
    suspend fun clearAll()

    @Query("DELETE FROM songs WHERE accountId = :accountId")
    suspend fun clearByAccountId(accountId: Long)

    @Query("DELETE FROM songs WHERE accountId = :accountId AND remotePath = :path")
    suspend fun deleteByPath(accountId: Long, path: String)

    @Query("UPDATE songs SET localPath = NULL")
    suspend fun clearAllLocalPaths()

    @Query("UPDATE songs SET artworkPath = NULL")
    suspend fun clearAllArtworkPaths()
}