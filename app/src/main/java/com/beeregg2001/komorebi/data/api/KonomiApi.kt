package com.beeregg2001.komorebi.data.api

import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.viewmodel.ChannelApiResponse
import retrofit2.Response
import retrofit2.http.*

interface KonomiApi {
    // --- ユーザー ---
    @GET("api/users/me")
    suspend fun getCurrentUser(): KonomiUser

    // --- チャンネル ---
    @GET("api/channels")
    suspend fun getChannels(): ChannelApiResponse

    // --- 録画番組 ---
    @GET("api/videos")
    suspend fun getRecordedPrograms(
        @Query("limit") limit: Int = 24,
        @Query("offset") offset: Int = 0,
        @Query("page") page: Int = 1,
        @Query("sort") sort: String = "recorded_start_at",
        @Query("order") order: String = "desc"
    ): RecordedApiResponse

    @GET("api/videos/search")
    suspend fun searchVideos(
        @Query("query") keyword: String,
        @Query("page") page: Int = 1,
        @Query("order") order: String = "desc"
    ): RecordedApiResponse

    // --- 視聴維持 ---
    @PUT("api/streams/video/{videoId}/{quality}/keep-alive")
    suspend fun keepAlive(
        @Path("videoId") videoId: Int,
        @Path("quality") quality: String,
        @Query("session_id") sessionId: String
    ): Response<Unit>

    // --- マイリスト ---
    @GET("api/mylists")
    suspend fun getBookmarks(): List<KonomiProgram>

    // --- 視聴履歴 ---
    @GET("api/histories")
    suspend fun getWatchHistory(): List<KonomiHistoryProgram>

    @POST("api/histories")
    suspend fun updateWatchHistory(@Body request: HistoryUpdateRequest): Response<Unit>

    // --- 実況 ---
    @GET("api/jikkyo/{channelId}")
    suspend fun getJikkyoInfo(@Path("channelId") channelId: String): JikkyoResponse

    @GET("api/videos/{videoId}/jikkyo")
    suspend fun getArchivedJikkyo(@Path("videoId") videoId: Int): ArchivedJikkyoResponse

    // --- 予約関連 ---
    @GET("api/recording/reservations")
    suspend fun getReserves(): ReserveApiResponse

    // 予約追加 (新しいリクエストボディに対応)
    @POST("api/recording/reservations")
    suspend fun addReserve(@Body request: ReserveRequest): Response<Unit>

    // ★追加: 予約更新 (ID指定でPUT)
    @PUT("api/recording/reservations/{reservation_id}")
    suspend fun updateReserve(
        @Path("reservation_id") reservationId: Int,
        @Body request: ReserveRequest
    ): Response<Unit>

    // 予約削除
    @DELETE("api/recording/reservations/{reservation_id}")
    suspend fun deleteReservation(@Path("reservation_id") reservationId: Int): Response<Unit>
}