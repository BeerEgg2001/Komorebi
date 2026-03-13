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

@Singleton
class KonomiRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao,
    private val lastChannelDao: LastChannelDao
) {
    // --- ユーザー設定 ---
    private val _currentUser = MutableStateFlow<KonomiUser?>(null)
    val currentUser: StateFlow<KonomiUser?> = _currentUser.asStateFlow()

    suspend fun refreshUser() {
        runCatching { apiService.getCurrentUser() }
            .onSuccess { _currentUser.value = it }
    }

    // --- チャンネル・録画 ---
    suspend fun getChannels(): ChannelApiResponse = apiService.getChannels()

    suspend fun getRecordedPrograms(page: Int = 1) = apiService.getRecordedPrograms(page = page)

    // ★追加: 番組詳細の取得（DBではなく直接KonomiTVに最新情報を聞きに行く）
    suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> = runCatching {
        apiService.getRecordedProgram(videoId)
    }

    suspend fun searchRecordedPrograms(keyword: String, page: Int = 1) = run {
        Log.d(TAG, "Calling API searchVideos. Keyword: $keyword, Page: $page")
        apiService.searchVideos(keyword = keyword, page = page)
    }

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

    // --- マイリスト ---
    suspend fun getBookmarks(): Result<List<KonomiProgram>> = runCatching { apiService.getBookmarks() }

    // --- 視聴履歴 ---
    suspend fun getWatchHistory(): Result<List<KonomiHistoryProgram>> = runCatching { apiService.getWatchHistory() }

    fun getLocalWatchHistory() = watchHistoryDao.getAllHistory()

    suspend fun getHistoryEntityById(id: Int): WatchHistoryEntity? {
        return watchHistoryDao.getById(id)
    }

    // ★追加: 複数IDを一括取得
    suspend fun getHistoryEntitiesByIds(ids: List<Int>): List<WatchHistoryEntity> {
        return watchHistoryDao.getByIds(ids)
    }

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity) {
        watchHistoryDao.insertOrUpdate(entity)
    }

    // ★追加: リストを一括保存
    suspend fun saveAllToLocalHistory(entities: List<WatchHistoryEntity>) {
        watchHistoryDao.insertOrUpdateAll(entities)
    }

    // --- 最近見たチャンネル ---
    fun getLastChannels() = lastChannelDao.getLastChannels()

    @OptIn(UnstableApi::class)
    suspend fun saveLastChannel(entity: LastChannelEntity) {
        lastChannelDao.insertOrUpdate(entity)
        Log.d(TAG, "Channel saved: ${entity.name}")
    }

    // ★追加: チャンネル履歴の全削除
    suspend fun clearLastChannels() {
        lastChannelDao.clearAll()
    }

    suspend fun getJikkyoInfo(channelId: String) = runCatching {
        apiService.getJikkyoInfo(channelId)
    }

    suspend fun syncPlaybackPosition(programId: String, position: Double) {
        runCatching { apiService.updateWatchHistory(HistoryUpdateRequest(programId, position)) }
    }

    suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> = runCatching {
        val response = apiService.getArchivedJikkyo(videoId)
        if (response.is_success) response.comments else emptyList()
    }

    // --- 予約関連 (修正版) ---
    suspend fun getReserves(): Result<List<ReserveItem>> = runCatching {
        apiService.getReserves().reservations
    }

    suspend fun addReserve(request: ReserveRequest): Result<Unit> = runCatching {
        val response = apiService.addReserve(request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            Log.e(TAG, "Reservation failed: $errorBody")
            throw Exception("Reservation failed: ${response.code()} $errorBody")
        }
    }

    suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> = runCatching {
        val response = apiService.updateReserve(reservationId, request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            Log.e(TAG, "Update reservation failed: $errorBody")
            throw Exception("Update reservation failed: ${response.code()} $errorBody")
        }
    }

    suspend fun deleteReservation(reservationId: Int): Result<Unit> = runCatching {
        val response = apiService.deleteReservation(reservationId)
        if (!response.isSuccessful) {
            if (response.code() == 404) {
                Log.w(TAG, "Reservation $reservationId not found (already deleted?)")
                return@runCatching
            }
            throw Exception("Delete reservation failed: ${response.code()} ${response.errorBody()?.string()}")
        }
    }

    //  自動予約条件（キーワード予約）の一覧を取得する
    suspend fun getReservationConditions(): Result<List<ReservationCondition>> {
        return try {
            val response = apiService.getReservationConditions()
            Result.success(response.reservationConditions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    // ★自動予約条件の更新
    suspend fun updateReservationCondition(conditionId: Int, request: ReservationConditionUpdateRequest): Result<ReservationCondition> {
        return try {
            val response = apiService.updateReservationCondition(conditionId, request)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ★自動予約条件の削除
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