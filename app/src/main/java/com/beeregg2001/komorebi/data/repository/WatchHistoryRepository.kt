package com.beeregg2001.komorebi.data.repository

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchHistoryRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao,
    private val konomiRepository: KonomiRepository
) {
    @RequiresApi(Build.VERSION_CODES.O)
    fun getWatchHistoryFlow(): Flow<List<KonomiHistoryProgram>> {
        return watchHistoryDao.getAllHistory().map { entities ->
            entities.map { KonomiDataMapper.toUiModel(it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun refreshHistoryFromApi() {
        runCatching { apiService.getWatchHistory() }.onSuccess { apiHistoryList ->
            apiHistoryList.forEach { history ->
                watchHistoryDao.insertOrUpdate(KonomiDataMapper.toEntity(history))
            }
        }
    }

    suspend fun saveWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        // 1. ローカルDBに保存
        val entity = KonomiDataMapper.toEntity(program, positionSeconds)
        watchHistoryDao.insertOrUpdate(entity)

        // 2. サーバーへ同期
        runCatching {
            konomiRepository.syncPlaybackPosition(program.id.toString(), positionSeconds)
        }
    }
}