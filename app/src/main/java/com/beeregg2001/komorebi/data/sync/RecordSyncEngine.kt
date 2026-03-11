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
                    val unknownBaseTitlesToOriginals = mutableMapOf<String, MutableSet<String>>()
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
                            if (!dictionary.containsKey(entity.title)) {
                                unknownBaseTitlesToOriginals.getOrPut(baseTitle) { mutableSetOf() }
                                    .add(entity.title)
                            }
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

                            // =================================================================
                            // ★ 復元: 最初の100件が保存されたら、UI（ホーム画面）を開放する！
                            // （これが無いと1万件終わるまでアプリが操作不能になります）
                            // =================================================================
                            if (_syncProgress.value.isInitialBuild) {
                                _syncProgress.value =
                                    _syncProgress.value.copy(isInitialBuild = false)
                                Log.i(
                                    TAG,
                                    "First batch saved. Released InitialBuild block to navigate to Home."
                                )
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
                                Log.i(TAG, "Deleting ${idsToDelete.size} orphan records.")
                                idsToDelete.chunked(900).forEach { chunk ->
                                    programDao.deleteByIds(chunk)
                                }
                            }
                            allFetchedIds.clear()
                        } else if (isResumed) {
                            Log.i(
                                TAG,
                                "Skipped deleting orphans because sync was resumed from a later page."
                            )
                        }

                        metaDao.upsert(
                            currentMeta.copy(
                                lastSyncedAt = System.currentTimeMillis(),
                                isInitialBuildCompleted = true
                            )
                        )
                    }

                    if (unknownBaseTitlesToOriginals.isNotEmpty()) {
                        resolveUnknownSeriesNamesBackground(unknownBaseTitlesToOriginals, isInitial)
                    } else {
                        _syncProgress.value = SyncProgress(
                            isSyncing = false,
                            isInitialBuild = false,
                            isInitialSyncPhase = false
                        )
                    }

                } catch (e: CancellationException) {
                    Log.i(TAG, "Sync cancelled by forceFullSync or WorkManager timeout.")
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
        syncMutex.withLock {
            jobMutex.withLock {
                activeSyncJob = currentJob
            }
            withContext(Dispatchers.IO) {
                try {
                    val programDao = db.recordedProgramDao()
                    resumeDictionaryResolutionIfNeeded()

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
                        val unknownBaseTitlesToOriginals =
                            mutableMapOf<String, MutableSet<String>>()

                        val enrichedEntities = entities.map { entity ->
                            val baseTitle = TitleNormalizer.extractDisplayTitle(entity.title)
                            val finalSeriesName = dictionary[entity.title] ?: baseTitle
                            if (!dictionary.containsKey(entity.title)) {
                                unknownBaseTitlesToOriginals.getOrPut(baseTitle) { mutableSetOf() }
                                    .add(entity.title)
                            }
                            entity.copy(seriesName = finalSeriesName)
                        }

                        db.withTransaction { programDao.upsertAll(enrichedEntities) }

                        if (unknownBaseTitlesToOriginals.isNotEmpty()) {
                            resolveUnknownSeriesNamesBackground(unknownBaseTitlesToOriginals, false)
                        }
                    }
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
    }

    private suspend fun resumeDictionaryResolutionIfNeeded() {
        val unknownPrograms = db.recordedProgramDao().getTitlesNotInDictionary()
        val unknownBaseTitlesToOriginals = mutableMapOf<String, MutableSet<String>>()

        unknownPrograms.forEach { prog ->
            val baseTitle = TitleNormalizer.extractDisplayTitle(prog.title)
            unknownBaseTitlesToOriginals.getOrPut(baseTitle) { mutableSetOf() }.add(prog.title)
        }

        if (unknownBaseTitlesToOriginals.isNotEmpty()) {
            Log.i(
                TAG,
                "Resuming Wikipedia dictionary resolution for ${unknownBaseTitlesToOriginals.size} titles."
            )
            resolveUnknownSeriesNamesBackground(unknownBaseTitlesToOriginals, false)
        }
    }

    private suspend fun resolveUnknownSeriesNamesBackground(
        unknownMap: Map<String, Set<String>>,
        isInitialSyncPhase: Boolean
    ) {
        withContext(Dispatchers.IO) {
            try {
                _syncProgress.value = SyncProgress(
                    isSyncing = true,
                    isInitialBuild = false,
                    isInitialSyncPhase = isInitialSyncPhase,
                    message = "シリーズ辞書を自動生成中...",
                    current = 0,
                    total = unknownMap.size
                )

                var processedCount = 0
                val programDao = db.recordedProgramDao()

                val entries = unknownMap.entries.toList()
                val chunkSize = 50

                entries.chunked(chunkSize).forEach { chunk ->
                    currentCoroutineContext().ensureActive()

                    val newDictEntries = mutableListOf<AiSeriesDictionaryEntity>()

                    for ((baseTitle, originalTitles) in chunk) {
                        currentCoroutineContext().ensureActive()
                        val canonicalTitle = WikipediaNormalizer.getCanonicalTitle(baseTitle)

                        delay(800)

                        val finalSeriesName = canonicalTitle ?: baseTitle
                        processedCount++
                        _syncProgress.value = _syncProgress.value.copy(current = processedCount)

                        originalTitles.forEach { originalTitle ->
                            newDictEntries.add(
                                AiSeriesDictionaryEntity(
                                    originalTitle = originalTitle,
                                    normalizedSeriesName = finalSeriesName,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
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
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Background dictionary generation failed", e)
            } finally {
                _syncProgress.value = SyncProgress(
                    isSyncing = false,
                    isInitialBuild = false,
                    isInitialSyncPhase = false
                )
            }
        }
    }

    suspend fun isInitialBuildCompleted(): Boolean = withContext(Dispatchers.IO) {
        db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true
    }
}