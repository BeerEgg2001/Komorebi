package com.beeregg2001.komorebi

import android.app.Application
// import android.content.res.Configuration <- これを削除しました
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessInterceptor
import com.beeregg2001.komorebi.data.worker.RecordSyncWorker
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var cloudflareAccessInterceptor: CloudflareAccessInterceptor

    // ★ 追加: Coil (AsyncImage) の画像取得にも Cloudflare Access ヘッダーを付与
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor(cloudflareAccessInterceptor)
                    .build()
            }
            .build()
    }

    // ★ 最新の WorkManager に合わせてプロパティとしてオーバーライドします
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // バックグラウンド同期スケジュールを登録
        RecordSyncWorker.schedule(this)
    }
}