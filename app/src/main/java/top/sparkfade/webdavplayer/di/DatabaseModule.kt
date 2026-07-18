package top.sparkfade.webdavplayer.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import top.sparkfade.webdavplayer.data.local.AppDatabase
import top.sparkfade.webdavplayer.data.local.SongDao
import top.sparkfade.webdavplayer.data.local.WebDavAccountDao
import top.sparkfade.webdavplayer.data.local.PlaylistDao
import top.sparkfade.webdavplayer.utils.Constants
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                DELETE FROM playlist_song_cross_ref
                WHERE playlistId NOT IN (SELECT id FROM playlists)
                   OR songId NOT IN (SELECT id FROM songs)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playlist_song_cross_ref_new` (
                    `playlistId` INTEGER NOT NULL,
                    `songId` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`playlistId`, `songId`),
                    FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`songId`) REFERENCES `songs`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `playlist_song_cross_ref_new` (`playlistId`, `songId`, `addedAt`)
                SELECT `playlistId`, `songId`, `addedAt` FROM `playlist_song_cross_ref`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `playlist_song_cross_ref`")
            db.execSQL("ALTER TABLE `playlist_song_cross_ref_new` RENAME TO `playlist_song_cross_ref`")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_playlist_song_cross_ref_songId`
                ON `playlist_song_cross_ref` (`songId`)
                """.trimIndent()
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            Constants.DATABASE_NAME
        )
        .addMigrations(MIGRATION_8_9)
        .fallbackToDestructiveMigration()
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
