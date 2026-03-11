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

    @Query("SELECT * FROM recorded_programs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<RecordedProgramEntity>

    @Query("SELECT id FROM recorded_programs")
    suspend fun getAllIds(): List<Int>

    // ★修正 BUG-04: 1000件以上でクラッシュするSQLiteのIN句上限エラーを防ぐため封印
    @Deprecated("Use deleteByIds with chunked(900) instead. This method crashes with 1000+ items.")
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

    @Query("SELECT COUNT(id) FROM recorded_programs")
    fun getTotalCountFlow(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT DISTINCT channel_id as channelId, channel_type as channelType, channel_name as channelName FROM recorded_programs WHERE channel_id IS NOT NULL")
    suspend fun getDistinctChannels(): List<ChannelProjection>

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
    suspend fun getGroupedSeries(): List<SeriesProjection>

    @Query("SELECT id, title, series_name FROM recorded_programs")
    suspend fun getAllTitlesAndSeries(): List<ProgramTitleProjection>

    // ★修正 PERF-05: 辞書に未登録のタイトルだけをDB側で高速に抽出する（OOM回避）
    @Query(
        """
        SELECT p.id, p.title, p.series_name FROM recorded_programs p
        LEFT JOIN ai_series_dictionary d ON p.title = d.originalTitle
        WHERE d.originalTitle IS NULL
    """
    )
    suspend fun getTitlesNotInDictionary(): List<ProgramTitleProjection>

    @Query("UPDATE recorded_programs SET series_name = :newSeriesName WHERE id = :id")
    suspend fun updateSeriesName(id: Int, newSeriesName: String)

    @Query("UPDATE recorded_programs SET series_name = :newSeriesName WHERE title = :originalTitle")
    suspend fun updateSeriesNameByOriginalTitle(originalTitle: String, newSeriesName: String)

    @Query("DELETE FROM recorded_programs")
    suspend fun clearAll()
}

@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE id = 1")
    suspend fun getSyncMeta(): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta WHERE id = 1")
    fun getSyncMetaFlow(): kotlinx.coroutines.flow.Flow<SyncMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SyncMetaEntity)
}

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

data class ProgramTitleProjection(
    val id: Int,
    val title: String,
    @ColumnInfo(name = "series_name") val seriesName: String
)