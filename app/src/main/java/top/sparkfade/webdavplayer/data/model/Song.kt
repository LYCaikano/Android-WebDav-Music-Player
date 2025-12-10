package top.sparkfade.webdavplayer.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "songs",
    indices = [Index(value = ["accountId", "remotePath"], unique = true)]
)
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val remotePath: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkPath: String? = null,
    val size: Long,
    val mimeType: String,
    val localPath: String? = null,
    val isCached: Boolean = false,
    val isMetadataVerified: Boolean = false
) : Parcelable {
    fun getPlayableUrl(): String {
        return localPath ?: remotePath
    }
}