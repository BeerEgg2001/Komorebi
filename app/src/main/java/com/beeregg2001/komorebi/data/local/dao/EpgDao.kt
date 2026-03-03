package com.beeregg2001.komorebi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.beeregg2001.komorebi.data.local.entity.EpgChannelEntity
import com.beeregg2001.komorebi.data.local.entity.EpgProgramEntity

@Dao
interface EpgDao {
    // --- チャンネル操作 ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Query("SELECT * FROM epg_channel ORDER BY remoconId ASC")
    suspend fun getAllChannels(): List<EpgChannelEntity>

    // --- 番組操作 ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    // 指定したチャンネルの、指定した時間以降の番組を取得
    @Query("SELECT * FROM epg_program WHERE channelId = :channelId AND endTimeEpoch > :targetTimeEpoch ORDER BY startTimeEpoch ASC")
    suspend fun getProgramsByChannel(channelId: String, targetTimeEpoch: Long): List<EpgProgramEntity>

    // シリーズ抽出用：タイトルで曖昧検索（将来的に正規化の精度向上に使用）
    @Query("SELECT * FROM epg_program WHERE title LIKE '%' || :keyword || '%' ORDER BY startTimeEpoch DESC")
    suspend fun searchProgramsByTitle(keyword: String): List<EpgProgramEntity>

    // --- お掃除機能 ---
    // 過去1週間前（閾値）より前に終了した番組を削除する
    @Query("DELETE FROM epg_program WHERE endTimeEpoch < :thresholdEpoch")
    suspend fun deleteOldPrograms(thresholdEpoch: Long)

    // チャンネルと番組を一括で安全に保存するトランザクション
    @Transaction
    suspend fun insertEpgData(channels: List<EpgChannelEntity>, programs: List<EpgProgramEntity>) {
        insertChannels(channels)
        insertPrograms(programs)
    }
}