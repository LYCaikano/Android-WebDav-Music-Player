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
import top.sparkfade.webdavplayer.security.HostAwareSslSocketFactory
import top.sparkfade.webdavplayer.utils.CurrentSession
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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

    // 2. 动态 Auth 拦截器 + 按主机动态 TLS 校验
    // 默认严格校验证书；仅当主机属于勾选了 skipSsl 的账号时才信任所有证书
    @Provides
    @Singleton
    @PlayerClient
    fun providePlayerOkHttpClient(): OkHttpClient {
        val strictContext = SSLContext.getInstance("TLS")
        strictContext.init(null, null, null)

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {}

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {}

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val trustAllContext = SSLContext.getInstance("TLS")
        trustAllContext.init(null, trustAllCerts, SecureRandom())

        val defaultTrustManager = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        return OkHttpClient.Builder()
            .sslSocketFactory(
                HostAwareSslSocketFactory(
                    strictFactory = strictContext.socketFactory,
                    trustAllFactory = trustAllContext.socketFactory
                ),
                defaultTrustManager
            )
            .hostnameVerifier { hostname, session ->
                if (CurrentSession.isSkipSslHost(hostname)) true
                else defaultHostnameVerifier.verify(hostname, session)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
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
