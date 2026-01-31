package com.example.komorebi.data.repository

import com.example.komorebi.data.api.KonomiApi
import com.example.komorebi.data.local.dao.WatchHistoryDao
import com.example.komorebi.data.local.entity.WatchHistoryEntity
import com.example.komorebi.data.model.HistoryUpdateRequest
import com.example.komorebi.data.model.KonomiHistoryProgram
import com.example.komorebi.data.model.KonomiProgram
import com.example.komorebi.data.model.KonomiUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KonomiRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao // RoomのDaoを追加
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

    // --- マイリスト (API) ---
    suspend fun getBookmarks(): Result<List<KonomiProgram>> = runCatching { apiService.getBookmarks() }

    // --- 視聴履歴 (API: 将来用) ---
    suspend fun getWatchHistory(): Result<List<KonomiHistoryProgram>> = runCatching { apiService.getWatchHistory() }

    // --- 視聴履歴 (Room: ローカルDB) ---
    fun getLocalWatchHistory() = watchHistoryDao.getAllHistory()

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity) {
        watchHistoryDao.insertOrUpdate(entity)
    }

    // 視聴位置同期 (API)
    suspend fun syncPlaybackPosition(programId: String, position: Double) {
        runCatching { apiService.updateWatchHistory(HistoryUpdateRequest(programId, position)) }
    }
}