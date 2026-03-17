package com.beeregg2001.komorebi.data.sync

import android.app.ActivityManager
import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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

/**
 * 同期処理の進捗状況をUI層に伝えるためのデータクラス。
 */
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

/**
 * KonomiTVの録画番組データをローカルデータベース（Room）と同期するためのコアエンジンです。
 * ページネーション処理、差分更新、OOM（メモリ不足）対策、不要データの削除、
 * およびバックグラウンドでの「AI名寄せ（シリーズ辞書の自動生成）」を統括します。
 */
@Singleton
class RecordSyncEngine @Inject constructor(
    private val apiService: KonomiApi,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val aiSeriesDictionaryDao: AiSeriesDictionaryDao,
    @ApplicationContext private val context: Context
) {
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    // エンジン全体で共有するコルーチンスコープ（親ジョブがキャンセルされても他のジョブに影響しないSupervisorJob）
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 複数スレッドやユーザー操作からの同時実行を防ぐための排他制御（Mutex）群
    private val syncMutex = Mutex()         // 全件同期の重複実行ブロック用
    private val jobMutex = Mutex()          // ジョブ参照の書き換え保護用
    private val dictionaryMutex = Mutex()   // 辞書生成ループの重複実行ブロック用

    private val smartSyncMutex = Mutex()    // スマート同期の重複実行ブロック用
    private var activeSyncJob: Job? = null

    // ★低メモリ端末（Fire TV Stick等）の判定
    private val isLowRamDevice: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice
    }

    // ★低メモリ端末ではバッチサイズを小さく、通常端末では大きくする
    private val BATCH_SIZE get() = if (isLowRamDevice) 30 else 100

    // ★低メモリ端末ではGC後の待機時間を長くする
    private val GC_DELAY_MS get() = if (isLowRamDevice) 2000L else 1200L

    fun clearError() {
        _syncProgress.value = _syncProgress.value.copy(error = null)
    }

    /**
     * 全件同期を開始するための公開エントリーポイント。
     * すでに同期中の場合は重複実行をスキップします。
     */
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

    /**
     * スマート同期を開始するための公開エントリーポイント。
     */
    fun launchSmartSync() {
        engineScope.launch {
            smartSync()
        }
    }

    // 既存機能を崩さないよう public のままにしますが、外部からは必ず launchSyncAllRecords を使わせます
    /**
     * 録画データの全件同期（または途中からの再開）を行うメインロジック。
     */
    suspend fun syncAllRecords(forceFullSync: Boolean = false) {
        val currentJob = currentCoroutineContext().job

        // 強制フル同期が要求された場合、現在走っている同期ジョブをキャンセルして待機する
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

                    // 同期の進捗状況（どこまでページを読み込んだか）を復元
                    var currentMeta = metaDao.getSyncMeta() ?: SyncMetaEntity(
                        id = 1,
                        lastSyncedPage = 0,
                        lastSyncedAt = 0L,
                        isInitialBuildCompleted = false
                    )

                    // 強制フル同期の場合はローカルの全データと進捗を初期化
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

                    // 前回中断した次のページから再開する（フル同期なら1ページ目から）
                    var currentPage =
                        if (currentMeta.lastSyncedPage > 0 && !forceFullSync) currentMeta.lastSyncedPage + 1 else 1
                    val isResumed = currentPage > 1
                    var isCompleted = false
                    var processedCount = if (isResumed) programDao.getAllIds().size else 0

                    // 初期構築（全件取得）の場合のみ、サーバーから消えたデータを特定するためのセットを用意
                    val needsOrphanDeletion = !isResumed && (isInitial || forceFullSync)
                    val allFetchedIds = if (needsOrphanDeletion) mutableSetOf<Int>() else null

                    // ★低メモリ端末対応: isLowRamDeviceの場合は辞書をメモリに全展開せず空Mapを渡す。
                    // series_nameの解決は後続の startDictionaryResolutionLoop() で行われるため
                    // 初回ビルドの品質への影響はなく、OOM発生を回避できる。
                    val dictionary: Map<String, String> = if (isLowRamDevice && isInitial) {
                        Log.i(TAG, "Low RAM device: skipping dictionary preload to save memory.")
                        emptyMap()
                    } else {
                        aiSeriesDictionaryDao.getAllDictionary()
                            .associate { it.originalTitle to it.normalizedSeriesName }
                    }

                    val entityBuffer = mutableListOf<RecordedProgramEntity>()

                    // ページ単位でAPIから録画番組を取得するループ
                    while (!isCompleted) {
                        currentCoroutineContext().ensureActive()

                        Log.i(TAG, "Fetching page: $currentPage")
                        val response = apiService.getRecordedPrograms(page = currentPage)
                        val programs = response.recordedPrograms

                        // サーバー側からのデータが空になれば取得完了
                        if (programs.isEmpty()) {
                            isCompleted = true
                            break
                        }

                        run {
                            val entities = programs.map { RecordDataMapper.toEntity(it) }
                            allFetchedIds?.addAll(entities.map { it.id })

                            // 差分更新時の最適化：
                            // 取得したページ内の番組群が、ローカルDBの番組群と「順序も含めて」完全に一致した場合、
                            // これ以降の古いページに変更はないと判断して同期を打ち切る
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
                                    return@run
                                }
                            }

                            // 辞書引きによるシリーズ名（名寄せキー）の割り当て
                            val enrichedEntities = entities.map { entity ->
                                val baseTitle = TitleNormalizer.extractDisplayTitle(entity.title)
                                val finalSeriesName = dictionary[entity.title] ?: baseTitle
                                entity.copy(seriesName = finalSeriesName)
                            }

                            entityBuffer.addAll(enrichedEntities)
                        }

                        val processedThisTime = programs.size
                        val totalCount = response.total.takeIf { it > 0 } ?: 0

                        // バッチサイズ（端末スペックに応じて可変）に達したらDBに書き込む
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

                        processedCount += processedThisTime

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
                            // ★修正 OOM対策:
                            // Fire TV 等の低メモリ端末でGC(ガベージコレクション)が追いつくよう、
                            // 明示的にヒントを出しつつ長めの休憩（1.2秒）を取らせる。
                            // これにより数千件のデータでもメモリがパンクせずに完走します。
                            if (isInitial) {
                                System.gc()
                                delay(GC_DELAY_MS)
                            } else {
                                delay(if (isLowRamDevice) 500L else 300L)
                            }
                        }
                    }

                    // ループ終了後にバッファに残っている端数をDBに書き込む
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
                        // サーバーから削除された番組（孤児レコード）をローカルDBからも削除する
                        if (needsOrphanDeletion && allFetchedIds != null && allFetchedIds.isNotEmpty()) {
                            val localIds = programDao.getAllIds()
                            val idsToDelete = localIds.toSet() - allFetchedIds
                            if (idsToDelete.isNotEmpty()) {
                                Log.i(TAG, "Deleting ${idsToDelete.size} orphan records.")
                                // 一度に大量に削除するとSQLiteの上限に引っかかるため分割(chunk)して削除
                                idsToDelete.chunked(900).forEach { chunk ->
                                    programDao.deleteByIds(chunk)
                                }
                            }
                        }

                        // 初期構築完了フラグを立てる
                        metaDao.upsert(
                            currentMeta.copy(
                                lastSyncedAt = System.currentTimeMillis(),
                                isInitialBuildCompleted = true
                            )
                        )
                    }

                    _syncProgress.value = _syncProgress.value.copy(
                        message = "シリーズ辞書を準備中...",
                        current = 0,
                        total = 0
                    )
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

        // 同期が正常に完了した場合、バックグラウンドで名寄せ辞書の生成処理をキックする
        if (isSyncSuccessful) {
            engineScope.launch {
                startDictionaryResolutionLoop()
            }
        }
    }

    suspend fun clearDatabase() {
        // ★修正: 他の処理中に呼ばれてもクラッシュしないよう排他ロックをかける
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

    /**
     * 高速な差分同期（スマート同期）処理。
     * 最新の1ページ目のみを取得し、ローカルDBと完全に一致すれば「変更なし」とみなして即座に終了します。
     * 録画の追加や削除があった場合のみDBを更新する、通信量・負荷に優しいエコな同期機能です。
     */
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

                        val response = apiService.getRecordedPrograms(page = 1)
                        val apiPrograms = response.recordedPrograms
                        if (apiPrograms.isEmpty()) return@withContext

                        val entities = apiPrograms.map { RecordDataMapper.toEntity(it) }
                        val pageIds = entities.map { it.id }
                        val localEntitiesMap = programDao.getByIds(pageIds).associateBy { it.id }

                        // 1ページ目の要素がすべてローカルDBの最新情報と一致しているかチェック
                        val allPageItemsMatch =
                            entities.size == localEntitiesMap.size && entities.all { entity ->
                                val local = localEntitiesMap[entity.id]
                                local != null && local.title == entity.title
                            }

                        // もし差分があれば（録画が追加された、メタデータが更新された等）、1ページ目だけを上書き
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
                            if (activeSyncJob == currentJob) activeSyncJob = null
                        }
                    }
                }
            }

            // 更新があった番組が辞書に未登録の新しい番組名だった場合、辞書解決ループを回す
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

    /**
     * バックグラウンドで実行される「シリーズ名寄せ（辞書生成）」のループ処理。
     * 正規化されていない（Unknownな）番組名をDBから抽出し、
     * Wikipedia APIを叩いて正式なシリーズ名を取得し、辞書と番組データを更新します。
     * Wikipedia API のレートリミットを回避するため、意図的な遅延（delay）を入れて処理します。
     */
    private suspend fun startDictionaryResolutionLoop() {
        if (!dictionaryMutex.tryLock()) {
            Log.i(TAG, "Dictionary resolution is already running. Skipping.")
            return
        }

        Log.i(TAG, "startDictionaryResolutionLoop: started")

        try {
            withContext(Dispatchers.IO) {
                val programDao = db.recordedProgramDao()

                // まだ辞書解決されていない番組の数をカウント
                val totalUnknown = programDao.getUnknownTitlesCount()
                Log.i(TAG, "startDictionaryResolutionLoop: totalUnknown=$totalUnknown")

                if (totalUnknown == 0) {
                    Log.i(TAG, "No unknown titles found. Dictionary is up to date.")
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
                Log.i(TAG, "startDictionaryResolutionLoop: progress updated to 自動生成中")

                var processedCount = 0

                while (true) {
                    currentCoroutineContext().ensureActive()
                    // 50件ずつフェッチして処理
                    val unknownTitles = programDao.getUnknownTitles(limit = 50)
                    if (unknownTitles.isEmpty()) break

                    val newDictEntries = mutableListOf<AiSeriesDictionaryEntity>()

                    for (title in unknownTitles) {
                        currentCoroutineContext().ensureActive()
                        val baseTitle = TitleNormalizer.extractDisplayTitle(title)

                        // Wikipedia APIで名寄せのための正規タイトルを取得
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

                        // APIの叩きすぎを防ぐ（レートリミット対策）
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

                    // 生成した辞書エントリを保存し、紐づく番組データの seriesName を一括更新
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

                    // まだ残っていれば、少し休憩してから次のバッチへ
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