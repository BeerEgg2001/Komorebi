package com.beeregg2001.komorebi.data.repository

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
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
    private val edcbRepository: EdcbRepository,
    private val epgStationRepository: EpgStationRepository
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider {

    // --- ルーティングロジック ---
    private suspend fun getActiveProvider(): Any {
        return when (settingsRepository.backendType.first()) {
            "EDCB" -> edcbRepository
            "EPGSTATION" -> epgStationRepository
            else -> konomiRepository
        }
    }

    // ========================================================================
    // LiveProvider (ライブ視聴関連)
    // EDCB構築中につき、未実装(NotImplementedError)や通信エラー時は
    // 他のバックエンドに頼らず、空のデータを返してスキップする。
    // ========================================================================

    override suspend fun getChannels(): ChannelApiResponse {
        return try {
            (getActiveProvider() as LiveProvider).getChannels()
        } catch (e: NotImplementedError) {
            Log.w("DtvProviderProxy", "getChannels is not implemented in active backend. Skipping.")
            ChannelApiResponse() // 空のリストを返してスキップ
        } catch (e: Exception) {
            Log.e("DtvProviderProxy", "Error fetching channels. Skipping.", e)
            ChannelApiResponse() // 空のリストを返してスキップ
        }
    }

    override suspend fun getLiveStreamUrl(channelId: String, quality: String): String {
        return try {
            (getActiveProvider() as LiveProvider).getLiveStreamUrl(channelId, quality)
        } catch (e: Exception) {
            Log.w("DtvProviderProxy", "getLiveStreamUrl failed or not implemented. Skipping.")
            "" // 空文字を返してスキップ
        }
    }

    override suspend fun getChannelLogoUrl(channelId: String): String {
        return try {
            (getActiveProvider() as LiveProvider).getChannelLogoUrl(channelId)
        } catch (e: Exception) {
            Log.w("DtvProviderProxy", "getChannelLogoUrl failed or not implemented. Skipping.")
            "" // 空文字を返してスキップ（ロゴなしで表示）
        }
    }

    // ========================================================================
    // RecordProvider, ReserveProvider, EpgProvider
    // ※以下は現在開発対象外のため、呼び出されたらそのままエラーを投げる
    // （アプリ開発時に実装漏れに気づけるようにするため）
    // ========================================================================

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

    @UnstableApi
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
        (getActiveProvider() as RecordProvider).keepAlive(videoId, quality, sessionId)
    }

    override suspend fun getReserves() =
        (getActiveProvider() as ReserveProvider).getReserves()

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
    ) =
        (getActiveProvider() as ReserveProvider).updateReservationCondition(conditionId, request)

    override suspend fun deleteReservationCondition(conditionId: Int) =
        (getActiveProvider() as ReserveProvider).deleteReservationCondition(conditionId)

    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ) =
        (getActiveProvider() as EpgProvider).getEpgPrograms(startTime, endTime, channelType)

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String) =
        (getActiveProvider() as EpgProvider).getPinnedEpgPrograms(pinnedChannelIds)
}