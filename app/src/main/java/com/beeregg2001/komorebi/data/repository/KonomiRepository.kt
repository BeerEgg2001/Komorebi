package com.beeregg2001.komorebi.data.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.HistoryUpdateRequest
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiProgram
import com.beeregg2001.komorebi.data.model.KonomiUser
import com.beeregg2001.komorebi.viewmodel.Channel
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
    // --- ユーザー設定 (API) ---
    private val _currentUser = MutableStateFlow<KonomiUser?>(null)
    val currentUser: StateFlow<KonomiUser?> = _currentUser.asStateFlow()

    suspend fun refreshUser() {
        runCatching { apiService.getCurrentUser() }
            .onSuccess { _currentUser.value = it }
    }

    // --- チャンネル・録画 (API) ---
    suspend fun getChannels() = apiService.getChannels()

    suspend fun getRecordedPrograms(page: Int = 1) = apiService.getRecordedPrograms(page = page)

    // 録画番組検索
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

    // --- マイリスト (API) ---
    suspend fun getBookmarks(): Result<List<KonomiProgram>> = runCatching { apiService.getBookmarks() }

    // --- 視聴履歴 (API: 将来用) ---
    suspend fun getWatchHistory(): Result<List<KonomiHistoryProgram>> = runCatching { apiService.getWatchHistory() }

    // --- 視聴履歴 (Room: ローカルDB) ---
    fun getLocalWatchHistory() = watchHistoryDao.getAllHistory()

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity) {
        watchHistoryDao.insertOrUpdate(entity)
    }

    // --- 最近見たチャンネル (Room: ローカルDB) ---
    fun getLastChannels() = lastChannelDao.getLastChannels()

    @OptIn(UnstableApi::class)
    suspend fun saveLastChannel(entity: LastChannelEntity) {
        lastChannelDao.insertOrUpdate(entity)
        Log.d(TAG, "Channel saved: ${entity.name}")
    }

    suspend fun getJikkyoInfo(channelId: String) = runCatching {
        apiService.getJikkyoInfo(channelId)
    }

    // 視聴位置同期 (API)
    suspend fun syncPlaybackPosition(programId: String, position: Double) {
        runCatching { apiService.updateWatchHistory(HistoryUpdateRequest(programId, position)) }
    }
}