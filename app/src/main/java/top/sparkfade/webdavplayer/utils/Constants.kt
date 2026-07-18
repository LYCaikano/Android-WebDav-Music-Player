package top.sparkfade.webdavplayer.utils

object Constants {
    const val DATABASE_NAME = "webdav_player_db"
    const val MAX_SCAN_DEPTH = 10

    const val USER_AGENT = "WebDavPlayer/1.0 (Android)"

    const val QUEUE_PERSIST_LIMIT = 500

    const val PLAYLIST_ID_FAVORITES = 1L
    const val PLAYLIST_ID_DOWNLOADS = 2L
    const val PLAYLIST_ID_QUEUE = 3L

    const val PREF_THEME_MODE = "theme_mode"
    const val PREF_LAST_SONG_ID = "last_song_id"
    const val PREF_LAST_POSITION = "last_pos"

    val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus"
    )
}
