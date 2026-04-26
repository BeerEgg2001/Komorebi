package com.beeregg2001.komorebi.data.repository

import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgStationRepository @Inject constructor(
    private val settingsRepository: SettingsRepository
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider {

    override suspend fun getChannels(): ChannelApiResponse = TODO("EPGStation: Not implemented yet")
    override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int): String = ""
    override suspend fun getChannelLogoUrl(channelId: String): String = ""

    override suspend fun getRecordedPrograms(page: Int): RecordedApiResponse =
        TODO("EPGStation: Not implemented yet")

    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> =
        Result.failure(NotImplementedError())

    override suspend fun searchRecordedPrograms(keyword: String, page: Int): RecordedApiResponse =
        TODO("EPGStation: Not implemented yet")

    override suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSec: Double
    ): String = ""

    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> =
        Result.success(emptyList())

    @UnstableApi
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
    }

    override suspend fun getTiledThumbnailUrl(videoId: Int): String = ""

    override suspend fun getReserves(): Result<List<ReserveItem>> = Result.success(emptyList())
    override suspend fun addReserve(request: ReserveRequest): Result<Unit> =
        Result.failure(NotImplementedError())

    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> =
        Result.failure(NotImplementedError())

    override suspend fun deleteReservation(reservationId: Int): Result<Unit> =
        Result.failure(NotImplementedError())

    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> =
        Result.success(emptyList())

    override suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> =
        Result.failure(NotImplementedError())

    override suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> = Result.failure(NotImplementedError())

    override suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> =
        Result.failure(NotImplementedError())

    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ): List<EpgChannelWrapper> = emptyList()

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> =
        emptyList()
}