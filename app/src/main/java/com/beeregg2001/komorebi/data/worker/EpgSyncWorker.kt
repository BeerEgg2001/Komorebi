package com.beeregg2001.komorebi.data.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.beeregg2001.komorebi.data.sync.EpgSyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class EpgSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncEngine: EpgSyncEngine
) : CoroutineWorker(appContext, workerParams) {

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun doWork(): Result {
        Log.i("EpgSyncWorker", "Periodic EPG sync triggered by WorkManager")
        return try {
            syncEngine.syncEpgData()
            Result.success()
        } catch (e: Exception) {
            Log.e("EpgSyncWorker", "EPG sync failed, scheduling retry.", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "epg_data_sync_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // 番組表の更新は録画リストより頻度が低くて良いため、12時間おきに設定
            val request = PeriodicWorkRequestBuilder<EpgSyncWorker>(
                12, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // すでに予約済みなら維持
                request
            )
        }
    }
}