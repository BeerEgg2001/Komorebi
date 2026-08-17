package com.beeregg2001.komorebi.data.sync

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.dao.AiSeriesDictionaryDao
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationSeriesDictionary
import com.beeregg2001.komorebi.data.local.entity.AiSeriesDictionaryEntity
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import com.beeregg2001.komorebi.util.TitleNormalizer
import com.beeregg2001.komorebi.util.WikipediaNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordSyncEngine"
private const val KNOWN_RECORD_STOP_THRESHOLD = 1

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
        get() = when {
            total > 0 -> "$message ($current / $total)"
            current > 0 -> "$message ($current 件取得中)"
            else -> message
        }
}

@Singleton
class RecordSyncEngine @Inject constructor(
    private val recordProvider: RecordProvider,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val aiSeriesDictionaryDao: AiSeriesDictionaryDao,
    private val epgStationSeriesDictionary: EpgStationSeriesDictionary,
    @ApplicationContext private val context: Context
) {
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val syncMutex = Mutex()
    private val jobMutex = Mutex()
    private val dictionaryMutex = Mutex()
    private val smartSyncMutex = Mutex()
    private var activeSyncJob: Job? = null

    private data class SyncProfile(
        val name: String,
        val parallelism: Int,
        val fetchLimit: Int,
        val batchSize: Int,
        val initialDelayMs: Long,
        val normalDelayMs: Long
    )

    private val isThrottled = AtomicBoolean(false)

    private val syncProfile: SyncProfile by lazy {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemoryMb = memoryInfo.totalMem / (1024L * 1024L)
        val memoryClassMb = activityManager.memoryClass
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val isLowRamDevice = activityManager.isLowRamDevice

        val profile = if (
            isLowRamDevice ||
            totalMemoryMb < 1200L ||
            cpuCores <= 2
        ) {
            // 1GB級はレスポンスとDBバッファを小さくしてメモリ使用量を抑える。
            SyncProfile(
                name = "low",
                parallelism = 2,
                fetchLimit = 50,
                batchSize = 50,
                initialDelayMs = 25L,
                normalDelayMs = 25L
            )
        } else if (
            totalMemoryMb >= 3072L &&
            cpuCores >= 6
        ) {
            // 高性能機は最大6本まで並列化し、通信回数を最小化する。
            SyncProfile(
                name = "high",
                parallelism = 6,
                fetchLimit = 200,
                batchSize = 400,
                initialDelayMs = 0L,
                normalDelayMs = 0L
            )
        } else {
            // 2GB級を含む大半のTV端末は標準プロファイルで動かす。
            SyncProfile(
                name = "standard",
                parallelism = 4,
                fetchLimit = 100,
                batchSize = 200,
                initialDelayMs = 0L,
                normalDelayMs = 0L
            )
        }

        Log.i(
            TAG,
            "Sync profile=" + profile.name +
                ", totalMemoryMb=" + totalMemoryMb +
                ", memoryClassMb=" + memoryClassMb +
                ", cpuCores=" + cpuCores +
                ", isLowRamDevice=" + isLowRamDevice +
                ", parallelism=" + profile.parallelism +
                ", fetchLimit=" + profile.fetchLimit +
                ", batchSize=" + profile.batchSize +
                ", initialDelayMs=" + profile.initialDelayMs +
                ", normalDelayMs=" + profile.normalDelayMs
        )
        profile
    }

    // 再生中は通信を1本に絞り、端末とプレイヤーの取り合いを避ける。
    fun setThrottled(enabled: Boolean) {
        isThrottled.set(enabled)
        Log.i(TAG, "Playback sync throttle=" + if (enabled) "on" else "off")
    }

    private fun hasRecordChanged(
        local: RecordedProgramEntity?,
        remote: RecordedProgramEntity
    ): Boolean {
        return local == null ||
                local.title != remote.title ||
                local.isRecording != remote.isRecording
    }

    fun clearError() {
        _syncProgress.value = _syncProgress.value.copy(error = null)
    }

    fun launchSyncAllRecords(forceFullSync: Boolean = false) {
        if (!forceFullSync && syncMutex.isLocked) {
            Log.i(TAG, "launchSyncAllRecords: sync already running. Skipping.")
            return
        }
        Log.i(TAG, "launchSyncAllRecords: launching. forceFullSync=$forceFullSync")
        engineScope.launch {
            syncAllRecords(forceFullSync)
        }
    }

    fun launchSmartSync() {
        engineScope.launch {
            smartSync()
        }
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
                    val syncStartedAtNanos = System.nanoTime()
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

                    // 完了済みの通常更新は降順ページングの先頭から確認する。
                    // lastSyncedPage は未完了の初期構築を再開する場合だけ使う。
                    val canResumeInitialBuild =
                        currentMeta.lastSyncedPage > 0 &&
                                !currentMeta.isInitialBuildCompleted &&
                                !forceFullSync
                    var currentPage =
                        if (canResumeInitialBuild) currentMeta.lastSyncedPage + 1 else 1
                    val isResumed = canResumeInitialBuild
                    var isCompleted = false
                    var processedCount = if (isResumed) programDao.getAllIds().size else 0

                    // 省メモリ機では17081件規模のID集合を作らず、孤児削除も省略する。
                    val needsOrphanDeletion =
                        !isResumed &&
                            (isInitial || forceFullSync) &&
                            syncProfile.name != "low"
                    val allFetchedIds = if (needsOrphanDeletion) mutableSetOf<Int>() else null

                    val dictionary: Map<String, String> =
                        if (syncProfile.name == "low" && isInitial) {
                        Log.i(TAG, "Low RAM device: skipping dictionary preload to save memory.")
                        emptyMap()
                    } else {
                        aiSeriesDictionaryDao.getAllDictionary()
                            .associate { it.originalTitle to it.normalizedSeriesName }
                    }

                    val entityBuffer = mutableListOf<RecordedProgramEntity>()
                    val pageSemaphore = Semaphore(syncProfile.parallelism)
                    var knownUnchangedStreak = 0
                    var lastCompletedPage = currentPage - 1

                    while (!isCompleted) {
                        currentCoroutineContext().ensureActive()
                        val parallelism = if (isThrottled.get()) 1 else syncProfile.parallelism
                        val windowSize = if (isInitial) parallelism else 1
                        val windowPages = (currentPage until currentPage + windowSize).toList()

                        // DeferredはこのcoroutineScope内で必ずawaitし、
                        // 同期のキャンセル時も子コルーチンを確実に閉じる。
                        val fetchedPages = coroutineScope {
                            windowPages.map { page ->
                                async {
                                    pageSemaphore.withPermit {
                                        page to recordProvider.getRecordedPrograms(
                                            page = page,
                                            limit = syncProfile.fetchLimit
                                        )
                                    }
                                }
                            }.awaitAll().sortedBy { it.first }
                        }

                        if (fetchedPages.all { it.second.recordedPrograms.isEmpty() }) {
                            isCompleted = true
                            break
                        }

                        for ((page, response) in fetchedPages) {
                            currentCoroutineContext().ensureActive()
                            val programs = response.recordedPrograms
                            if (programs.isEmpty()) {
                                isCompleted = true
                                break
                            }

                            val entities = programs.map { RecordDataMapper.toEntity(it) }
                            allFetchedIds?.addAll(entities.map { it.id })

                            if (currentMeta.isInitialBuildCompleted && !forceFullSync) {
                                val pageIds = entities.map { it.id }
                                val localEntitiesMap =
                                    programDao.getByIds(pageIds).associateBy { it.id }
                                val hasPageChanges = entities.any { entity ->
                                    hasRecordChanged(localEntitiesMap[entity.id], entity)
                                }
                                val shouldStopAfterPage = entities.any { entity ->
                                    val local = localEntitiesMap[entity.id]
                                    val knownUnchanged =
                                        local != null &&
                                            !local.isRecording &&
                                            !hasRecordChanged(local, entity)
                                    knownUnchangedStreak = if (knownUnchanged) {
                                        knownUnchangedStreak + 1
                                    } else {
                                        0
                                    }
                                    knownUnchangedStreak >= KNOWN_RECORD_STOP_THRESHOLD
                                }
                                if (!hasPageChanges && shouldStopAfterPage) {
                                    isCompleted = true
                                    break
                                }
                                if (shouldStopAfterPage) {
                                    isCompleted = true
                                }
                            }

                            val enrichedEntities = entities.map { entity ->
                                val baseTitle = TitleNormalizer.extractDisplayTitle(entity.title)
                                val finalSeriesName = dictionary[entity.title] ?: baseTitle
                                entity.copy(seriesName = finalSeriesName)
                            }
                            entityBuffer.addAll(enrichedEntities)

                            // 最初の3ページはページごとに反映し、最新録画をすぐ表示する。
                            val shouldFlushImmediately = isInitial && page <= 3
                            if (shouldFlushImmediately || entityBuffer.size >= syncProfile.batchSize) {
                                db.withTransaction {
                                    programDao.upsertAll(entityBuffer)
                                    val newMeta = currentMeta.copy(
                                        lastSyncedPage = page,
                                        lastSyncedAt = System.currentTimeMillis()
                                    )
                                    metaDao.upsert(newMeta)
                                    currentMeta = newMeta
                                }
                                entityBuffer.clear()
                                lastCompletedPage = page
                                if (_syncProgress.value.isInitialBuild && shouldFlushImmediately) {
                                    _syncProgress.value =
                                        _syncProgress.value.copy(isInitialBuild = false)
                                }
                            } else {
                                lastCompletedPage = page
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
                                break
                            }
                        }

                        if (!isCompleted) {
                            currentPage = lastCompletedPage + 1
                            val waitMs = if (isThrottled.get()) {
                                maxOf(syncProfile.initialDelayMs, 250L)
                            } else if (isInitial) {
                                syncProfile.initialDelayMs
                            } else {
                                syncProfile.normalDelayMs
                            }
                            if (waitMs > 0L) delay(waitMs)
                        }
                    }

                    if (entityBuffer.isNotEmpty()) {
                        db.withTransaction {
                            programDao.upsertAll(entityBuffer)
                            val newMeta = currentMeta.copy(
                                lastSyncedPage = lastCompletedPage,
                                lastSyncedAt = System.currentTimeMillis()
                            )
                            metaDao.upsert(newMeta)
                            currentMeta = newMeta
                        }
                        entityBuffer.clear()
                    }

                    if (isCompleted) {
                        if (needsOrphanDeletion && allFetchedIds != null && allFetchedIds.isNotEmpty()) {
                            val localIds = programDao.getAllIds()
                            val idsToDelete = localIds.toSet() - allFetchedIds
                            if (idsToDelete.isNotEmpty()) {
                                Log.i(TAG, "Deleting ${idsToDelete.size} orphan records.")
                                idsToDelete.chunked(900).forEach { chunk ->
                                    programDao.deleteByIds(chunk)
                                }
                            }
                        }

                        metaDao.upsert(
                            currentMeta.copy(
                                lastSyncedPage = 0,
                                lastSyncedAt = System.currentTimeMillis(),
                                isInitialBuildCompleted = true
                            )
                        )
                    }

                    val elapsedSeconds =
                        (System.nanoTime() - syncStartedAtNanos) / 1_000_000_000.0
                    val throughput =
                        if (elapsedSeconds > 0.0) processedCount / elapsedSeconds else 0.0
                    Log.i(
                        TAG,
                        "Sync completed. elapsedSeconds=" + elapsedSeconds +
                            ", totalCount=" + processedCount +
                            ", throughputPerSecond=" + throughput
                    )

                    _syncProgress.value = _syncProgress.value.copy(
                        message = "シリーズ辞書を準備中...",
                        current = 0,
                        total = 0
                    )
                    isSyncSuccessful = true

                } catch (e: CancellationException) {
                    Log.i(TAG, "Sync gracefully cancelled: ${e.message}")
                    _syncProgress.value = SyncProgress(isSyncing = false)
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

        if (isSyncSuccessful) {
            engineScope.launch {
                startDictionaryResolutionLoop()
            }
        }
    }

    suspend fun clearDatabase() {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
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
        }
    }

    suspend fun smartSync() {
        if (!smartSyncMutex.tryLock()) {
            Log.i(TAG, "smartSync: already running. Skipping.")
            return
        }

        try {
            if (syncMutex.isLocked) {
                Log.i(TAG, "smartSync: sync already running. Skipping.")
                return
            }

            val currentMeta = withContext(Dispatchers.IO) { db.syncMetaDao().getSyncMeta() }
            if (currentMeta == null || !currentMeta.isInitialBuildCompleted) {
                Log.i(TAG, "smartSync: initial build not completed. Skipping.")
                return
            }

            val currentJob = currentCoroutineContext().job
            var isSyncSuccessful = false

            syncMutex.withLock {
                jobMutex.withLock { activeSyncJob = currentJob }
                withContext(Dispatchers.IO) {
                    try {
                        val programDao = db.recordedProgramDao()
                        currentCoroutineContext().ensureActive()

                        val response = recordProvider.getRecordedPrograms(page = 1)
                        val apiPrograms = response.recordedPrograms
                        if (apiPrograms.isEmpty()) return@withContext

                        val entities = apiPrograms.map { RecordDataMapper.toEntity(it) }
                        val pageIds = entities.map { it.id }
                        val localEntitiesMap = programDao.getByIds(pageIds).associateBy { it.id }

                        val allPageItemsMatch =
                            entities.size == localEntitiesMap.size && entities.all { entity ->
                                val local = localEntitiesMap[entity.id]
                                local != null &&
                                        local.title == entity.title &&
                                        local.isRecording == entity.isRecording
                            }

                        val hasLocalRecording = localEntitiesMap.values.any { it.isRecording }

                        if (!allPageItemsMatch || hasLocalRecording) {
                            val dictionary = aiSeriesDictionaryDao.getAllDictionary()
                                .associate { it.originalTitle to it.normalizedSeriesName }

                            val enrichedEntities = entities.map { entity ->
                                val baseTitle = TitleNormalizer.extractDisplayTitle(entity.title)
                                val finalSeriesName = dictionary[entity.title] ?: baseTitle
                                entity.copy(seriesName = finalSeriesName)
                            }

                            db.withTransaction { programDao.upsertAll(enrichedEntities) }
                            Log.i(
                                TAG,
                                "smartSync: Detected changes or stuck recordings. Updated DB."
                            )
                        } else {
                            Log.i(TAG, "smartSync: No changes detected. Skipped.")
                        }

                        isSyncSuccessful = true

                    } catch (e: CancellationException) {
                        Log.i(TAG, "Smart sync gracefully cancelled: ${e.message}")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Smart sync error: ${e.message}", e)
                    } finally {
                        jobMutex.withLock {
                            if (activeSyncJob == currentJob) activeSyncJob = null
                        }
                    }
                }
            }

            if (isSyncSuccessful) {
                if (!dictionaryMutex.isLocked) {
                    engineScope.launch { startDictionaryResolutionLoop() }
                } else {
                    Log.i(TAG, "smartSync: dictionary already running, skipping launch.")
                }
            }

        } finally {
            smartSyncMutex.unlock()
        }
    }

    private suspend fun startDictionaryResolutionLoop() {
        // EPGStation はサーバー側のシリーズ情報を使うため、辞書生成をスキップする。
        if (settingsRepository.backendType.first() == "EPGSTATION") {
            Log.i(TAG, "EPGStation はサーバー側のシリーズ情報を使うため、辞書生成をスキップします。")
            _syncProgress.value = SyncProgress(
                isSyncing = false,
                isInitialBuild = false,
                isInitialSyncPhase = false
            )
            return
        }

        if (!dictionaryMutex.tryLock()) {
            Log.i(TAG, "Dictionary resolution is already running. Skipping.")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val programDao = db.recordedProgramDao()
                val totalUnknown = programDao.getUnknownTitlesCount()
                if (totalUnknown == 0) {
                    _syncProgress.value = SyncProgress(
                        isSyncing = false,
                        isInitialBuild = false,
                        isInitialSyncPhase = false
                    )
                    return@withContext
                }

                _syncProgress.value = SyncProgress(
                    isSyncing = true,
                    isInitialBuild = false,
                    isInitialSyncPhase = false,
                    message = "シリーズ辞書を自動生成中...",
                    current = 0,
                    total = totalUnknown
                )

                var processedCount = 0
                val chunkSize = 100
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val unknownTitles = programDao.getUnknownTitles(limit = chunkSize)
                    if (unknownTitles.isEmpty()) break

                    val baseTitleMap = unknownTitles.groupBy {
                        TitleNormalizer.extractDisplayTitle(it)
                    }
                    val resolvedBaseTitles = HashMap<String, String>()

                    for (baseTitle in baseTitleMap.keys) {
                        currentCoroutineContext().ensureActive()
                        val epgStationTitle = epgStationSeriesDictionary.resolve(baseTitle)
                        if (epgStationTitle != null) {
                            resolvedBaseTitles[baseTitle] = epgStationTitle
                            continue
                        }

                        try {
                            val canonicalTitle = WikipediaNormalizer.getCanonicalTitle(baseTitle)
                            resolvedBaseTitles[baseTitle] = canonicalTitle ?: baseTitle
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                Log.w(TAG, "Wikipedia lookup failed for '" + baseTitle + "': " + e.message)
                            }
                            resolvedBaseTitles[baseTitle] = baseTitle
                        }
                        delay(300)
                    }

                    val newDictEntries = unknownTitles.map { title ->
                        val baseTitle = TitleNormalizer.extractDisplayTitle(title)
                        val finalSeriesName = resolvedBaseTitles[baseTitle] ?: baseTitle
                        processedCount++
                        if (processedCount % 100 == 0 || processedCount == totalUnknown) {
                            _syncProgress.value = _syncProgress.value.copy(current = processedCount)
                        }
                        AiSeriesDictionaryEntity(
                            originalTitle = title,
                            normalizedSeriesName = finalSeriesName,
                            updatedAt = System.currentTimeMillis()
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

                    if (programDao.getUnknownTitlesCount() > 0) delay(500)
                }
                Log.i(TAG, "Dictionary resolution loop completed successfully.")
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
            dictionaryMutex.unlock()
        }
    }

    suspend fun isInitialBuildCompleted(): Boolean = withContext(Dispatchers.IO) {
        db.syncMetaDao().getSyncMeta()?.isInitialBuildCompleted == true
    }
}
