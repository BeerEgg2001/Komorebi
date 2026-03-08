package com.beeregg2001.komorebi.data.local.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.local.entity.SyncMetaEntity

@Dao
interface RecordedProgramDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(programs: List<RecordedProgramEntity>)

    @Query("SELECT * FROM recorded_programs WHERE id = :id")
    suspend fun getById(id: Int): RecordedProgramEntity?

    @Query("SELECT id FROM recorded_programs")
    suspend fun getAllIds(): List<Int>

    @Query("DELETE FROM recorded_programs WHERE id NOT IN (:apiIds)")
    suspend fun deleteOrphans(apiIds: List<Int>)

    @Query("DELETE FROM recorded_programs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("SELECT * FROM recorded_programs ORDER BY start_time DESC")
    fun getAllPagingSource(): PagingSource<Int, RecordedProgramEntity>

    @Query("SELECT * FROM recorded_programs WHERE channel_id = :channelId ORDER BY start_time DESC")
    fun getPagingSourceByChannel(channelId: String): PagingSource<Int, RecordedProgramEntity>

    @Query("SELECT * FROM recorded_programs WHERE title LIKE '%' || :keyword || '%' OR series_name LIKE '%' || :keyword || '%' ORDER BY start_time DESC")
    fun searchPagingSource(keyword: String): PagingSource<Int, RecordedProgramEntity>

    @Query("SELECT * FROM recorded_programs WHERE genres LIKE '%' || :genre || '%' ORDER BY start_time DESC")
    fun getPagingSourceByGenre(genre: String): PagingSource<Int, RecordedProgramEntity>

    @Query("SELECT * FROM recorded_programs WHERE strftime('%w', substr(start_time, 1, 10)) = :dayOfWeek ORDER BY start_time DESC")
    fun getPagingSourceByDayOfWeek(dayOfWeek: String): PagingSource<Int, RecordedProgramEntity>

    @Query(
        """
        SELECT * FROM recorded_programs 
        WHERE playback_position <= 5.0 
        AND id NOT IN (SELECT videoId FROM watch_history WHERE playbackPosition > 5.0)
        ORDER BY start_time DESC
    """
    )
    fun getPagingSourceUnwatched(): PagingSource<Int, RecordedProgramEntity>

    @Query("SELECT * FROM recorded_programs ORDER BY start_time DESC LIMIT 20")
    fun getRecentRecordingsFlow(): kotlinx.coroutines.flow.Flow<List<RecordedProgramEntity>>

    @Query("SELECT * FROM recorded_programs ORDER BY start_time DESC")
    suspend fun getAllPrograms(): List<RecordedProgramEntity>

    // ★追加: チャンネル一覧をSQLite側で一意（DISTINCT）にして取得（数十件のみ、超軽量）
    @Query("SELECT DISTINCT channel_id as channelId, channel_type as channelType, channel_name as channelName FROM recorded_programs WHERE channel_id IS NOT NULL")
    fun getDistinctChannelsFlow(): kotlinx.coroutines.flow.Flow<List<ChannelProjection>>

    // ★追加: シリーズのグルーピングをSQLite側に任せる（数百件のみ、超軽量・爆速）
    @Query(
        """
        SELECT 
            series_name as seriesName, 
            COUNT(id) as programCount, 
            MAX(id) as representativeVideoId, 
            MAX(is_episodic) as isEpisodic,
            MAX(genres) as genres 
        FROM recorded_programs 
        WHERE series_name != '' 
        GROUP BY series_name
    """
    )
    fun getGroupedSeriesFlow(): kotlinx.coroutines.flow.Flow<List<SeriesProjection>>

    @Query("DELETE FROM recorded_programs")
    suspend fun clearAll()
}

@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE id = 1")
    suspend fun getSyncMeta(): SyncMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SyncMetaEntity)
}

// --- SQLiteから直接グループ化されたデータを受け取るPOJO ---
data class ChannelProjection(
    val channelId: String,
    val channelType: String?,
    val channelName: String?
)

data class SeriesProjection(
    val seriesName: String,
    val programCount: Int,
    val representativeVideoId: Int,
    val isEpisodic: Boolean,
    val genres: List<com.beeregg2001.komorebi.data.model.EpgGenre>?
)