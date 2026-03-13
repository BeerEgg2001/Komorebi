package com.beeregg2001.komorebi.data.api

import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
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

    // ★追加: 個別の録画番組詳細を取得 (詳細情報を含む完全なデータ)
    @GET("api/videos/{video_id}")
    suspend fun getRecordedProgram(@Path("video_id") videoId: Int): RecordedProgram

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

    // =================================================================================
    // キーワード自動予約条件 (EPG予約) 関連 API
    // =================================================================================

    // 1. 自動予約条件の一覧取得
    @GET("api/recording/conditions")
    suspend fun getReservationConditions(): ReservationConditionsResponse

    // 2. 自動予約条件の新規登録 (成功時は 201 Created が返る)
    @POST("api/recording/conditions")
    suspend fun addReservationCondition(@Body request: ReservationConditionAddRequest): Response<Unit>

    // 3. 自動予約条件の個別取得
    @GET("api/recording/conditions/{reservation_condition_id}")
    suspend fun getReservationCondition(
        @Path("reservation_condition_id") conditionId: Int
    ): ReservationCondition

    // 4. 自動予約条件の更新 (更新後のデータがそのまま返ってくる仕様)
    @PUT("api/recording/conditions/{reservation_condition_id}")
    suspend fun updateReservationCondition(
        @Path("reservation_condition_id") conditionId: Int,
        @Body request: ReservationConditionUpdateRequest
    ): ReservationCondition

    // 5. 自動予約条件の削除 (成功時は 204 No Content が返る)
    @DELETE("api/recording/conditions/{reservation_condition_id}")
    suspend fun deleteReservationCondition(
        @Path("reservation_condition_id") conditionId: Int
    ): Response<Unit>
}