package com.example.komorebi.di

import com.example.komorebi.data.SettingsRepository
import com.example.komorebi.data.repository.KonomiTvApiService
import com.example.komorebi.data.api.KonomiApi
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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(settingsRepository: SettingsRepository): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // 通信先のURLだけを表示
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging) // ログ出力を追加
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
            .baseUrl("http://192-168-100-60.local.konomi.tv:7000")
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