package com.beeregg2001.komorebi

import android.app.Application
// import android.content.res.Configuration <- これを削除しました
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.beeregg2001.komorebi.data.worker.RecordSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

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