package com.beeregg2001.komorebi.data.repository

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
// ★ 追加: 分割された新しいEDCBリポジトリ群をインポート
import com.beeregg2001.komorebi.data.repository.edcb.EdcbLiveRepository
import com.beeregg2001.komorebi.data.repository.edcb.EdcbRecordRepository
import com.beeregg2001.komorebi.data.repository.edcb.EdcbReserveRepository
import com.beeregg2001.komorebi.data.repository.edcb.EdcbEpgRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ユーザーの設定（SettingsRepository）に応じて、リクエストを適切なバックエンド（Repository）に
 * 動的にルーティングする「代理人（Proxy）」クラスです。
 */
@Singleton
class DtvProviderProxy @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val konomiRepository: KonomiRepository,
    private val epgStationRepository: EpgStationRepository,
    // ★ 修正: 旧 EdcbRepository を削除し、分割した4つのRepositoryをInjectする
    private val edcbLiveRepository: EdcbLiveRepository,
    private val edcbRecordRepository: EdcbRecordRepository,
    private val edcbReserveRepository: EdcbReserveRepository,
    private val edcbEpgRepository: EdcbEpgRepository
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider {

    // --- ルーティングロジック（インターフェースごとに特化） ---

    private suspend fun getLiveProvider(): LiveProvider {
        return when (settingsRepository.backendType.first()) {
            "EDCB" -> edcbLiveRepository
            "EPGSTATION" -> epgStationRepository
            else -> konomiRepository
        }
    }

    private suspend fun getRecordProvider(): RecordProvider {
        return when (settingsRepository.backendType.first()) {
            "EDCB" -> edcbRecordRepository
            "EPGSTATION" -> epgStationRepository
            else -> konomiRepository
        }
    }

    private suspend fun getReserveProvider(): ReserveProvider {
        return when (settingsRepository.backendType.first()) {
            "EDCB" -> edcbReserveRepository
            "EPGSTATION" -> epgStationRepository
            else -> konomiRepository
        }
    }

    private suspend fun getEpgProvider(): EpgProvider {
        return when (settingsRepository.backendType.first()) {
            "EDCB" -> edcbEpgRepository
            "EPGSTATION" -> epgStationRepository
            else -> konomiRepository
        }
    }

    // ========================================================================
    // LiveProvider (ライブ視聴関連)
    // ========================================================================

    override suspend fun getChannels(): ChannelApiResponse {
        return try {
            getLiveProvider().getChannels()
        } catch (e: NotImplementedError) {
            Log.w("DtvProviderProxy", "getChannels is not implemented in active backend. Skipping.")
            ChannelApiResponse()
        } catch (e: Exception) {
            Log.e("DtvProviderProxy", "Error fetching channels. Skipping.", e)
            ChannelApiResponse()
        }
    }

    override suspend fun getLiveStreamUrl(
        channelId: String,
        quality: String,
        streamNumber: Int
    ): String {
        return try {
            getLiveProvider().getLiveStreamUrl(channelId, quality, streamNumber)
        } catch (e: Exception) {
            Log.w("DtvProviderProxy", "getLiveStreamUrl failed or not implemented. Skipping.")
            ""
        }
    }

    override suspend fun getChannelLogoUrl(channelId: String): String {
        return try {
            getLiveProvider().getChannelLogoUrl(channelId)
        } catch (e: Exception) {
            Log.w("DtvProviderProxy", "getChannelLogoUrl failed or not implemented. Skipping.")
            ""
        }
    }

    // ========================================================================
    // RecordProvider (録画視聴関連)
    // ========================================================================

    override suspend fun getRecordedPrograms(page: Int) =
        getRecordProvider().getRecordedPrograms(page)

    override suspend fun getRecordedProgram(videoId: Int) =
        getRecordProvider().getRecordedProgram(videoId)

    override suspend fun searchRecordedPrograms(keyword: String, page: Int) =
        getRecordProvider().searchRecordedPrograms(keyword, page)

    override suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double
    ) =
        getRecordProvider().getRecordStreamUrl(videoId, quality, sessionId, offsetSeconds)

    override suspend fun getArchivedJikkyo(videoId: Int) =
        getRecordProvider().getArchivedJikkyo(videoId)

    @UnstableApi
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
        getRecordProvider().keepAlive(videoId, quality, sessionId)
    }

    override suspend fun getTiledThumbnailUrl(videoId: Int): String? =
        getRecordProvider().getTiledThumbnailUrl(videoId)

    override suspend fun getStreamQualities(): List<StreamQuality> =
        getRecordProvider().getStreamQualities()

    // ========================================================================
    // ReserveProvider (録画予約関連)
    // ========================================================================

    override suspend fun getReserves() =
        getReserveProvider().getReserves()

    override suspend fun addReserve(request: ReserveRequest) =
        getReserveProvider().addReserve(request)

    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest) =
        getReserveProvider().updateReserve(reservationId, request)

    override suspend fun deleteReservation(reservationId: Int) =
        getReserveProvider().deleteReservation(reservationId)

    override suspend fun getReservationConditions() =
        getReserveProvider().getReservationConditions()

    override suspend fun addReservationCondition(request: ReservationConditionAddRequest) =
        getReserveProvider().addReservationCondition(request)

    override suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ) =
        getReserveProvider().updateReservationCondition(conditionId, request)

    override suspend fun deleteReservationCondition(conditionId: Int) =
        getReserveProvider().deleteReservationCondition(conditionId)

    // ========================================================================
    // EpgProvider (番組表関連)
    // ========================================================================

    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ) =
        getEpgProvider().getEpgPrograms(startTime, endTime, channelType)

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String) =
        getEpgProvider().getPinnedEpgPrograms(pinnedChannelIds)
}