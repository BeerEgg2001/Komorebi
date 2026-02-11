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
        }.onFailure {
            // エラーログなど
            it.printStackTrace()
        }
    }

    /**
     * ローカル再生の履歴をDBに保存する
     * @param program 再生中の番組情報
     * @param positionSeconds 現在の再生位置（秒）
     */
    suspend fun saveWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        // RecordedProgramをDBエンティティに変換し、再生位置と現在時刻を更新して保存
        val entity = program.toEntity().copy(
            playbackPosition = positionSeconds,
            watchedAt = System.currentTimeMillis()
        )
        watchHistoryDao.insertOrUpdate(entity)
    }
}