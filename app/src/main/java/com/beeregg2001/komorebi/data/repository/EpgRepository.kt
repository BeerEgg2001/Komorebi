package com.beeregg2001.komorebi.data.repository

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.model.EpgChannelResponse
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException // ★追加

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
    private val apiService: KonomiTvApiService,
    private val epgCacheDao: EpgCacheDao,
    private val gson: Gson
) {
    /**
     * DBキャッシュ付きの番組表データ取得（Flow）
     */
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun getEpgDataStream(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String
    ): Flow<Result<List<EpgChannelWrapper>>> = flow {
        // 1. キャッシュから読み込んで即座にエミットする
        try {
            val cache = epgCacheDao.getCache(channelType)
            if (cache != null) {
                val listType = object : TypeToken<List<EpgChannelWrapper>>() {}.type
                val data: List<EpgChannelWrapper> = gson.fromJson(cache.dataJson, listType)
                emit(Result.success(data))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e // ★重要: キャンセル/中断例外は再送出する
            Log.e("EPG", "Cache Read Error", e)
        }

        // 2. APIから最新データを取得
        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val response = apiService.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )

            // 3. 取得した最新データをキャッシュに保存
            val json = gson.toJson(response.channels)
            epgCacheDao.insertOrUpdate(EpgCacheEntity(channelType, json, System.currentTimeMillis()))

            // 4. 最新データをエミットしてUIを更新
            emit(Result.success(response.channels))
        } catch (e: Exception) {
            if (e is CancellationException) throw e // ★重要: AbortFlowExceptionなどを握りつぶさない
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            emit(Result.failure(e))
        }
    }

    /**
     * 単発のAPIフェッチ
     */
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchEpgData(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String? = null
    ): Result<List<EpgChannelWrapper>> {
        return try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

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