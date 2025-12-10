package top.sparkfade.webdavplayer.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import top.sparkfade.webdavplayer.data.model.WebDavAccount

@Dao
interface WebDavAccountDao {
    @Query("SELECT * FROM accounts")
    fun getAllAccounts(): Flow<List<WebDavAccount>>

    @Query("SELECT * FROM accounts")
    suspend fun getAllAccountsList(): List<WebDavAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: WebDavAccount): Long

    @Update
    suspend fun update(account: WebDavAccount)

    @Delete
    suspend fun delete(account: WebDavAccount)
}