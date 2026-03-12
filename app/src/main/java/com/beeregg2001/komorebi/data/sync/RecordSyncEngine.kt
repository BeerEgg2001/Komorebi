package com.beeregg2001.komorebi.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.dao.AiSeriesDictionaryDao
import com.beeregg2001.komorebi.data.local.entity.AiSeriesDictionaryEntity
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import com.beeregg2001.komorebi.util.TitleNormalizer
import com.beeregg2001.komorebi.util.WikipediaNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordSyncEngine"

data class SyncProgress(
    val isSyncing: Boolean = false,
    val isInitialBuild: Boolean = false,
    val isInitialSyncPhase: Boolean = false,
    val message: String = "Loading...",
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null
) {
    val progressText: String
        get() = if (total > 0) "$message ($current / $total)" else "$message ($current 件取得中)"
}

@Singleton
class RecordSyncEngine @Inject constructor(
    private val apiService: KonomiApi,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val aiSeriesDictionaryDao: AiSeriesDictionaryDao
) {
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val syncMutex = Mutex()
    private val jobMutex = Mutex()
    private val dictionaryMutex = Mutex()
    private var activeSyncJob: Job? = null

    private val BATCH_SIZE = 100

    fun clearError() {
        _syncProgress.value = _syncProgress.value.copy(error = null)
    }

    suspend fun syncAllRecords(forceFullSync: Boolean = false) {
        val currentJob = currentCoroutineContext().job

        var jobToJoin: Job? = null
        if (forceFullSync) {
            jobMutex.withLock {
                jobToJoin = activeSyncJob
                jobToJoin?.cancel()
            }
            jobToJoin?.join()
        }

        var isSyncSuccessful = false

        syncMutex.withLock {
            jobMutex.withLock {
                activeSyncJob = currentJob
            }
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
                        aiSeriesDictionaryDao.clearAll()
                        currentMeta =
                            currentMeta.copy(lastSyncedPage = 0, isInitialBuildCompleted = false)
                    }

                    val isInitial = forceFullSync || !currentMeta.isInitialBuildCompleted
                    val baseMessage =
                        if (isInitial) "データベース構築中..." else "録画リストを更新中..."

                    _syncProgress.value = SyncProgress(
                        isSyncing = true,
                        isInitialBuild = isInitial,
                        isInitialSyncPhase = isInitial,
                        message = "$baseMessage (接続中)"
                    )

                    var currentPage =
                        if (currentMeta.lastSyncedPage > 0 && !forceFullSync) currentMeta.lastSyncedPage + 1 else 1
                    val isResumed = currentPage > 1
                    var isCompleted = false
                    var processedCount = if (isResumed) programDao.getAllIds().size else 0

                    val allFetchedIds = mutableSetOf<Int>()
                    val dictionary = aiSeriesDictionaryDao.getAllDictionary()
                        .associate { it.originalTitle to it.normalizedSeriesName }

                    val entityBuffer = mutableListOf<RecordedProgramEntity>()

                    while (!isCompleted) {
                        currentCoroutineContext().ensureActive()

                        Log.i(TAG, "Fetching page: $currentPage")
                        val response = apiService.getRecordedPrograms(page = currentPage)
                        val programs = response.recordedPrograms

                        if (programs.isEmpty()) {
                            isCompleted = true
                            break
                        }

                        val entities = programs.map { RecordDataMapper.toEntity(it) }
                        allFetchedIds.addAll(entities.map { it.id })

                        if (currentMeta.isInitialBuildCompleted && !forceFullSync) {
                            val pageIds = entities.map { it.id }
                            val localEntitiesMap =
                                programDao.getByIds(pageIds).associateBy { it.id }

                            val allPageItemsMatch =
                                entities.size == localEntitiesMap.size && entities.all { entity ->
                                    val local = localEntitiesMap[entity.id]
                                    local != null && local.title == entity.title
                                }
                            if (allPageItemsMatch) {
                                isCompleted = true
                                break
                            }
                        }

                        val enrichedEntities = entities.map { entity ->
                            val baseTitle = TitleNormalizer.extractDisplayTitle(entity.title)
                            val finalSeriesName = dictionary[entity.title] ?: baseTitle
                            entity.copy(seriesName = finalSeriesName)
                        }

                        entityBuffer.addAll(enrichedEntities)

                        if (entityBuffer.size >= BATCH_SIZE) {
                            db.withTransaction {
                                programDao.upsertAll(entityBuffer)
                                val newMeta = currentMeta.copy(
                                    lastSyncedPage = currentPage,
                                    lastSyncedAt = System.currentTimeMillis()
                                )
                                metaDao.upsert(newMeta)
                                currentMeta = newMeta
                            }
                            entityBuffer.clear()

                            if (_syncProgress.value.isInitialBuild) {
                                _syncProgress.value =
                                    _syncProgress.value.copy(isInitialBuild = false)
                            }
                        }

                        processedCount += programs.size
                        val totalCount = response.total.takeIf { it > 0 } ?: 0

                        _syncProgress.value = _syncProgress.value.copy(
                            isSyncing = true,
                            message = baseMessage,
                            current = processedCount,
                            total = totalCount
                        )
                        if (totalCount > 0 && processedCount >= totalCount) {
                            isCompleted = true
                        } else {
                            currentPage++
                            delay(100)
                        }
                    }

                    if (entityBuffer.isNotEmpty()) {
                        db.withTransaction {
                            programDao.upsertAll(entityBuffer)
                            val newMeta = currentMeta.copy(
                                lastSyncedPage = currentPage,
                                lastSyncedAt = System.currentTimeMillis()
                            )
                            metaDao.upsert(newMeta)
                            currentMeta = newMeta
                        }
                        entityBuffer.clear()
                    }

                    if (isCompleted) {
                        if (!isResumed && allFetchedIds.isNotEmpty()) {
                            val localIds = programDao.getAllIds()
                            val idsToDelete = localIds.toSet() - allFetchedIds
                            if (idsToDelete.isNotEmpty()) {
                                idsToDelete.chunked(900).forEach { chunk ->
                                    programDao.deleteByIds(chunk)
                                }
                            }
                        }
                        allFetchedIds.clear()

                        metaDao.upsert(
                            currentMeta.copy(
                                lastSyncedAt = System.currentTimeMillis(),
                                isInitialBuildCompleted = true
                            )
                        )
                    }

                    // ★修正: ここでは isSyncing = false にしない！
                    // ジョブのキャンセルを防ぎ、そのまま辞書構築ループへバトンを渡す。
                    isSyncSuccessful = true

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sync interrupted. Error: ${e.message}", e)
                    _syncProgress.value = SyncProgress(
                        isSyncing = false,
                        isInitialBuild = false,
                        isInitialSyncPhase = false,
                        error = e.localizedMessage ?: "不明なエラーが発生しました"
                    )
                } finally {
                    jobMutex.withLock {
                        if (activeSyncJob == currentJob) {
                            activeSyncJob = null
                        }
                    }
                }
            }
        }

        // 正常にDB構築が終わった場合のみ、辞書構築の無限ループに入る
        if (isSyncSuccessful) {
            startDictionaryResolutionLoop()
        }
    }

    suspend fun clearDatabase() = withContext(Dispatchers.IO) {
        db.recordedProgramDao().clearAll()
        aiSeriesDictionaryDao.clearAll()
        db.syncMetaDao().upsert(
            SyncMetaEntity(
                id = 1,
                lastSyncedPage = 0,
                lastSyncedAt = 0L,
                isInitialBuildCompleted = false
            )
        )
    }

    suspend fun smartSync() {
        val currentMeta = withContext(Dispatchers.IO) { db.syncMetaDao().getSyncMeta() }
        if (currentMeta == null || !currentMeta.isInitialBuildCompleted) {
            syncAllRecords(forceFullSync = false)
            return
        }

        val currentJob = currentCoroutineContext().job
        var isSyncSuccessful = false

        syncMutex.withLock {
            jobMutex.withLock {
                activeSyncJob = currentJob
            }
            withContext(Dispatchers.IO) {
                try {
                    val programDao = db.recordedProgramDao()
                    currentCoroutineContext().ensureActive()

                    val response = apiService.getRecordedPrograms(page = 1)
                    val apiPrograms = response.recordedPrograms
                    if (apiPrograms.isEmpty()) return@withContext

                    val entities = apiPrograms.map { RecordDataMapper.toEntity(it) }
                    val pageIds = entities.map { it.id }
                    val localEntitiesMap = programDao.getByIds(pageIds).associateBy { it.id }

                    val allPageItemsMatch =
                        entities.size == localEntitiesMap.size && entities.all { entity ->
                            val local = localEntitiesMap[entity.id]
                            local != null && local.title == entity.title
                        }

                    if (!allPageItemsMatch) {
                        val dictionary = aiSeriesDictionaryDao.getAllDictionary()
                            .associate { it.originalTitle to it.normalizedSeriesName }

                        val enrichedEntities = entities.map { entity ->
                            val baseTitle = TitleNormalizer.extractDisplayTitle(entity.title)
                            val finalSeriesName = dictionary[entity.title] ?: baseTitle
                            entity.copy(seriesName = finalSeriesName)
                        }

                        db.withTransaction { programDao.upsertAll(enrichedEntities) }
                    }

                    isSyncSuccessful = true

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Smart sync error: ${e.message}", e)
                } finally {
                    jobMutex.withLock {
                        if (activeSyncJob == currentJob) {
                            activeSyncJob = null
                        }
                    }
                }
            }
        }

        // 正常終了時のみ辞書構築へ
        if (isSyncSuccessful) {
            startDictionaryResolutionLoop()
        }
    }

    private suspend fun startDictionaryResolutionLoop() {
        if (!dictionaryMutex.tryLock()) {
            Log.i(TAG, "Dictionary resolution is already running. Skipping.")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val programDao = db.recordedProgramDao()

                val totalUnknown = programDao.getUnknownTitlesCount()

                if (totalUnknown == 0) {
                    Log.i(TAG, "No unknown titles found. Dictionary is up to date.")
                    // ★修正: 辞書構築が不要な場合のみ、ここで明示的に同期フラグを落とす
                    _syncProgress.value = SyncProgress(
                        isSyncing = false,
                        isInitialBuild = false,
                        isInitialSyncPhase = false
                    )
                    return@withContext
                }

                // 辞書構築が必要な場合は、フラグを true に維持したままメッセージを切り替える
                _syncProgress.value = SyncProgress(
                    isSyncing = true,
                    isInitialBuild = false,
                    isInitialSyncPhase = false, // UIブロックは解除
                    message = "シリーズ辞書を自動生成中...",
                    current = 0,
                    total = totalUnknown
                )

                var processedCount = 0

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val unknownTitles = programDao.getUnknownTitles(limit = 50)
                    if (unknownTitles.isEmpty()) break

                    val newDictEntries = mutableListOf<AiSeriesDictionaryEntity>()

                    for (title in unknownTitles) {
                        currentCoroutineContext().ensureActive()
                        val baseTitle = TitleNormalizer.extractDisplayTitle(title)

                        val canonicalTitle = try {
                            WikipediaNormalizer.getCanonicalTitle(baseTitle)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(
                                TAG,
                                "Wikipedia lookup failed for '$baseTitle', skipping: ${e.message}"
                            )
                            null
                        }

                        delay(300)

                        val finalSeriesName = canonicalTitle ?: baseTitle
                        processedCount++
                        _syncProgress.value = _syncProgress.value.copy(current = processedCount)

                        newDictEntries.add(
                            AiSeriesDictionaryEntity(
                                originalTitle = title,
                                normalizedSeriesName = finalSeriesName,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }

                    if (newDictEntries.isNotEmpty()) {
                        db.withTransaction {
                            aiSeriesDictionaryDao.insertAll(newDictEntries)
                            newDictEntries.forEach { dict ->
                                programDao.updateSeriesNameByOriginalTitle(
                                    originalTitle = dict.originalTitle,
                                    newSeriesName = dict.normalizedSeriesName
                                )
                            }
                        }
                    }

                    val hasMore = programDao.getUnknownTitlesCount() > 0
                    if (hasMore) delay(2000)
                }

                Log.i(TAG, "Dictionary resolution loop completed successfully.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background dictionary generation failed", e)
        } finally {
            // ループがすべて完了した、またはエラーが起きた場合に同期フラグを完全に落とす
            _syncProgress.value = SyncProgress(
                isSyncing = false,
                isInitialBuild = false,
                isInitialSyncPhase = false
            )
            dictionaryMutex.unlock()
        }
    }

    suspend fun isInitialBuildCompleted(): Boolean = withContext(Dispatchers.IO) {
        db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true
    }
}