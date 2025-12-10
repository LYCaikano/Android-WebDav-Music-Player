package top.sparkfade.webdavplayer.utils

object Constants {
    const val DATABASE_NAME = "webdav_player.db"
    const val MAX_SCAN_DEPTH = 5
    
    // 支持的音频格式后缀
    val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus"
    )
}