package com.example.komorebi.data.api

import com.example.komorebi.data.model.HistoryUpdateRequest
import com.example.komorebi.data.model.KonomiHistoryProgram
import com.example.komorebi.data.model.KonomiProgram
import com.example.komorebi.data.model.KonomiUser
import com.example.komorebi.data.model.RecordedApiResponse
import com.example.komorebi.viewmodel.ChannelApiResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
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
    }