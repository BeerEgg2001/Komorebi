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
import com.beeregg2001.komorebi.viewmodel.ChannelApiResponse
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
    // ★修正: 戻り値を ChannelApiResponse に変更
    suspend fun getChannels(): ChannelApiResponse = apiService.getChannels()

    suspend fun getRecordedPrograms(page: Int = 1) = apiService.getRecordedPrograms(page = page)

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

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity) {
        watchHistoryDao.insertOrUpdate(entity)
    }

    // --- 最近見たチャンネル ---
    fun getLastChannels() = lastChannelDao.getLastChannels()

    @OptIn(UnstableApi::class)
    suspend fun saveLastChannel(entity: LastChannelEntity) {
        lastChannelDao.insertOrUpdate(entity)
        Log.d(TAG, "Channel saved: ${entity.name}")
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

    // --- 予約関連 ---
    suspend fun getReserves(): Result<List<ReserveItem>> = runCatching {
        apiService.getReserves().reservations
    }

    // ★修正: リクエストオブジェクトを受け取るように変更
    suspend fun addReserve(request: ReserveRequest): Result<Unit> = runCatching {
        val response = apiService.addReserve(request)
        if (!response.isSuccessful) throw Exception("Reservation failed: ${response.code()} ${response.errorBody()?.string()}")
    }

    // ★追加: 予約更新の実装
    suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> = runCatching {
        val response = apiService.updateReserve(reservationId, request)
        if (!response.isSuccessful) {
            throw Exception("Update reservation failed: ${response.code()} ${response.errorBody()?.string()}")
        }
    }

    suspend fun deleteReservation(reservationId: Int): Result<Unit> = runCatching {
        val response = apiService.deleteReservation(reservationId)
        if (!response.isSuccessful) {
            if (response.code() == 404) {
                Log.w(TAG, "Reservation $reservationId not found (already deleted?)")
                throw Exception("Reservation not found")
            }
            throw Exception("Delete reservation failed: ${response.code()} ${response.errorBody()?.string()}")
        }
    }
}