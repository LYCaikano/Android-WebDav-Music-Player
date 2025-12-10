package top.sparkfade.webdavplayer.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import top.sparkfade.webdavplayer.utils.CurrentSession
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    // 定义一个新的限定符，专门用于播放器的 OkHttp 客户端
    // 这打破了与 NetworkModule 中 UnsafeClient 的依赖循环和命名冲突
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class PlayerClient

    // 1. 缓存配置
    @Provides
    @Singleton
    @OptIn(UnstableApi::class)
    fun provideDatabaseProvider(@ApplicationContext context: Context): DatabaseProvider {
        return StandaloneDatabaseProvider(context)
    }

    @Provides
    @Singleton
    @OptIn(UnstableApi::class)
    fun provideCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider
    ): Cache {
        val cacheDir = File(context.cacheDir, "media_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024) // 512MB
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }

    // 2. 动态 Auth 拦截器
    // 输入：基础的 UnsafeClient (来自 NetworkModule)
    // 输出：带拦截器的 PlayerClient (供下方 DataSource 使用)
    @Provides
    @Singleton
    @PlayerClient 
    fun providePlayerOkHttpClient(
        @NetworkModule.UnsafeClient baseClient: OkHttpClient
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                // 修复：从原始 request 获取 URL
                val url = chain.request().url.toString()
                CurrentSession.getAuthForUrl(url)?.let {
                    requestBuilder.addHeader("Authorization", it)
                }
                chain.proceed(requestBuilder.build())
            }
            .build()
    }

    // 3. 数据源
    // 这里明确注入 @PlayerClient，而不是 @UnsafeClient
    @Provides
    @Singleton
    @OptIn(UnstableApi::class)
    fun provideDataSourceFactory(
        @ApplicationContext context: Context,
        @PlayerClient playerClient: OkHttpClient, 
        cache: Cache
    ): DataSource.Factory {
        // 远程数据源
        val upstreamFactory = OkHttpDataSource.Factory(playerClient)
        
        // 缓存数据源
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultDataSource.Factory(context, cacheDataSourceFactory)
    }

    // 4. ExoPlayer 实例
    @Provides
    @Singleton
    @OptIn(UnstableApi::class)
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        dataSourceFactory: DataSource.Factory
    ): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}
