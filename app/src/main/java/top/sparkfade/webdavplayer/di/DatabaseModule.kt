package top.sparkfade.webdavplayer.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import top.sparkfade.webdavplayer.data.local.AppDatabase
import top.sparkfade.webdavplayer.data.local.SongDao
import top.sparkfade.webdavplayer.data.local.WebDavAccountDao
import top.sparkfade.webdavplayer.data.local.PlaylistDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "webdav_player_db"
        )
        .fallbackToDestructiveMigration() // 允许重建数据库
        .build()
    }

    @Provides
    fun provideSongDao(db: AppDatabase): SongDao {
        return db.songDao()
    }

    @Provides
    fun provideAccountDao(db: AppDatabase): WebDavAccountDao {
        return db.accountDao()
    }

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao {
        return db.playlistDao()
    }
}