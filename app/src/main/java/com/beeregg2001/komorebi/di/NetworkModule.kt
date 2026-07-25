package com.beeregg2001.komorebi.di

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessInterceptor
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
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
    fun provideOkHttpClient(
        settingsRepository: SettingsRepository,
        cloudflareAccessInterceptor: CloudflareAccessInterceptor
    ): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {
            }

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            // Cloudflare Access のシークレットは logcat に平文で残さない
            redactHeader(SettingsRepository.CF_ACCESS_CLIENT_SECRET_HEADER)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // ★ 修正: Interceptorを明示的に指定し、SettingsRepositoryから正しくURLを取得する
            .addInterceptor(Interceptor { chain ->
                val originalRequest = chain.request()
                val baseUrlString = runBlocking {
                    // KonomiTVのベースURLを動的に取得して組み立てる
                    val ip = settingsRepository.konomiIp.first()
                    val port = settingsRepository.konomiPort.first()
                    if (ip.startsWith("http://") || ip.startsWith("https://")) {
                        "$ip:$port"
                    } else {
                        "http://$ip:$port"
                    }
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
            })
            .addInterceptor(cloudflareAccessInterceptor)
            // 最後に追加し、実際に送信されるヘッダーとレスポンス本文をログ出力する
            // (CF Access のブロック/認証ページがHTMLで返ってきていないか確認するため)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit {
        return Retrofit.Builder()
            // ここはダミーの初期値（Interceptorで動的に書き換わるため何でもOK）
            .baseUrl("https://192-168-11-100.local.konomi.tv:7000")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideKonomiApi(retrofit: Retrofit): KonomiApi {
        return retrofit.create(KonomiApi::class.java)
    }
}