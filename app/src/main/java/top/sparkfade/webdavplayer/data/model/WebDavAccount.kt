package top.sparkfade.webdavplayer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class WebDavAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val skipSsl: Boolean,
    val scanDepth: Int = 4
)