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

    @Query("SELECT * FROM songs WHERE accountId = :accountId")
    suspend fun getSongsByAccountId(accountId: Long): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(songs: List<Song>)

    @Update
    suspend fun updateAll(songs: List<Song>)

    @Update
    suspend fun update(song: Song)

    @Query("DELETE FROM songs WHERE accountId = :accountId")
    suspend fun clearByAccountId(accountId: Long)

    @Query("DELETE FROM songs WHERE accountId = :accountId AND remotePath IN (:paths)")
    suspend fun deleteByPaths(accountId: Long, paths: List<String>)

    @Query("UPDATE songs SET localPath = :localPath WHERE id = :id")
    suspend fun updateLocalPath(id: Long, localPath: String?)

    @Query("UPDATE songs SET localPath = NULL")
    suspend fun clearAllLocalPaths()

    @Query("UPDATE songs SET artworkPath = NULL")
    suspend fun clearAllArtworkPaths()
}
