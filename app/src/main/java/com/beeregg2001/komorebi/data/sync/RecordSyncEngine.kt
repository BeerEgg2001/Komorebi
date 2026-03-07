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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

                        if (currentMeta.isInitialBuildCompleted && !forceFullSync) {
                            val allPageItemsMatch = entities.all { entity ->
                                val local = programDao.getById(entity.id)
                                local != null && local.id == entity.id && local.title == entity.title // 簡易チェック
                            }
                            if (allPageItemsMatch) {
                                isCompleted = true
                                break
                            }
                        }

                        // ★DB保存前にシリーズ名を確定させる！
                        val enrichedEntities = enrichEntitiesWithSeriesName(entities)

                        db.withTransaction {
                            programDao.upsertAll(enrichedEntities)
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
                            if (allFetchedIds.isNotEmpty()) db.recordedProgramDao()
                                .deleteOrphans(allFetchedIds)
                        }
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
                local != null && local.id == entity.id && local.title == entity.title
            }

            if (!allPageItemsMatch) {
                // ★差分更新時もシリーズ名を確定させる！
                val enrichedEntities = enrichEntitiesWithSeriesName(entities)
                db.withTransaction { programDao.upsertAll(enrichedEntities) }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Smart sync error: ${e.message}")
        }
    }

    /**
     * DB保存前に、ローカル辞書とWikipedia APIを活用して SeriesName を付与する
     */
    private suspend fun enrichEntitiesWithSeriesName(entities: List<RecordedProgramEntity>): List<RecordedProgramEntity> {
        if (entities.isEmpty()) return emptyList()

        val dictionary = aiSeriesDictionaryDao.getAllDictionary()
            .associate { it.originalTitle to it.normalizedSeriesName }
        val unknownBaseTitles = mutableSetOf<String>()

        entities.forEach { entity ->
            if (!dictionary.containsKey(entity.title)) {
                unknownBaseTitles.add(TitleNormalizer.extractDisplayTitle(entity.title))
            }
        }

        if (unknownBaseTitles.isNotEmpty()) {
            var processed = 0
            _syncProgress.value = _syncProgress.value.copy(
                isSyncing = true,
                message = "シリーズ辞書を自動生成中...",
                current = 0,
                total = unknownBaseTitles.size
            )

            val newDictEntries = mutableListOf<AiSeriesDictionaryEntity>()
            for (baseTitle in unknownBaseTitles) {
                val canonicalTitle = WikipediaNormalizer.getCanonicalTitle(baseTitle)
                val finalSeriesName = canonicalTitle ?: baseTitle

                // このbaseTitleに属するすべてのoriginalTitleに対して辞書エントリを作成
                entities.filter { TitleNormalizer.extractDisplayTitle(it.title) == baseTitle }
                    .map { it.title }.distinct()
                    .forEach { originalTitle ->
                        newDictEntries.add(
                            AiSeriesDictionaryEntity(
                                originalTitle = originalTitle,
                                normalizedSeriesName = finalSeriesName,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }

                processed++
                _syncProgress.value = _syncProgress.value.copy(current = processed)
                delay(300) // APIレートリミット対策
            }
            if (newDictEntries.isNotEmpty()) aiSeriesDictionaryDao.insertAll(newDictEntries)
        }

        // 最新の辞書を再取得して適用
        val updatedDictionary = aiSeriesDictionaryDao.getAllDictionary()
            .associate { it.originalTitle to it.normalizedSeriesName }

        return entities.map { entity ->
            val finalSeriesName =
                updatedDictionary[entity.title] ?: TitleNormalizer.extractDisplayTitle(entity.title)
            entity.copy(seriesName = finalSeriesName)
        }
    }
}