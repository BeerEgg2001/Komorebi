package com.beeregg2001.komorebi.data.repository

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
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

    // ★追加: 録画番組検索
    suspend fun searchRecordedPrograms(keyword: String, page: Int = 1) =
        apiService.searchVideos(keyword = keyword, page = page)

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
        Log.d("DEBUG", "Channel saved: ${entity.name}")
    }

    // 視聴位置同期 (API)
    suspend fun syncPlaybackPosition(programId: String, position: Double) {
        runCatching { apiService.updateWatchHistory(HistoryUpdateRequest(programId, position)) }
    }

    fun buildStreamId(channel: Channel): String {
        val networkIdPart = when (channel.type) {
            "GR" -> channel.networkId.toString()
            "BS", "CS", "SKY", "BS4K" -> "${channel.networkId}00"
            else -> channel.networkId.toString()
        }
        return "${networkIdPart}${channel.serviceId}"
    }
}