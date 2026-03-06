package com.beeregg2001.komorebi.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.local.dao.AiSeriesDictionaryDao
import com.beeregg2001.komorebi.data.local.entity.AiSeriesDictionaryEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity
import com.beeregg2001.komorebi.data.mapper.RecordDataMapper
import com.beeregg2001.komorebi.util.TitleNormalizer
import com.beeregg2001.komorebi.util.WikipediaNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                    val allFetchedTitles = mutableSetOf<String>()

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
                        allFetchedTitles.addAll(programs.map { it.title })

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

                        // 新機能：Wikipedia APIによる名寄せ処理の実行
                        runWikipediaNormalizationIfNeeded(allFetchedTitles.toList())
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

                // 差分で取得したタイトルを渡す
                val newTitles = apiPrograms.map { it.title }
                runWikipediaNormalizationIfNeeded(newTitles)
            }
        } catch (e: Exception) {
            Log.i(TAG, "Smart sync error: ${e.message}")
        }
    }

    /**
     * Wikipedia APIを用いて、未知の番組タイトルを正式なシリーズ名に名寄せし、ローカルDBに保存します。
     * @param targetTitles 処理対象の生タイトルリスト
     */
    private suspend fun runWikipediaNormalizationIfNeeded(targetTitles: List<String>) {
        try {
            val existingDict =
                aiSeriesDictionaryDao.getAllDictionary().map { it.originalTitle }.toSet()
            val unknownPrograms = targetTitles.filter { it !in existingDict }.distinct()

            if (unknownPrograms.isEmpty()) return

            // 💡 超高速化ハック1: APIを叩く前に「クレンジング後のキーワード」でグループ化する
            // 戻り値の型: Map<String, List<String>> = Map<cleanKeyword, List<originalTitle>>
            val groupedByCleanKeyword = unknownPrograms.groupBy { originalTitle ->
                TitleNormalizer.extractDisplayTitle(originalTitle)
            }

            // Wikipediaに問い合わせる「ユニークなシリーズ名」のリスト（件数が劇的に減る）
            val uniqueCleanKeywords = groupedByCleanKeyword.keys.toList()

            Log.i(
                TAG,
                "Starting Wikipedia Normalization for ${uniqueCleanKeywords.size} unique series (from ${unknownPrograms.size} unknown episodes)."
            )

            var processed = 0
            val total = uniqueCleanKeywords.size
            _syncProgress.value = _syncProgress.value.copy(
                isSyncing = true,
                message = "シリーズ辞書を自動生成中...",
                current = 0,
                total = total
            )

            for (cleanKeyword in uniqueCleanKeywords) {
                // 1. Wikipedia API に正式名称を聞きに行く（各シリーズ1回だけ！）
                val canonicalTitle = WikipediaNormalizer.getCanonicalTitle(cleanKeyword)
                val finalSeriesName = canonicalTitle ?: cleanKeyword

                // 2. この cleanKeyword に属する「すべてのエピソードのタイトル」に対して辞書エンティティを一気に作成
                val originalTitlesInGroup = groupedByCleanKeyword[cleanKeyword] ?: emptyList()
                val entriesToInsert = originalTitlesInGroup.map { originalTitle ->
                    Log.d(TAG, "Normalized: [$originalTitle] -> [$finalSeriesName]")
                    AiSeriesDictionaryEntity(
                        originalTitle = originalTitle,
                        normalizedSeriesName = finalSeriesName,
                        updatedAt = System.currentTimeMillis()
                    )
                }

                // 3. グループ単位でDBに一括保存
                aiSeriesDictionaryDao.insertAll(entriesToInsert)

                processed++
                _syncProgress.value = _syncProgress.value.copy(current = processed)

                // 💡 超高速化ハック2: お行儀（レートリミット）の最適化
                // 1000msだと遅すぎるため、Wikipediaの規約（数リクエスト/秒）の範囲内で最速の 300ms に短縮
                delay(300)
            }

            Log.i(TAG, "Wikipedia Normalization completed.")
        } catch (e: Exception) {
            Log.e(TAG, "Wikipedia Normalization error", e)
        }
    }
}