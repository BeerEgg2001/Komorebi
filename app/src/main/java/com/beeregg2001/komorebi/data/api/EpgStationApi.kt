package com.beeregg2001.komorebi.data.api

import com.beeregg2001.komorebi.data.model.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

/** EPGStation の REST API を呼び出す Retrofit 定義。 */
interface EpgStationApi {
    @GET("api/channels")
    suspend fun getChannels(): List<EsChannelItem>

    @GET("api/schedules")
    suspend fun getSchedules(
        @Query("startAt") startAt: Long,
        @Query("endAt") endAt: Long,
        @Query("isHalfWidth") isHalfWidth: Boolean = false,
        @QueryMap broadcastFlags: Map<String, Boolean>
    ): List<EsSchedule>

    @GET("api/schedules/broadcasting")
    suspend fun getBroadcasting(
        @Query("isHalfWidth") isHalfWidth: Boolean = false,
        @Query("includeNextProgram") includeNextProgram: Boolean = true,
        @Query("time") time: Long? = null
    ): List<EsSchedule>

    @GET("api/schedules/detail/{programId}")
    suspend fun getScheduleDetail(@Path("programId") programId: Long): EsScheduleProgramItem

    @GET("api/recorded")
    suspend fun getRecordedList(
        @Query("isHalfWidth") isHalfWidth: Boolean = false,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 24,
        @Query("isReverse") isReverse: Boolean = false,
        @Query("keyword") keyword: String? = null
    ): EsRecords

    @GET("api/recorded/{recordedId}")
    suspend fun getRecordedDetail(
        @Path("recordedId") recordedId: Int,
        @Query("isHalfWidth") isHalfWidth: Boolean = false
    ): EsRecordedItem

    @GET("api/recorded/{recordedId}/next-up")
    suspend fun getNextUp(
        @Path("recordedId") recordedId: Int,
        @Query("isHalfWidth") isHalfWidth: Boolean = false
    ): EsNextUpResult?

    @GET("api/series/mappings/{recordedId}")
    suspend fun getSeriesMapping(@Path("recordedId") recordedId: Int): Response<EsSeriesMappingValue>

    @GET("api/videos/{videoFileId}/duration")
    suspend fun getDuration(@Path("videoFileId") videoFileId: Int): Response<EsDuration>

    @GET("api/videos/{videoFileId}/chapters")
    suspend fun getChapters(@Path("videoFileId") videoFileId: Int): Response<EsVideoChapters>

    @GET("api/videos/{videoFileId}/playback-position")
    suspend fun getPlaybackPosition(@Path("videoFileId") videoFileId: Int): Response<EsWatchHistory>

    @PUT("api/videos/{videoFileId}/playback-position")
    suspend fun updatePlaybackPosition(
        @Path("videoFileId") videoFileId: Int,
        @Body body: EsPlaybackPosition
    ): Response<EsWatchHistory>

    @GET("api/videos/{videoFileId}/audio-tracks")
    suspend fun getAudioTracks(@Path("videoFileId") videoFileId: Int): Response<EsAudioTracks>

    @GET("api/config")
    suspend fun getConfig(): EsConfig

    @GET("api/reserves")
    suspend fun getReserves(
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 1000,
        @Query("type") type: String = "all",
        @Query("isHalfWidth") isHalfWidth: Boolean = false
    ): EsReserves

    @POST("api/reserves")
    suspend fun addReserve(@Body body: EsManualReserveOption): Response<Unit>

    @PUT("api/reserves/{id}")
    suspend fun updateReserve(@Path("id") id: Int, @Body body: EsEditManualReserveOption): Response<Unit>

    @DELETE("api/reserves/{id}")
    suspend fun deleteReserve(@Path("id") id: Int): Response<Unit>

    @GET("api/rules")
    suspend fun getRules(@Query("offset") offset: Int = 0, @Query("limit") limit: Int = 1000): EsRules

    @GET("api/rules/{id}")
    suspend fun getRule(@Path("id") id: Int): EsRule

    @POST("api/rules")
    suspend fun addRule(@Body body: EsAddRuleOption): Response<Unit>

    @PUT("api/rules/{id}")
    suspend fun updateRule(@Path("id") id: Int, @Body body: EsAddRuleOption): Response<Unit>

    @DELETE("api/rules/{id}")
    suspend fun deleteRule(@Path("id") id: Int): Response<Unit>

    @GET("api/streams/live/{channelId}/hls")
    suspend fun startLiveHls(@Path("channelId") channelId: Long, @Query("mode") mode: Int): EsStartStreamInfo

    @GET("api/streams/recorded/{videoFileId}/hls")
    suspend fun startRecordedHls(
        @Path("videoFileId") videoFileId: Int,
        @Query("mode") mode: Int,
        @Query("ss") ss: Int
    ): EsStartStreamInfo

    @PUT("api/streams/{streamId}/keep")
    suspend fun keepStream(@Path("streamId") streamId: Int): Response<Unit>
}
