package com.beeregg2001.komorebi.data.repository.epgstation

import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.RecordProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** EPGStation の録画一覧、詳細、配信 URL を提供するリポジトリ。 */
@Singleton
class EpgStationRecordRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val api: EpgStationApi,
    private val channelCache: EpgStationChannelCache
) : RecordProvider {
    private var streamId: Int? = null
    private var qualities: List<StreamQuality>? = null

    /** 録画 DTO を共通モデルへ変換し、詳細時だけ付加情報を取得する。 */
    private suspend fun mapRecorded(item: EsRecordedItem, includeDetails: Boolean): RecordedProgram {
        val file = item.videoFiles?.firstOrNull { it.type == "ts" }
            ?: item.videoFiles?.firstOrNull()
        // 詳細表示のときだけ、チャプター・実尺・シリーズ割当・再生位置を並列で取りに行く。
        // これらはサーバー側で機能を無効化できる (404 が返る) ため、個々の失敗は無視して欠損扱いにする。
        val details = if (includeDetails && file != null) {
            coroutineScope {
                val chaptersJob = async { runCatching { api.getChapters(file.id) }.getOrNull() }
                val durationJob = async { runCatching { api.getDuration(file.id) }.getOrNull() }
                val mappingJob = async { runCatching { api.getSeriesMapping(item.id) }.getOrNull() }
                val positionJob = async { runCatching { api.getPlaybackPosition(file.id) }.getOrNull() }
                RecordedDetails(
                    chapters = chaptersJob.await()?.body()?.chapters.orEmpty(),
                    duration = durationJob.await()?.body()?.duration,
                    mapping = mappingJob.await()?.body(),
                    position = positionJob.await()?.body()?.position ?: 0.0
                )
            }
        } else {
            RecordedDetails()
        }
        val chapters = details.chapters
        val duration = details.duration
        val mapping = details.mapping
        val position = details.position
        val channels = channelCache.getChannels()
        return EpgStationDataMapper.toRecordedProgram(
            item = item,
            channel = channels.firstOrNull { it.id == item.channelId },
            allChannels = channels,
            mapping = mapping,
            chapters = chapters,
            durationOverride = duration,
            playbackPosition = position,
            ip = settings.epgStationIp.first(),
            port = settings.epgStationPort.first()
        )
    }

    /** 録画一覧を取得する。 */
    override suspend fun getRecordedPrograms(page: Int): RecordedApiResponse {
        return try {
            val response = api.getRecordedList(offset = (page - 1) * 24, limit = 24)
            RecordedApiResponse(
                total = response.total,
                recordedPrograms = response.records.map { mapRecorded(it, false) }
            )
        } catch (e: Exception) {
            throw Exception("録画番組の取得に失敗しました。\n[詳細]: ${e.message}", e)
        }
    }

    /** キーワードで録画一覧を検索する。 */
    override suspend fun searchRecordedPrograms(keyword: String, page: Int): RecordedApiResponse {
        return try {
            val response = api.getRecordedList(
                offset = (page - 1) * 24,
                limit = 24,
                keyword = keyword
            )
            RecordedApiResponse(
                total = response.total,
                recordedPrograms = response.records.map { mapRecorded(it, false) }
            )
        } catch (e: Exception) {
            throw Exception("録画番組の検索に失敗しました。\n[詳細]: ${e.message}", e)
        }
    }

    /** recordedId の詳細情報を取得する。 */
    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> {
        return try {
            Result.success(mapRecorded(api.getRecordedDetail(videoId), true))
        } catch (e: Exception) {
            Result.failure(Exception("録画番組の取得に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    /** videoFileId に対応する録画ストリーム URL を生成する。 */
    override suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double
    ): String {
        val parts = quality.split(":", limit = 2)
        val format = parts.firstOrNull()?.lowercase() ?: "direct"
        val mode = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val ip = settings.epgStationIp.first()
        val port = settings.epgStationPort.first()
        return when (format) {
            "hls" -> api.startRecordedHls(videoId, mode, offsetSeconds.toInt()).also {
                streamId = it.streamId
            }.let { UrlBuilder.getEpgStationHlsPlaylistUrl(ip, port, it.streamId) }
            "mp4", "webm" -> UrlBuilder.getEpgStationRecordedStreamUrl(
                ip, port, videoId, format, mode, offsetSeconds
            )
            else -> UrlBuilder.getEpgStationVideoDirectUrl(ip, port, videoId)
        }
    }

    /** EPGStation に実況過去ログがないため空の結果を返す。 */
    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> =
        Result.success(emptyList())

    /** 開始済み HLS ストリームを維持する。 */
    @UnstableApi
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
        streamId?.let { api.keepStream(it) }
    }

    override suspend fun getTiledThumbnailUrl(videoId: Int): String? = null

    /** EPGStation の録画設定から利用可能な画質を生成する。 */
    override suspend fun getStreamQualities(): List<StreamQuality> {
        qualities?.let { return it }
        val result = mutableListOf(StreamQuality("そのまま再生 (無変換)", "direct", true))
        try {
            val config = api.getConfig().streamConfig?.recorded
            listOf("ts" to config?.ts, "encoded" to config?.encoded).forEach { (type, formatConfig) ->
                listOf(
                    "mp4" to formatConfig?.mp4,
                    "hls" to formatConfig?.hls,
                    "webm" to formatConfig?.webm
                ).forEach { (format, labels) ->
                    labels.orEmpty().forEachIndexed { index, label ->
                        result += StreamQuality("$type: $label", "$format:$index")
                    }
                }
            }
        } catch (_: Exception) {
            // 設定取得に失敗しても直接再生は利用できる。
        }
        qualities = result
        return result
    }

    /** 次に見る候補を取得する。 */
    suspend fun getNextUp(recordedId: Int): EsNextUpResult? = api.getNextUp(recordedId)

    /** 音声トラック一覧を取得する。 */
    suspend fun getAudioTracks(videoFileId: Int): List<EsAudioTrack> =
        api.getAudioTracks(videoFileId).body()?.tracks.orEmpty()

    /** 視聴位置を EPGStation へ保存する。 */
    suspend fun updatePlaybackPosition(videoFileId: Int, position: Double, duration: Double) {
        api.updatePlaybackPosition(
            videoFileId,
            EsPlaybackPosition(position = position, duration = duration)
        )
    }

    /** 詳細表示時にだけ取得する付加情報をまとめて持ち回るための入れ物。 */
    private data class RecordedDetails(
        val chapters: List<EsVideoChapter> = emptyList(),
        val duration: Double? = null,
        val mapping: EsSeriesMappingValue? = null,
        val position: Double = 0.0
    )
}
