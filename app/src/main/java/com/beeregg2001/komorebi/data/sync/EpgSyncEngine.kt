package com.beeregg2001.komorebi.data.sync

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.room.withTransaction
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.mapper.EpgDataMapper
import com.beeregg2001.komorebi.data.repository.KonomiTvApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EpgSyncEngine"

@Singleton
class EpgSyncEngine @Inject constructor(
    private val apiService: KonomiTvApiService,
    private val db: AppDatabase
) {
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val syncMutex = Mutex()

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun syncEpgData() {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "EPG Sync started.")
                    _syncProgress.value = SyncProgress(
                        isSyncing = true,
                        message = "番組表データを同期中..."
                    )

                    val channelTypes = listOf("GR", "BS", "CS", "BS4K", "SKY")
                    val now = OffsetDateTime.now()
                    val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

                    // ★過去データの保持期間（1週間前）をミリ秒で計算
                    val cleanupThresholdEpoch = now.minusDays(7).toInstant().toEpochMilli()

                    var processedTypes = 0

                    for (type in channelTypes) {
                        Log.i(TAG, "Fetching EPG for: $type")

                        // ★APIの負荷・タイムアウトを防ぐため、3日分ずつ2回（計6日分）に分けて取得する
                        for (chunkIndex in 0 until 2) {
                            val start = now.plusDays((chunkIndex * 3).toLong()).withHour(0).withMinute(0).withSecond(0)
                            val end = start.plusDays(3)

                            try {
                                val response = apiService.getEpgPrograms(
                                    startTime = start.format(formatter),
                                    endTime = end.format(formatter),
                                    channelType = type
                                )

                                // Mapperを使ってAPIモデルからRoomエンティティへ変換
                                val channels = response.channels.map { EpgDataMapper.toChannelEntity(it.channel) }
                                val programs = response.channels.flatMap { wrapper ->
                                    wrapper.programs.map { EpgDataMapper.toProgramEntity(it) }
                                }

                                // DBに一括挿入（重複は自動で上書き更新される）
                                db.withTransaction {
                                    db.epgDao().insertEpgData(channels, programs)
                                }

                                Log.i(TAG, "Successfully synced EPG for $type (Chunk $chunkIndex: ${programs.size} programs)")
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.e(TAG, "Error syncing EPG for $type (Chunk $chunkIndex)", e)
                            }
                        }

                        processedTypes++
                        _syncProgress.value = SyncProgress(
                            isSyncing = true,
                            message = "番組表データを同期中...",
                            current = processedTypes,
                            total = channelTypes.size
                        )
                    }

                    // ★同期完了後に1週間以上前の古い番組を自動削除
                    Log.i(TAG, "Cleaning up old EPG programs...")
                    _syncProgress.value = _syncProgress.value.copy(message = "古い番組表データを整理中...")
                    db.epgDao().deleteOldPrograms(cleanupThresholdEpoch)

                    Log.i(TAG, "EPG Sync completed successfully.")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "EPG Sync interrupted. Error: ${e.message}")
                } finally {
                    _syncProgress.value = SyncProgress(isSyncing = false)
                }
            }
        }
    }

    suspend fun clearEpgDatabase() = withContext(Dispatchers.IO) {
        // ※必要になった時のための全削除メソッド
        // db.epgDao().deleteAll() などを実装した場合に呼び出す
        Log.i(TAG, "EPG Database cleared (Not implemented fully yet).")
    }
}