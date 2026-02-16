package com.beeregg2001.komorebi.data.api

import com.beeregg2001.komorebi.data.model.HistoryUpdateRequest
import com.beeregg2001.komorebi.data.model.JikkyoResponse
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiProgram
import com.beeregg2001.komorebi.data.model.KonomiUser
import com.beeregg2001.komorebi.data.model.RecordedApiResponse
import com.beeregg2001.komorebi.viewmodel.ChannelApiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface KonomiApi {
    @GET("api/channels")
    suspend fun getChannels(): ChannelApiResponse

    @GET("api/videos")
    suspend fun getRecordedPrograms(
        @Query("order") sort: String = "desc",
        @Query("page") page: Int = 1,
    ): RecordedApiResponse

    // ★追加: 録画番組検索API
    @GET("api/videos/search")
    suspend fun searchVideos(
        @Query("query") keyword: String,
        @Query("order") sort: String = "desc",
        @Query("page") page: Int = 1,
//        @Query("limit") limit: Int = 30 // デフォルト30件と想定
    ): RecordedApiResponse

    // --- ユーザー設定（ピン留めチャンネル等） ---
    @GET("api/users/me")
    suspend fun getCurrentUser(): KonomiUser

    // --- 視聴履歴 ---
    @GET("api/programs/history")
    suspend fun getWatchHistory(): List<KonomiHistoryProgram>

    // 視聴位置の更新（30秒以上視聴時などに叩く）
    @POST("api/programs/history")
    suspend fun updateWatchHistory(@Body request: HistoryUpdateRequest)

    // --- マイリスト（ブックマーク） ---
    @GET("api/programs/bookmarks")
    suspend fun getBookmarks(): List<KonomiProgram>

    @POST("api/programs/bookmarks/{program_id}")
    suspend fun addBookmark(@Path("program_id") programId: String)

    @DELETE("api/programs/bookmarks/{program_id}")
    suspend fun removeBookmark(@Path("program_id") programId: String)

//    録画ストリームの生存確認（ハートビート）
    @PUT("api/streams/video/{video_id}/{quality}/keep-alive")
    suspend fun keepAlive(
        @Path("video_id") videoId: Int,
        @Path("quality") quality: String,
        @Query("session_id") sessionId: String
    ): Response<Unit>

    // ★追加: 実況接続情報取得API
    @GET("api/channels/{channel_id}/jikkyo")
    suspend fun getJikkyoInfo(
        @Path("channel_id") channelId: String
    ): JikkyoResponse
}