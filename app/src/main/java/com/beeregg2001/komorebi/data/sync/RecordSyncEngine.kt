package com.beeregg2001.komorebi.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordSyncEngine"

data class SyncProgress(
    val isSyncing: Boolean = false,
    val isInitialBuild: Boolean = false,
    val message: String = "Loading...",
    val current: Int = 0,
    val total: Int = 0
) {
    val progressText: String
        get() = if (total > 0) "$message ($current / $total)" else "$message ($current 件取得中)"

    val progressRatio: Float?
        get() = if (total > 0) current.toFloat() / total.toFloat() else null
}

@Singleton
class RecordSyncEngine @Inject constructor(
    private val apiService: KonomiApi,
    private val db: AppDatabase
) {
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val syncMutex = Mutex()

    suspend fun syncAllRecords(forceFullSync: Boolean = false) {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Sync started. FullSync: $forceFullSync")

                    val metaDao = db.syncMetaDao()
                    val programDao = db.recordedProgramDao()

                    var currentMeta = metaDao.getSyncMeta() ?: SyncMetaEntity(
                        id = 1,
                        lastSyncedPage = 0,
                        lastSyncedAt = 0L,
                        isInitialBuildCompleted = false
                    )

                    if (forceFullSync) {
                        programDao.clearAll()
                        currentMeta = currentMeta.copy(
                            lastSyncedPage = 0,
                            isInitialBuildCompleted = false
                        )
                    }

                    val isInitial = !currentMeta.isInitialBuildCompleted
                    val baseMessage = if (isInitial) "DB construction..." else "Updating records..."

                    _syncProgress.value = SyncProgress(
                        isSyncing = true,
                        isInitialBuild = isInitial,
                        message = "$baseMessage (Connecting)"
                    )

                    var currentPage = if (currentMeta.lastSyncedPage > 0 && !forceFullSync) currentMeta.lastSyncedPage + 1 else 1
                    var isCompleted = false
                    var processedCount = 0

                    // ★追加: サーバー上に存在する全てのIDを保持するリスト
                    val allFetchedIds = mutableListOf<Int>()

                    while (!isCompleted) {
                        Log.i(TAG, "Fetching page: $currentPage")
                        val response = apiService.getRecordedPrograms(page = currentPage)
                        val programs = response.recordedPrograms

                        if (programs.isEmpty()) {
                            Log.i(TAG, "No more programs found. Reached end of records.")
                            isCompleted = true
                            break
                        }

                        val entities = programs.map { RecordDataMapper.toEntity(it) }

                        // ★追加: 取得したIDを漏れなく記録していく
                        allFetchedIds.addAll(entities.map { it.id })

                        if (currentMeta.isInitialBuildCompleted && !forceFullSync) {
                            val allPageItemsMatch = entities.all { entity ->
                                val local = programDao.getById(entity.id)
                                local != null && local == entity
                            }
                            if (allPageItemsMatch) {
                                Log.i(TAG, "No changes detected on page $currentPage. Stopping sync.")
                                isCompleted = true
                                break
                            }
                        }

                        db.withTransaction {
                            programDao.upsertAll(entities)
                            val newMeta = currentMeta.copy(
                                lastSyncedPage = currentPage,
                                lastSyncedAt = System.currentTimeMillis()
                            )
                            metaDao.upsert(newMeta)
                            currentMeta = newMeta
                        }

                        processedCount += programs.size
                        val totalCount = response.total.takeIf { it > 0 } ?: 0

                        _syncProgress.value = SyncProgress(
                            isSyncing = true,
                            isInitialBuild = isInitial,
                            message = baseMessage,
                            current = processedCount,
                            total = totalCount
                        )

                        if (totalCount > 0 && processedCount >= totalCount) {
                            isCompleted = true
                        } else {
                            currentPage++
                        }
                    }

                    if (isCompleted) {
                        metaDao.upsert(
                            currentMeta.copy(
                                lastSyncedAt = System.currentTimeMillis(),
                                isInitialBuildCompleted = true
                            )
                        )

                        // ★修正: 別途APIを叩かず、ループで集めた100%確実なIDリストを使ってクリーンアップ
                        if (isInitial || forceFullSync) {
                            if (allFetchedIds.isNotEmpty()) {
                                Log.i(TAG, "Cleaning up orphaned records...")
                                _syncProgress.value = _syncProgress.value.copy(
                                    isInitialBuild = isInitial,
                                    message = "Cleaning up old records..."
                                )
                                db.recordedProgramDao().deleteOrphans(allFetchedIds)
                                Log.i(TAG, "Cleanup completed.")
                            }
                        }
                    }

                    Log.i(TAG, "Sync completed successfully. Total processed: $processedCount")

                } catch (e: Exception) {
                    Log.i(TAG, "Sync interrupted. Will resume next time. Error: ${e.message}")
                } finally {
                    _syncProgress.value = SyncProgress(isSyncing = false, isInitialBuild = false)
                }
            }
        }
    }

    suspend fun clearDatabase() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Clearing database...")
        db.recordedProgramDao().clearAll()
        db.syncMetaDao().upsert(
            SyncMetaEntity(
                id = 1, lastSyncedPage = 0, lastSyncedAt = 0L, isInitialBuildCompleted = false
            )
        )
        Log.i(TAG, "Database cleared.")
    }

    suspend fun smartSync() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Smart sync started.")
            val metaDao = db.syncMetaDao()
            val programDao = db.recordedProgramDao()

            val currentMeta = metaDao.getSyncMeta()
            if (currentMeta == null || !currentMeta.isInitialBuildCompleted) {
                syncAllRecords(forceFullSync = false)
                return@withContext
            }

            val response = apiService.getRecordedPrograms(page = 1)
            val apiPrograms = response.recordedPrograms
            if (apiPrograms.isEmpty()) return@withContext

            val entities = apiPrograms.map { RecordDataMapper.toEntity(it) }

            val allPageItemsMatch = entities.all { entity ->
                val local = programDao.getById(entity.id)
                local != null && local == entity
            }

            if (!allPageItemsMatch) {
                Log.i(TAG, "Smart sync found changes. Updating local DB.")
                db.withTransaction {
                    programDao.upsertAll(entities)
                }
            } else {
                Log.i(TAG, "Smart sync found no changes.")
            }
        } catch (e: Exception) {
            Log.i(TAG, "Smart sync error: ${e.message}")
        }
    }
}