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
    // 保留列以避免破坏性迁移；离线可用性请以 localPath != null 判断
    val isCached: Boolean = false,
    val isMetadataVerified: Boolean = false
) : Parcelable