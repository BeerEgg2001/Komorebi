package com.beeregg2001.komorebi.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.dao.AiSeriesDictionaryDao
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import com.beeregg2001.komorebi.data.repository.AiNormalizationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val db: AppDatabase,
    private val aiNormalizationRepository: AiNormalizationRepository,
    private val settingsRepository: SettingsRepository,
    private val aiSeriesDictionaryDao: AiSeriesDictionaryDao
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
                        currentMeta =
                            currentMeta.copy(lastSyncedPage = 0, isInitialBuildCompleted = false)
                    }

                    val isInitial = !currentMeta.isInitialBuildCompleted
                    val baseMessage = if (isInitial) "DB construction..." else "Updating records..."

                    _syncProgress.value = SyncProgress(
                        isSyncing = true,
                        isInitialBuild = isInitial,
                        message = "$baseMessage (Connecting)"
                    )

                    var currentPage =
                        if (currentMeta.lastSyncedPage > 0 && !forceFullSync) currentMeta.lastSyncedPage + 1 else 1
                    var isCompleted = false
                    var processedCount = 0

                    val allFetchedIds = mutableListOf<Int>()
                    val allFetchedTitles = mutableSetOf<String>() // ★追加: 取得したタイトルを保持

                    while (!isCompleted) {
                        Log.i(TAG, "Fetching page: $currentPage")
                        val response = apiService.getRecordedPrograms(page = currentPage)
                        val programs = response.recordedPrograms

                        if (programs.isEmpty()) {
                            isCompleted = true
                            break
                        }

                        val entities = programs.map { RecordDataMapper.toEntity(it) }
                        allFetchedIds.addAll(entities.map { it.id })
                        allFetchedTitles.addAll(entities.map { it.title }) // ★追加: タイトルを記録

                        if (currentMeta.isInitialBuildCompleted && !forceFullSync) {
                            val allPageItemsMatch = entities.all { entity ->
                                val local = programDao.getById(entity.id)
                                local != null && local == entity
                            }
                            if (allPageItemsMatch) {
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

                        if (totalCount > 0 && processedCount >= totalCount) isCompleted =
                            true else currentPage++
                    }

                    if (isCompleted) {
                        metaDao.upsert(
                            currentMeta.copy(
                                lastSyncedAt = System.currentTimeMillis(),
                                isInitialBuildCompleted = true
                            )
                        )

                        if (isInitial || forceFullSync) {
                            if (allFetchedIds.isNotEmpty()) {
                                db.recordedProgramDao().deleteOrphans(allFetchedIds)
                            }
                        }

                        // ★修正: 取得した生タイトルを直接渡す（DBの更新遅延を回避）
                        runAiNormalizationIfNeeded(allFetchedTitles.toList())
                    }

                } catch (e: Exception) {
                    Log.i(TAG, "Sync interrupted. Error: ${e.message}")
                } finally {
                    _syncProgress.value = SyncProgress(isSyncing = false, isInitialBuild = false)
                }
            }
        }
    }

    suspend fun clearDatabase() = withContext(Dispatchers.IO) {
        db.recordedProgramDao().clearAll()
        db.syncMetaDao().upsert(
            SyncMetaEntity(
                id = 1,
                lastSyncedPage = 0,
                lastSyncedAt = 0L,
                isInitialBuildCompleted = false
            )
        )
    }

    suspend fun smartSync() = withContext(Dispatchers.IO) {
        try {
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
                db.withTransaction { programDao.upsertAll(entities) }
                // ★修正: 差分で取得したタイトルだけを渡す
                runAiNormalizationIfNeeded(entities.map { it.title })
            }
        } catch (e: Exception) {
            Log.i(TAG, "Smart sync error: ${e.message}")
        }
    }

    // ★修正: 引数でターゲットのタイトルリストを受け取る
    private suspend fun runAiNormalizationIfNeeded(targetTitles: List<String>) {
        try {
            val isEnabled = settingsRepository.enableAiNormalization.first() == "ON"
            if (!isEnabled) return

            val apiKey = settingsRepository.geminiApiKey.first()
            if (apiKey.isBlank()) return

            val existingDict =
                aiSeriesDictionaryDao.getAllDictionary().map { it.originalTitle }.toSet()

            // 辞書に存在しない「完全初見」のタイトルだけを抽出
            val unknownTitles = targetTitles.filter { it !in existingDict }.distinct()

            if (unknownTitles.isEmpty()) return

            Log.i(TAG, "Starting AI Normalization for ${unknownTitles.size} unknown titles.")
            _syncProgress.value = _syncProgress.value.copy(
                isSyncing = true,
                message = "AI名寄せ辞書を更新中..."
            )

            // ★修正: 100件はAIがサボるので、確実に処理させるため25件ずつに小分けする
            val chunks = unknownTitles.chunked(25)
            for ((index, chunk) in chunks.withIndex()) {
                Log.i(TAG, "AI Normalization chunk ${index + 1}/${chunks.size}")
                aiNormalizationRepository.normalizeTitles(chunk)
            }
            Log.i(TAG, "AI Normalization completed.")
        } catch (e: Exception) {
            Log.e(TAG, "AI Normalization error", e)
        }
    }
}