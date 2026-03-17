package com.beeregg2001.komorebi.data.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Komorebi_Repo"

/**
 * KonomiTVバックエンド（API）およびローカルデータベース（Room）との通信を抽象化するリポジトリ。
 * ViewModelに対して、データの取得元（ネットワークかローカルか）を意識させずに
 * 統合されたデータアクセス手段を提供します。
 */
@Singleton
class KonomiRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao,
    private val lastChannelDao: LastChannelDao
) {
    // ==========================================
    // ユーザー設定・セッション管理
    // ==========================================
    private val _currentUser = MutableStateFlow<KonomiUser?>(null)
    val currentUser: StateFlow<KonomiUser?> = _currentUser.asStateFlow()

    /**
     * 現在ログインしているKonomiTVユーザーの情報を取得・更新します。
     * バックエンドのセッション維持（セッション切れ防止）の役割も兼ねています。
     */
    suspend fun refreshUser() {
        runCatching { apiService.getCurrentUser() }
            .onSuccess { _currentUser.value = it }
    }

    // ==========================================
    // チャンネル・録画リスト取得
    // ==========================================

    /**
     * 放送中のチャンネル一覧（地デジ、BS、CSなど）をAPIから取得します。
     */
    suspend fun getChannels(): ChannelApiResponse = apiService.getChannels()

    /**
     * 録画済みの番組一覧をページネーション形式でAPIから取得します。
     * （主にRecordSyncEngineでのバックグラウンド同期に使用されます）
     */
    suspend fun getRecordedPrograms(page: Int = 1) = apiService.getRecordedPrograms(page = page)

    /**
     * ★追加: 指定された録画番組の詳細情報（CMセクション、詳細なあらすじ等）を取得します。
     * ローカルDBではなく、常に最新の情報をKonomiTV APIから直接取得します。
     */
    suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> = runCatching {
        apiService.getRecordedProgram(videoId)
    }

    /**
     * KonomiTVのサーバーサイド検索を利用して録画番組を検索します。
     */
    suspend fun searchRecordedPrograms(keyword: String, page: Int = 1) = run {
        Log.d(TAG, "Calling API searchVideos. Keyword: $keyword, Page: $page")
        apiService.searchVideos(keyword = keyword, page = page)
    }

    /**
     * 動画のストリーミング再生中、サーバーに「まだ視聴している」ことを伝えるための生存信号（KeepAlive）を送信します。
     * これを定期的に送らないと、サーバー側で不要な通信とみなされてストリーミングが強制切断されます。
     */
    @UnstableApi
    suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
        runCatching {
            val response = apiService.keepAlive(videoId, quality, sessionId)
            if (!response.isSuccessful) {
                Log.w(TAG, "KeepAlive Failed: ${response.code()}")
            }
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ==========================================
    // マイリスト・視聴履歴の管理
    // ==========================================

    suspend fun getBookmarks(): Result<List<KonomiProgram>> =
        runCatching { apiService.getBookmarks() }

    /**
     * サーバー（KonomiTV）から全デバイスで共有されている視聴履歴を取得します。
     */
    suspend fun getWatchHistory(): Result<List<KonomiHistoryProgram>> =
        runCatching { apiService.getWatchHistory() }

    /**
     * ローカルDBにキャッシュされている視聴履歴（レジュームポイント）をFlowとして取得します。
     */
    fun getLocalWatchHistory() = watchHistoryDao.getAllHistory()

    suspend fun getHistoryEntityById(id: Int): WatchHistoryEntity? {
        return watchHistoryDao.getById(id)
    }

    /**
     * ★追加: 複数の録画番組の視聴履歴をローカルDBから一括で取得します。
     * 同期時の差分チェックなどでパフォーマンスを向上させるために使用します。
     */
    suspend fun getHistoryEntitiesByIds(ids: List<Int>): List<WatchHistoryEntity> {
        return watchHistoryDao.getByIds(ids)
    }

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity) {
        watchHistoryDao.insertOrUpdate(entity)
    }

    /**
     * ★追加: サーバーから取得した最新の視聴履歴リストをローカルDBへ一括保存します。
     */
    suspend fun saveAllToLocalHistory(entities: List<WatchHistoryEntity>) {
        watchHistoryDao.insertOrUpdateAll(entities)
    }

    // ==========================================
    // チャンネル視聴履歴の管理 (ザッピング・レジューム用)
    // ==========================================

    /**
     * アプリ内で最後に視聴したチャンネル（放送局）のリストをローカルDBから取得します。
     */
    fun getLastChannels() = lastChannelDao.getLastChannels()

    @OptIn(UnstableApi::class)
    suspend fun saveLastChannel(entity: LastChannelEntity) {
        lastChannelDao.insertOrUpdate(entity)
        Log.d(TAG, "Channel saved: ${entity.name}")
    }

    /**
     * ★追加: チャンネルの視聴履歴をすべて消去します（設定画面からのリセット用）。
     */
    suspend fun clearLastChannels() {
        lastChannelDao.clearAll()
    }

    // ==========================================
    // ニコニコ実況 (コメント) 関連
    // ==========================================

    suspend fun getJikkyoInfo(channelId: String) = runCatching {
        apiService.getJikkyoInfo(channelId)
    }

    suspend fun syncPlaybackPosition(programId: String, position: Double) {
        runCatching { apiService.updateWatchHistory(HistoryUpdateRequest(programId, position)) }
    }

    /**
     * 録画番組に対応する、当時のニコニコ実況のコメント（過去ログ）を取得します。
     */
    suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> = runCatching {
        val response = apiService.getArchivedJikkyo(videoId)
        if (response.is_success) response.comments else emptyList()
    }

    // ==========================================
    // 録画予約（EDCB連携）の管理
    // ==========================================

    /**
     * 現在EDCB（バックエンド）に登録されている「すべての録画予約（単発・自動）」を取得します。
     */
    suspend fun getReserves(): Result<List<ReserveItem>> = runCatching {
        apiService.getReserves().reservations
    }

    /**
     * 特定の番組を「単発予約」として新規追加します。
     */
    suspend fun addReserve(request: ReserveRequest): Result<Unit> = runCatching {
        val response = apiService.addReserve(request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            Log.e(TAG, "Reservation failed: $errorBody")
            throw Exception("Reservation failed: ${response.code()} $errorBody")
        }
    }

    /**
     * 既に登録されている単発予約の設定（優先度や録画モードなど）を更新します。
     */
    suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> =
        runCatching {
            val response = apiService.updateReserve(reservationId, request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Update reservation failed: $errorBody")
                throw Exception("Update reservation failed: ${response.code()} $errorBody")
            }
        }

    /**
     * 登録済みの単発予約を削除（キャンセル）します。
     */
    suspend fun deleteReservation(reservationId: Int): Result<Unit> = runCatching {
        val response = apiService.deleteReservation(reservationId)
        if (!response.isSuccessful) {
            // 既に削除されていた場合（404）は成功とみなして握りつぶす
            if (response.code() == 404) {
                Log.w(TAG, "Reservation $reservationId not found (already deleted?)")
                return@runCatching
            }
            throw Exception(
                "Delete reservation failed: ${response.code()} ${
                    response.errorBody()?.string()
                }"
            )
        }
    }

    /**
     * 自動予約条件（キーワード、ジャンル、時間帯などのルールに基づく録画予約）の一覧を取得します。
     */
    suspend fun getReservationConditions(): Result<List<ReservationCondition>> {
        return try {
            val response = apiService.getReservationConditions()
            Result.success(response.reservationConditions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 新しい自動予約条件（キーワード予約）をサーバーに登録します。
     */
    suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> {
        return try {
            val response = apiService.addReservationCondition(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to add condition: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * ★自動予約条件の更新（キーワードの変更や、条件の一時的なON/OFF切り替えなど）
     */
    suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> {
        return try {
            val response = apiService.updateReservationCondition(conditionId, request)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * ★自動予約条件（ルールそのもの）の削除
     */
    suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> {
        return try {
            val response = apiService.deleteReservationCondition(conditionId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete condition: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}