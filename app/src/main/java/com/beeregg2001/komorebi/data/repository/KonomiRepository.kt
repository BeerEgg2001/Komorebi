package com.beeregg2001.komorebi.data.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Komorebi_Repo"

/**
 * KonomiTVバックエンド（API）との通信を抽象化するリポジトリ。
 * ローカルDBに関する処理はそれぞれ WatchHistoryRepository, LastChannelRepository に分離しました。
 */
@Singleton
class KonomiRepository @Inject constructor(
    private val apiService: KonomiApi,
    // ★ 追加: URL生成のためにIP/Portを取得する SettingsRepository を Inject
    private val settingsRepository: SettingsRepository
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider { // ★ インターフェースを実装

    // ==========================================
    // ユーザー設定・セッション管理
    // ==========================================
    private val _currentUser = MutableStateFlow<KonomiUser?>(null)
    val currentUser: StateFlow<KonomiUser?> = _currentUser.asStateFlow()

    /**
     * 現在ログインしているKonomiTVユーザーの情報を取得・更新します。
     * バックエンドのセッション維持（セッション切れ防止）の役割も兼ねています。
     */
    suspend fun refreshUser() {
        runCatching { apiService.getCurrentUser() }
            .onSuccess { _currentUser.value = it }
    }

    // ==========================================
    // チャンネル・録画リスト取得
    // ==========================================

    override suspend fun getChannels(): ChannelApiResponse {
        try {
            return apiService.getChannels()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch KonomiTV channels", e)
            // ★ 修正: エラーを握りつぶして空リストを返さず、例外をスローして知らせる
            throw Exception("KonomiTVからのチャンネル一覧取得に失敗しました。\nサーバーが稼働しているか確認してください。\n[詳細]: ${e.message}")
        }
    }

    override suspend fun getRecordedPrograms(page: Int): RecordedApiResponse {
        return try {
            val response = apiService.getRecordedPrograms(page = page, order = "desc")

            val ip = settingsRepository.konomiIp.first()
            val port = settingsRepository.konomiPort.first()
            val updatedPrograms = response.recordedPrograms.map { program ->
                val fallbackUrl =
                    UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, program.id.toString())
                program.copy(apiThumbnailUrl = fallbackUrl)
            }

            response.copy(recordedPrograms = updatedPrograms)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recorded programs", e)
            // ★ 修正: 例外をスロー
            throw Exception("録画番組の取得に失敗しました。\nKonomiTVサーバーの状態を確認してください。\n[詳細]: ${e.message}")
        }
    }

    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> {
        return try {
            val program = apiService.getRecordedProgram(videoId)
            val ip = settingsRepository.konomiIp.first()
            val port = settingsRepository.konomiPort.first()
            val fallbackUrl =
                UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, program.id.toString())
            Result.success(program.copy(apiThumbnailUrl = fallbackUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recorded program $videoId", e)
            // ★ 修正
            Result.failure(Exception("録画番組詳細の取得に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun searchRecordedPrograms(
        keyword: String,
        page: Int
    ): RecordedApiResponse {
        return try {
            Log.d(TAG, "Calling API searchVideos. Keyword: $keyword, Page: $page")
            apiService.searchVideos(keyword = keyword, page = page)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search recorded programs", e)
            // ★ 修正: 例外をスロー
            throw Exception("録画番組の検索に失敗しました。\n[詳細]: ${e.message}")
        }
    }

    @OptIn(UnstableApi::class)
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
        try {
            val response = apiService.keepAlive(videoId, quality, sessionId)
            if (!response.isSuccessful) {
                Log.w(TAG, "KeepAlive Failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to keep alive", e)
        }
    }

    // ★ 追加: KonomiTV仕様のタイル画像URLを生成
    override suspend fun getTiledThumbnailUrl(videoId: Int): String? {
        val ip = settingsRepository.konomiIp.first()
        val port = settingsRepository.konomiPort.first()
        // 既存の UrlBuilder.getTiledThumbnailUrl をそのまま利用します
        return UrlBuilder.getTiledThumbnailUrl(ip, port, videoId)
    }

    // ==========================================
    // マイリスト・視聴履歴の管理 (API通信のみ)
    // ==========================================

    suspend fun getBookmarks(): Result<List<KonomiProgram>> =
        runCatching { apiService.getBookmarks() }

    suspend fun getWatchHistory(): Result<List<KonomiHistoryProgram>> =
        runCatching { apiService.getWatchHistory() }

    // ==========================================
    // ニコニコ実況 (コメント) 関連
    // ==========================================

    suspend fun getJikkyoInfo(channelId: String) = runCatching {
        apiService.getJikkyoInfo(channelId)
    }

    suspend fun syncPlaybackPosition(programId: String, position: Double) {
        runCatching { apiService.updateWatchHistory(HistoryUpdateRequest(programId, position)) }
    }

    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> {
        return try {
            val response = apiService.getArchivedJikkyo(videoId)
            Result.success(if (response.is_success) response.comments else emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch archived jikkyo", e)
            // ★ 修正
            Result.failure(Exception("過去ログ実況の取得に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    // ==========================================
    // 録画予約（EDCB連携）の管理
    // ==========================================

    override suspend fun getReserves(): Result<List<ReserveItem>> {
        return try {
            Result.success(apiService.getReserves().reservations)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch reserves", e)
            // ★ 修正
            Result.failure(Exception("予約一覧の取得に失敗しました。\nKonomiTVサーバーの状態を確認してください。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun addReserve(request: ReserveRequest): Result<Unit> {
        return try {
            val response = apiService.addReserve(request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                throw Exception("Reservation failed: ${response.code()} $errorBody")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add reserve", e)
            // ★ 修正
            Result.failure(Exception("予約の追加に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> {
        return try {
            val response = apiService.updateReserve(reservationId, request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                throw Exception("Update reservation failed: ${response.code()} $errorBody")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update reserve", e)
            // ★ 修正
            Result.failure(Exception("予約の更新に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun deleteReservation(reservationId: Int): Result<Unit> {
        return try {
            val response = apiService.deleteReservation(reservationId)
            if (!response.isSuccessful) {
                if (response.code() == 404) {
                    Log.w(TAG, "Reservation $reservationId not found (already deleted?)")
                    return Result.success(Unit)
                }
                throw Exception(
                    "Delete reservation failed: ${response.code()} ${
                        response.errorBody()?.string()
                    }"
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete reservation", e)
            // ★ 修正
            Result.failure(Exception("予約の削除に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> {
        return try {
            val response = apiService.getReservationConditions()
            Result.success(response.reservationConditions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch reservation conditions", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの取得に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> {
        return try {
            val response = apiService.addReservationCondition(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                throw Exception("Failed to add condition: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの追加に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> {
        return try {
            val condition = apiService.updateReservationCondition(conditionId, request)
            Result.success(condition)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの更新に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> {
        return try {
            val response = apiService.deleteReservationCondition(conditionId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                throw Exception("Failed to delete condition: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの削除に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    // ==========================================
    // ★ 追加: UrlBuilderへの依存をリポジトリ内に隠蔽
    // ==========================================

    override suspend fun getLiveStreamUrl(
        channelId: String,
        quality: String,
        streamNumber: Int
    ): String {
        val ip = settingsRepository.konomiIp.first()
        val port = settingsRepository.konomiPort.first()
        return UrlBuilder.getKonomiTvLiveStreamUrl(ip, port, channelId, quality)
    }

    override suspend fun getChannelLogoUrl(channelId: String): String {
        val backend = settingsRepository.backendType.first()

        return if (backend == "MIRAKURUN_ONLY") {
            val ip = settingsRepository.mirakurunIp.first()
            val port = settingsRepository.mirakurunPort.first()

            // "mirakurun_32736_1024" のようなIDからネットワークIDとサービスIDを抽出
            val parts = channelId.split("_")
            val nid = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val sid = parts.getOrNull(2)?.toLongOrNull() ?: 0L

            UrlBuilder.getMirakurunLogoUrl(ip, port, nid, sid)
        } else {
            val ip = settingsRepository.konomiIp.first()
            val port = settingsRepository.konomiPort.first()
            UrlBuilder.getKonomiTvLogoUrl(ip, port, channelId)
        }
    }

    override suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSec: Double
    ): String {
        val ip = settingsRepository.konomiIp.first()
        val port = settingsRepository.konomiPort.first()
        return UrlBuilder.getVideoPlaylistUrl(ip, port, videoId, sessionId, quality)
    }

    // ==========================================
    // ★ 追加: EpgProvider の実装
    // ※ 以前 EpgRepository 内にあった KonomiTvApiService の通信処理をここに移動
    // ==========================================
    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ): List<EpgChannelWrapper> {
        return try {
            apiService.getEpgPrograms(startTime, endTime, channelType).channels
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch EPG from KonomiTV", e)
            // ★ 修正: 例外をスロー
            throw Exception("KonomiTVからの番組表データ取得に失敗しました。\n[詳細]: ${e.message}")
        }
    }

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        return apiService.getEpgPrograms(pinnedChannelIds = pinnedChannelIds).channels
    }
}