package top.sparkfade.webdavplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

@HiltAndroidApp
class WebDavPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        cleanupStaleTempFiles()
    }

    // 清理上次运行残留的下载/扫描临时文件
    private fun cleanupStaleTempFiles() {
        try {
            cacheDir.listFiles { f -> f.name.startsWith("scan_") }?.forEach(File::delete)
            File(cacheDir, "covers").listFiles { f -> f.name.startsWith("temp_") }
                ?.forEach(File::delete)
            getExternalFilesDir("music_downloads")
                ?.listFiles { f -> f.name.endsWith(".tmp") }
                ?.forEach(File::delete)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
