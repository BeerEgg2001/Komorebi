package com.example.komorebi.data.repository

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.komorebi.data.model.EpgChannelResponse
import com.example.komorebi.data.model.EpgChannelWrapper
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
         * 指定期間の番組表データを取得
         */
        // EpgRepository.kt の fetchEpgData を修正
        @OptIn(UnstableApi::class)
        @RequiresApi(Build.VERSION_CODES.O)
        suspend fun fetchEpgData(
            startTime: OffsetDateTime,
            channelType: String? = null,
            days: Long = 1
        ): Result<List<EpgChannelWrapper>> {
            return try {
                // ISO8601形式でフォーマット
                val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                val startStr = startTime.format(formatter)
                val endStr = startTime.plusHours(12).format(formatter) // 最初は「6時間分」などでテスト

                val response = apiService.getEpgPrograms(
                    startTime = startStr,
                    endTime = endStr,
                    channelType = channelType
                )
                Result.success(response.channels)
            } catch (e: Exception) {
                Log.e("EPG", "Fetch Error", e) // ログを出してエラー内容を確認
                Result.failure(e)
            }
        }

        /**
         * 特定のチャンネルIDのみを抽出する（ピン留め機能用など）
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