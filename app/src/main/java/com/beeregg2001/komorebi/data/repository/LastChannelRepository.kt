package com.beeregg2001.komorebi.data.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LastChannel_Repo"

/**
 * アプリ内で最後に視聴したチャンネル履歴（ザッピング・レジューム用）を
 * ローカルDB（Room）で管理するためのリポジトリです。
 */
@Singleton
class LastChannelRepository @Inject constructor(
    private val lastChannelDao: LastChannelDao
) {
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
     * チャンネルの視聴履歴をすべて消去します（設定画面からのリセット用）。
     */
    suspend fun clearLastChannels() {
        lastChannelDao.clearAll()
    }
}