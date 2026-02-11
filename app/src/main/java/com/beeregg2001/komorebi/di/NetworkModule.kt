package com.beeregg2001.komorebi.di

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.repository.KonomiTvApiService
import com.beeregg2001.komorebi.data.api.KonomiApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(settingsRepository: SettingsRepository): OkHttpClient {
        // 1. すべての証明書を無条件で信頼するTrustManagerを作成（FireOS 9のルート証明書問題回避用）
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        // 2. SSLコンテキストを初期化
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // 通信先のURLだけを表示
        }

        return OkHttpClient.Builder()
            // 3. SSL/TLS設定の適用（証明書エラーのバイパスとホスト名検証スキップ）
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }

            // 4. タイムアウトの大幅延長（AkebiのKeyless SSL遅延対策として一律30秒に設定）
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

            // 既存のインターセプターを維持
//            .addInterceptor(logging) // ログ出力を追加
            .addInterceptor { chain ->
                val originalRequest = chain.request()

                val baseUrlString = runBlocking {
                    settingsRepository.getBaseUrl()
                }

                val newUrl = baseUrlString.toHttpUrlOrNull() ?: originalRequest.url

                val modifiedUrl = originalRequest.url.newBuilder()
                    .scheme(newUrl.scheme)
                    .host(newUrl.host)
                    .port(newUrl.port)
                    .build()

                val newRequest = originalRequest.newBuilder()
                    .url(modifiedUrl)
                    .build()

                chain.proceed(newRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://192-168-11-10.local.konomi.tv:7000") // ※動的URLで上書きされるためダミーでもOK
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    // HomeViewModel等で使用
    @Provides
    @Singleton
    fun provideKonomiApi(retrofit: Retrofit): KonomiApi {
        return retrofit.create(KonomiApi::class.java)
    }

    // EpgViewModel / EpgRepository で使用
    @Provides
    @Singleton
    fun provideKonomiTvApiService(retrofit: Retrofit): KonomiTvApiService {
        return retrofit.create(KonomiTvApiService::class.java)
    }
}