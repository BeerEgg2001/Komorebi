package com.beeregg2001.komorebi.data.repository

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.toKonomiHistoryProgram
import com.beeregg2001.komorebi.data.local.entity.toEntity
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchHistoryRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao
) {
    /**
     * DBを監視し、UI用のモデルに変換して流す。
     * API更新時もここから自動的にUIへ反映される。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun getWatchHistoryFlow(): Flow<List<KonomiHistoryProgram>> {
        return watchHistoryDao.getAllHistory().map { entities ->
            entities.map { it.toKonomiHistoryProgram() }
        }
    }

    /**
     * APIから履歴を取得し、DBを更新する（API優先）
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun refreshHistoryFromApi() {
        runCatching { apiService.getWatchHistory() }.onSuccess { apiHistoryList ->
            // APIから取得した番組をDBに保存
            apiHistoryList.forEach { history ->
                // APIの番組情報をDB用エンティティに変換して保存
                // watch_historyテーブルにUPSERT（なければ挿入、あれば更新）
                val entity = history.toEntity()
                watchHistoryDao.insertOrUpdate(entity)
            }
        }
    }

    /**
     * 再生開始/停止時にローカルで履歴を保存する
     */
    suspend fun saveLocalHistory(program: RecordedProgram) {
        watchHistoryDao.insertOrUpdate(program.toEntity())
    }
}