package com.beeregg2001.komorebi.data.repository

import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.*

/**
 * 1. ライブ視聴・チャンネル関連の機能を提供するインターフェース
 */
interface LiveProvider {
    suspend fun getChannels(): ChannelApiResponse

    suspend fun getLiveStreamUrl(channelId: String, quality: String): String
    suspend fun getChannelLogoUrl(channelId: String): String
}

/**
 * 2. 録画番組関連の機能を提供するインターフェース
 */
interface RecordProvider {
    suspend fun getRecordedPrograms(page: Int = 1): RecordedApiResponse
    suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram>
    suspend fun searchRecordedPrograms(keyword: String, page: Int = 1): RecordedApiResponse

    // ★ 修正: offsetSeconds を追加し、途中からストリームを取得できるようにする
    suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double = 0.0
    ): String

    suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>>

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun keepAlive(videoId: Int, quality: String, sessionId: String)

    // シークバー・シーンサーチ用のタイル画像(スプライト)URLを取得する
    suspend fun getTiledThumbnailUrl(videoId: Int): String?

    // バックエンドから利用可能な画質（トランスコード設定）のリストを取得する
    suspend fun getStreamQualities(): List<StreamQuality> = emptyList()
}

/**
 * 3. 録画予約・自動予約ルールの機能を提供するインターフェース
 */
interface ReserveProvider {
    suspend fun getReserves(): Result<List<ReserveItem>>
    suspend fun addReserve(request: ReserveRequest): Result<Unit>
    suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit>
    suspend fun deleteReservation(reservationId: Int): Result<Unit>

    suspend fun getReservationConditions(): Result<List<ReservationCondition>>
    suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit>
    suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition>

    suspend fun deleteReservationCondition(conditionId: Int): Result<Unit>
}

/**
 * 4. 番組表（EPG）関連の機能を提供するインターフェース
 */
interface EpgProvider {
    suspend fun getEpgPrograms(
        startTime: String? = null,
        endTime: String? = null,
        channelType: String? = null
    ): List<EpgChannelWrapper>

    suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper>
}