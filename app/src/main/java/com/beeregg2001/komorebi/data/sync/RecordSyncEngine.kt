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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordSyncEngine"

data class SyncProgress(
    val isSyncing: Boolean = false,
    val isInitialBuild: Boolean = false, // アプリ全体の起動ブロック用
    val isInitialSyncPhase: Boolean = false, // ビデオタブの操作ブロック用
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
    private var activeSyncJob: Job? = null // ★追加: 現在実行中のジョブを保持する

    private val BATCH_SIZE = 300

    fun clearError() {
        _syncProgress.value = _syncProgress.value.copy(error = null)
    }

    suspend fun syncAllRecords(forceFullSync: Boolean = false) {
        val currentJob = currentCoroutineContext().job

        // ★追加: FullSyncが要求された場合、現在実行中の同期処理（古いURLでの処理など）を即座に強制キャンセルする
        if (forceFullSync) {
            jobMutex.withLock {
                activeSyncJob?.cancel()
            }
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
                        currentMeta =
                            currentMeta.copy(lastSyncedPage = 0, isInitialBuildCompleted = false)
                    }

                    val hasExistingData = programDao.getAllIds().isNotEmpty()

                    // ★修正: forceFullSync=true の時は問答無用でブロック画面（isInitial = true）にする
                    val isInitial =
                        forceFullSync || (!currentMeta.isInitialBuildCompleted && !hasExistingData)
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
                    var isCompleted = false
                    var processedCount = 0

                    val allFetchedIds = mutableListOf<Int>()
                    val dictionary = aiSeriesDictionaryDao.getAllDictionary()
                        .associate { it.originalTitle to it.normalizedSeriesName }
                    val unknownBaseTitlesToOriginals = mutableMapOf<String, MutableSet<String>>()
                    val entityBuffer = mutableListOf<RecordedProgramEntity>()

                    while (!isCompleted) {
                        // ★追加: ジョブがキャンセルされていたら例外を投げてループを即脱出する
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
                            val allPageItemsMatch = entities.all { entity ->
                                val local = programDao.getById(entity.id)
                                local != null && local.id == entity.id && local.title == entity.title
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
                        val shouldDeleteOrphans =
                            !currentMeta.isInitialBuildCompleted || forceFullSync
                        if (shouldDeleteOrphans && allFetchedIds.isNotEmpty()) {
                            val localIds = programDao.getAllIds()
                            val idsToDelete = localIds.toSet() - allFetchedIds.toSet()
                            if (idsToDelete.isNotEmpty()) {
                                Log.i(TAG, "Deleting ${idsToDelete.size} orphan records.")
                                idsToDelete.chunked(900).forEach { chunk ->
                                    programDao.deleteByIds(chunk)
                                }
                            }
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
                    // ★追加: キャンセルされた場合は正常な中断として処理を終わらせる
                    Log.i(TAG, "Sync cancelled by forceFullSync.")
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
        db.syncMetaDao().upsert(
            SyncMetaEntity(
                id = 1,
                lastSyncedPage = 0,
                lastSyncedAt = 0L,
                isInitialBuildCompleted = false
            )
        )
    }

    // ★修正: smartSyncも実行中にキャンセルできるように activeSyncJob の管理下に入れます
    suspend fun smartSync() {
        val currentJob = currentCoroutineContext().job

        syncMutex.withLock {
            jobMutex.withLock {
                activeSyncJob = currentJob
            }
            withContext(Dispatchers.IO) {
                try {
                    val metaDao = db.syncMetaDao()
                    val programDao = db.recordedProgramDao()

                    val currentMeta = metaDao.getSyncMeta()
                    if (currentMeta == null || !currentMeta.isInitialBuildCompleted) {
                        syncAllRecords(forceFullSync = false)
                        return@withContext
                    }

                    resumeDictionaryResolutionIfNeeded()

                    currentCoroutineContext().ensureActive()

                    val response = apiService.getRecordedPrograms(page = 1)
                    val apiPrograms = response.recordedPrograms
                    if (apiPrograms.isEmpty()) return@withContext

                    val entities = apiPrograms.map { RecordDataMapper.toEntity(it) }
                    val allPageItemsMatch = entities.all { entity ->
                        val local = programDao.getById(entity.id)
                        local != null && local.id == entity.id && local.title == entity.title
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
        val allPrograms = db.recordedProgramDao().getAllPrograms()
        val dictionary = aiSeriesDictionaryDao.getAllDictionary()
            .map { it.originalTitle }.toSet()

        val unknownBaseTitlesToOriginals = mutableMapOf<String, MutableSet<String>>()

        allPrograms.forEach { prog ->
            if (!dictionary.contains(prog.title)) {
                val baseTitle = TitleNormalizer.extractDisplayTitle(prog.title)
                unknownBaseTitlesToOriginals.getOrPut(baseTitle) { mutableSetOf() }.add(prog.title)
            }
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

                val semaphore = Semaphore(3)
                val processedCount = AtomicInteger(0)

                val deferreds = unknownMap.map { (baseTitle, originalTitles) ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive() // キャンセル検知
                            val canonicalTitle = WikipediaNormalizer.getCanonicalTitle(baseTitle)
                            delay(100)

                            val finalSeriesName = canonicalTitle ?: baseTitle
                            val currentProcessed = processedCount.incrementAndGet()
                            _syncProgress.value =
                                _syncProgress.value.copy(current = currentProcessed)

                            originalTitles.map { originalTitle ->
                                AiSeriesDictionaryEntity(
                                    originalTitle = originalTitle,
                                    normalizedSeriesName = finalSeriesName,
                                    updatedAt = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                }

                val newDictEntries = deferreds.awaitAll().flatten()

                if (newDictEntries.isNotEmpty()) {
                    db.withTransaction {
                        aiSeriesDictionaryDao.insertAll(newDictEntries)
                        val programDao = db.recordedProgramDao()
                        val allPrograms = programDao.getAllPrograms()

                        val latestDict = aiSeriesDictionaryDao.getAllDictionary()
                            .associate { it.originalTitle to it.normalizedSeriesName }

                        val updatedPrograms = allPrograms.map { prog ->
                            val updatedSeriesName = latestDict[prog.title] ?: prog.seriesName
                            prog.copy(seriesName = updatedSeriesName)
                        }

                        programDao.upsertAll(updatedPrograms)
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
}