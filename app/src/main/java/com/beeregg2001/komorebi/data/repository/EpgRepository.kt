package com.beeregg2001.komorebi.data.repository

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.EpgChannelResponse
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

interface KonomiTvApiService {
    @GET("api/programs/timetable")
    suspend fun getEpgPrograms(
        @Query("start_time") startTime: String? = null,
        @Query("end_time") endTime: String? = null,
        @Query("channel_type") channelType: String? = null,
        @Query("pinned_channel_ids") pinnedChannelIds: String? = null
    ): EpgChannelResponse
}

class EpgRepository @Inject constructor(
    private val apiService: KonomiTvApiService
) {
    /**
     * 指定された開始時刻と終了時刻の範囲で番組表データを取得
     */
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchEpgData(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime, // days: Long から変更
        channelType: String? = null
    ): Result<List<EpgChannelWrapper>> {
        return try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            // apiService は constructor で定義されているため、ここからアクセス可能
            val response = apiService.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            Result.success(response.channels)
        } catch (e: Exception) {
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            Result.failure(e)
        }
    }

    /**
     * 特定のチャンネルIDのみを抽出する
     */
    suspend fun fetchPinnedChannels(
        pinnedIds: List<String>
    ): Result<List<EpgChannelWrapper>> {
        return try {
            val response = apiService.getEpgPrograms(
                pinnedChannelIds = pinnedIds.joinToString(",")
            )
            Result.success(response.channels)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}