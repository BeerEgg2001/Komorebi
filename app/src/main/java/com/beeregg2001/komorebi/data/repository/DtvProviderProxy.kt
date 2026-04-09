package com.beeregg2001.komorebi.data.repository

import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ユーザーの設定（SettingsRepository）に応じて、リクエストを適切なバックエンド（Repository）に
 * 動的にルーティングする「代理人（Proxy）」クラスです。
 * これにより、アプリを再起動することなくバックエンドをシームレスに切り替えることができます。
 */
@Singleton
class DtvProviderProxy @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val konomiRepository: KonomiRepository,
    private val edcbRepository: EdcbRepository,
    private val epgStationRepository: EpgStationRepository
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider {

    // --- ルーティングロジック ---
    private suspend fun getActiveProvider(): Any {
        return when (settingsRepository.backendType.first()) {
            "EDCB" -> edcbRepository
            "EPGSTATION" -> epgStationRepository
            else -> konomiRepository // "KONOMITV" や "MIRAKURUN_ONLY" のフォールバック
        }
    }

    // --- LiveProvider ---
    override suspend fun getChannels() = (getActiveProvider() as LiveProvider).getChannels()
    override suspend fun getLiveStreamUrl(channelId: String, quality: String) =
        (getActiveProvider() as LiveProvider).getLiveStreamUrl(channelId, quality)

    override suspend fun getChannelLogoUrl(channelId: String) =
        (getActiveProvider() as LiveProvider).getChannelLogoUrl(channelId)

    // --- RecordProvider ---
    override suspend fun getRecordedPrograms(page: Int) =
        (getActiveProvider() as RecordProvider).getRecordedPrograms(page)

    override suspend fun getRecordedProgram(videoId: Int) =
        (getActiveProvider() as RecordProvider).getRecordedProgram(videoId)

    override suspend fun searchRecordedPrograms(keyword: String, page: Int) =
        (getActiveProvider() as RecordProvider).searchRecordedPrograms(keyword, page)

    override suspend fun getRecordStreamUrl(videoId: Int, quality: String, sessionId: String) =
        (getActiveProvider() as RecordProvider).getRecordStreamUrl(videoId, quality, sessionId)

    override suspend fun getArchivedJikkyo(videoId: Int) =
        (getActiveProvider() as RecordProvider).getArchivedJikkyo(videoId)

    @OptIn(UnstableApi::class)
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) =
        (getActiveProvider() as RecordProvider).keepAlive(videoId, quality, sessionId)

    // --- ReserveProvider ---
    override suspend fun getReserves() = (getActiveProvider() as ReserveProvider).getReserves()
    override suspend fun addReserve(request: ReserveRequest) =
        (getActiveProvider() as ReserveProvider).addReserve(request)

    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest) =
        (getActiveProvider() as ReserveProvider).updateReserve(reservationId, request)

    override suspend fun deleteReservation(reservationId: Int) =
        (getActiveProvider() as ReserveProvider).deleteReservation(reservationId)

    override suspend fun getReservationConditions() =
        (getActiveProvider() as ReserveProvider).getReservationConditions()

    override suspend fun addReservationCondition(request: ReservationConditionAddRequest) =
        (getActiveProvider() as ReserveProvider).addReservationCondition(request)

    override suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ) = (getActiveProvider() as ReserveProvider).updateReservationCondition(conditionId, request)

    override suspend fun deleteReservationCondition(conditionId: Int) =
        (getActiveProvider() as ReserveProvider).deleteReservationCondition(conditionId)

    // --- EpgProvider ---
    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ) = (getActiveProvider() as EpgProvider).getEpgPrograms(startTime, endTime, channelType)

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String) =
        (getActiveProvider() as EpgProvider).getPinnedEpgPrograms(pinnedChannelIds)
}