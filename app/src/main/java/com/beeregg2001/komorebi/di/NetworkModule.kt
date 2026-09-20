package com.beeregg2001.komorebi.di

import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.common.UrlBuilder
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

    private fun trustAllClient(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        return builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
    }

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

        // ★ 重要 (起動速度): 以前はここが Level.BODY だった。
        // BODY はレスポンス本文を丸ごとメモリへ読み切ってから文字列化し logcat へ流すため、
        // 数MB規模の JSON を返す EPG API では
        //   ・本文全体の二重デコード (ログ用 + Gson 用)
        //   ・巨大文字列の生成による GC 多発
        //   ・logd への大量書き込み
        // が発生する。起動直後は EPG / チャンネル一覧の取得が集中するため、
        // これが「起動直後だけ操作が絶望的に重い」最大の要因になっていた。
        // Cloudflare Access の診断はヘッダーが見えれば足りるので、
        // デバッグビルドでも HEADERS までに留め、リリースでは完全に無効化する。
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
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
                // ★ 修正: 以前は"$ip:$port"という素朴な文字列連結でベースURLを組み立てて
                // いたため、ip欄にサブディレクトリ付きURL(例: https://example.com/konomi)を
                // 設定すると"https://example.com/konomi:7000"のようにポートがパスの
                // 末尾に付いてしまい、HttpUrlパース時にホスト名付きポートと誤認識されず
                // encodedPath="/konomi:7000"という壊れた状態になっていた(ロゴ等
                // UrlBuilder.formatBaseUrl()生成のURLは正しく"https://example.com:7000/konomi"を
                // 向くため、APIとストリームで別々の壊れ方をする非対称な状態だった)。
                // EPGStation側で実績のあるUrlBuilder.formatBaseUrl()に統一する。
                val baseUrlString = runBlocking {
                    val ip = settingsRepository.konomiIp.first()
                    val port = settingsRepository.konomiPort.first()
                    UrlBuilder.formatBaseUrl(ip, port, "http")
                }
                val newUrl = baseUrlString.toHttpUrlOrNull() ?: originalRequest.url
                // 以前はscheme/host/portのみ差し替えており、リバースプロキシの
                // サブディレクトリ運用で設定値に含まれるパス接頭辞が失われ、Retrofit経由の
                // 全APIが404になっていた。ダミーbaseUrlのパス("/api/...")の前に、
                // 設定値側のパス接頭辞を連結する。
                // ★ 追加: EPGStation側と同じ理由で、既にbasePathで始まっているパスへの
                // 二重前置を避け冪等にする。
                val basePath = newUrl.encodedPath.removeSuffix("/")
                val originalPath = originalRequest.url.encodedPath
                val newPath = if (basePath.isNotEmpty() && !originalPath.startsWith(basePath)) {
                    basePath + originalPath
                } else {
                    originalPath
                }
                val modifiedUrl = originalRequest.url.newBuilder()
                    .scheme(newUrl.scheme)
                    .host(newUrl.host)
                    .port(newUrl.port)
                    .encodedPath(newPath)
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

    @Provides
    @Singleton
    @EpgStationClient
    fun provideEpgStationClient(
        settingsRepository: SettingsRepository,
        cloudflareAccessInterceptor: CloudflareAccessInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader(SettingsRepository.CF_ACCESS_CLIENT_SECRET_HEADER)
        }
        return trustAllClient(OkHttpClient.Builder())
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val base = runBlocking { settingsRepository.getEpgStationFullUrl() }
                    .toHttpUrlOrNull() ?: original.url
                // ★ 修正: KonomiTV側と同じ理由(サブディレクトリ運用でのパス欠落)。
                // stuayu/EPGStation自体もsubDirectory設定を正式サポートしており
                // (doc/conf-manual.md・ServiceServer.createUrl()で確認済み)、想定外の
                // 使い方ではない。
                // ★ 追加: このクライアントはRetrofit(ダミーURL宛)だけでなく、
                // EpgStationLiveRepository.getChannelLogoUrl()がUrlBuilder.
                // getEpgStationLogoUrl()で組み立てた「既にパス接頭辞を含むフルURL」の
                // リクエストにも使われる。後者に無条件でbasePathを前置すると、
                // サブディレクトリ運用時にパスが二重になり局ロゴが404になっていた。
                // 既にbasePathで始まっている場合は前置しないことで冪等にする。
                val basePath = base.encodedPath.removeSuffix("/")
                val originalPath = original.url.encodedPath
                val newPath = if (basePath.isNotEmpty() && !originalPath.startsWith(basePath)) {
                    basePath + originalPath
                } else {
                    originalPath
                }
                val modifiedUrl = original.url.newBuilder()
                    .scheme(base.scheme).host(base.host).port(base.port)
                    .encodedPath(newPath)
                    .build()
                chain.proceed(original.newBuilder().url(modifiedUrl).build())
            })
            .addInterceptor(cloudflareAccessInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    @EpgStationRetrofit
    fun provideEpgStationRetrofit(@EpgStationClient client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder().baseUrl("http://127.0.0.1:8888/").client(client)
            .addConverterFactory(GsonConverterFactory.create(gson)).build()

    @Provides
    @Singleton
    fun provideEpgStationApi(@EpgStationRetrofit retrofit: Retrofit): com.beeregg2001.komorebi.data.api.EpgStationApi =
        retrofit.create(com.beeregg2001.komorebi.data.api.EpgStationApi::class.java)
}
