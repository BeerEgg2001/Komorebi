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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordSyncEngine"
private const val PAGE_SIZE = 30

data class SyncProgress(
    val isSyncing: Boolean = false,
    val isInitialBuild: Boolean = false,
    val message: String = "Loading...",
    val current: Int = 0,
    val total: Int = 0
) {
    val progressText: String
        get() = if (total > 0) "$message ($current / $total)" else message

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

    suspend fun syncAllRecords(forceFullSync: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Sync started. FullSync: $forceFullSync")

            val metaDao = db.syncMetaDao()
            val programDao = db.recordedProgramDao()

            val currentMeta = metaDao.getSyncMeta() ?: SyncMetaEntity(
                id = 1,
                lastSyncedPage = 0,
                lastSyncedAt = 0L,
                isInitialBuildCompleted = false
            )

            val isInitial = !currentMeta.isInitialBuildCompleted || forceFullSync
            // ★修正: 差分更新の時はメッセージを変える
            val baseMessage = if (isInitial) "DB construction..." else "Updating records..."

            _syncProgress.value = SyncProgress(
                isSyncing = true,
                isInitialBuild = isInitial,
                message = "$baseMessage (Connecting)"
            )

            var currentPage = if (!currentMeta.isInitialBuildCompleted) currentMeta.lastSyncedPage + 1 else 1
            var isCompleted = false
            var processedCount = (currentPage - 1) * PAGE_SIZE

            while (!isCompleted) {
                Log.i(TAG, "Fetching page: $currentPage")
                val response = apiService.getRecordedPrograms(page = currentPage)
                val programs = response.recordedPrograms

                if (programs.isEmpty()) {
                    isCompleted = true
                    break
                }

                _syncProgress.value = SyncProgress(
                    isSyncing = true,
                    isInitialBuild = isInitial,
                    message = baseMessage, // ★修正
                    current = processedCount,
                    total = response.total
                )

                val entities = programs.map { RecordDataMapper.toEntity(it) }

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
                        lastSyncedAt = System.currentTimeMillis(),
                        isInitialBuildCompleted = currentMeta.isInitialBuildCompleted || (currentPage * PAGE_SIZE >= response.total)
                    )
                    metaDao.upsert(newMeta)
                }

                processedCount += programs.size
                _syncProgress.value = SyncProgress(
                    isSyncing = true,
                    isInitialBuild = isInitial,
                    message = baseMessage, // ★修正
                    current = processedCount,
                    total = response.total
                )

                if ((currentPage * PAGE_SIZE) >= response.total) {
                    isCompleted = true
                } else {
                    currentPage++
                }
            }

            if (currentMeta.isInitialBuildCompleted || forceFullSync) {
                cleanupOrphanedRecords(isInitial)
            }

            Log.i(TAG, "Sync completed successfully.")

        } catch (e: Exception) {
            Log.i(TAG, "Sync interrupted. Will resume next time. Error: ${e.message}")
        } finally {
            _syncProgress.value = SyncProgress(isSyncing = false, isInitialBuild = false)
        }
    }

    private suspend fun cleanupOrphanedRecords(isInitial: Boolean) {
        try {
            Log.i(TAG, "Cleaning up deleted records...")
            _syncProgress.value = _syncProgress.value.copy(isInitialBuild = isInitial, message = "Cleaning up old records...")
            val response = apiService.getRecordedPrograms(limit = 10000)
            val apiIds = response.recordedPrograms.map { it.id }

            if (apiIds.isNotEmpty()) {
                db.recordedProgramDao().deleteOrphans(apiIds)
                Log.i(TAG, "Cleanup completed.")
            }
        } catch (e: Exception) {
            Log.i(TAG, "Cleanup failed: ${e.message}")
        }
    }

    suspend fun clearDatabase() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Clearing database...")
        _syncProgress.value = SyncProgress(isSyncing = true, isInitialBuild = true, message = "Clearing database...")
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
                syncAllRecords()
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