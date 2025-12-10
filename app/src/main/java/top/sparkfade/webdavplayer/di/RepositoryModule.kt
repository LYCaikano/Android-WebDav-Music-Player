package top.sparkfade.webdavplayer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import top.sparkfade.webdavplayer.data.repository.MusicRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    // MusicRepository 已经使用了 @Inject 构造函数，
    // 这里其实不需要显式 Provide，除非需要转换成接口。
    // Hilt 会自动扫描 @Inject constructor。
    // 占位以备后续扩展。
}