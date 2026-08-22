package com.beeregg2001.komorebi.data.repository.epgstation

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.jikkyo.JikkyoChannelResolver
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.RecordProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** EPGStation の録画一覧、詳細、配信 URL を提供するリポジトリ。 */
@Singleton
class EpgStationRecordRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val api: EpgStationApi,
    private val channelCache: EpgStationChannelCache,
    private val okHttpClient: OkHttpClient,
    private val jikkyoChannelResolver: JikkyoChannelResolver
) : RecordProvider {
    private var streamId: Int? = null
    private var qualities: List<StreamQuality>? = null

    /**
     * 録画 ID (recordedId) から再生対象のビデオファイル ID (videoFileId) を引くための対応表。
     * EPGStation ではこの2つが別の採番なのに対し、アプリ側は呼び出し箇所によって
     * 録画 ID を渡してくることがある (再生画面のシーク処理など) ため、ここで吸収する。
     */
    private val videoFileIdByRecordedId = ConcurrentHashMap<Int, Int>()
    private val recordedByVideoFileId = ConcurrentHashMap<Int, EsRecordedItem>()

    private val directHttpClient: OkHttpClient by lazy {
        // バックエンド URL 書換え Interceptor を外し、NX-Jikkyo へ直接接続する。
        okHttpClient.newBuilder().apply { interceptors().clear() }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 録画 DTO を共通モデルへ変換し、詳細時だけ付加情報を取得する。 */
    private suspend fun mapRecorded(item: EsRecordedItem, includeDetails: Boolean): RecordedProgram {
        val file = item.videoFiles?.firstOrNull { it.type == "ts" }
            ?: item.videoFiles?.firstOrNull()
        file?.let {
            videoFileIdByRecordedId[item.id] = it.id
            recordedByVideoFileId[it.id] = item
        }
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
        val index = channelCache.getChannelIndex()
        return EpgStationDataMapper.toRecordedProgram(
            item = item,
            channel = index.byId[item.channelId],
            index = index,
            mapping = mapping,
            chapters = chapters,
            durationOverride = duration,
            playbackPosition = position,
            ip = settings.epgStationIp.first(),
            port = settings.epgStationPort.first()
        )
    }

    private var seriesFeatureDisabled = false
    private var seriesCacheAt = 0L
    private var seriesCache: List<EsSeriesListItem>? = null

    /** シリーズ一覧を全件取得する。機能無効時は null を返す。 */
    suspend fun getSeriesList(
        seasonYear: Int? = null,
        seasonName: String? = null,
        onAirOnly: Boolean = false
    ): List<EsSeriesListItem>? {
        if (seriesFeatureDisabled) return null
        val now = System.currentTimeMillis()
        val cached = seriesCache
        if (cached != null && now - seriesCacheAt < 5 * 60 * 1000) {
            return cached.filterSeries(seasonYear, seasonName, onAirOnly)
        }
        return try {
            val first = api.getSeries(
                offset = 0,
                limit = 100,
                seasonYear = seasonYear,
                seasonName = seasonName,
                status = if (onAirOnly) "onair" else null
            )
            if (first.code() == 404) {
                seriesFeatureDisabled = true
                return null
            }
            if (!first.isSuccessful) return emptyList()
            val result = first.body() ?: return emptyList()
            val items = result.items.toMutableList()
            while (items.size < result.total) {
                val page = api.getSeries(
                    offset = items.size,
                    limit = 100,
                    seasonYear = seasonYear,
                    seasonName = seasonName,
                    status = if (onAirOnly) "onair" else null
                )
                if (page.code() == 404) {
                    seriesFeatureDisabled = true
                    return null
                }
                if (!page.isSuccessful) break
                val body = page.body() ?: break
                if (body.items.isEmpty()) break
                items.addAll(body.items)
            }
            if (seasonYear == null && seasonName == null && !onAirOnly) {
                seriesCache = items
                seriesCacheAt = now
            }
            items.filterSeries(seasonYear, seasonName, onAirOnly)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** シリーズ詳細（話数付きの録画一覧つき）を取得する。 */
    suspend fun getSeriesDetail(seriesId: Int): EsSeriesDetail? {
        if (seriesFeatureDisabled) return null
        return try {
            val response = api.getSeriesDetail(seriesId)
            if (response.code() == 404) {
                seriesFeatureDisabled = true
                null
            } else {
                response.body().takeIf { response.isSuccessful }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** シリーズのアイキャッチ画像 URL を生成する。 */
    suspend fun getSeriesImageUrl(seriesId: Int, hasImage: Boolean): String? {
        if (!hasImage) return null
        val ip = settings.epgStationIp.first()
        val port = settings.epgStationPort.first()
        return UrlBuilder.getEpgStationSeriesImageUrl(ip, port, seriesId)
    }

    private fun List<EsSeriesListItem>.filterSeries(
        seasonYear: Int?,
        seasonName: String?,
        onAirOnly: Boolean
    ): List<EsSeriesListItem> = filter {
        (seasonYear == null || it.seasonYear == seasonYear) &&
            (seasonName == null || it.seasonName == seasonName) &&
            (!onAirOnly || it.isOnAir)
    }

    /** 録画一覧の既定件数。画面表示と検索でも通信回数を抑える。 */
    private companion object {
        const val TAG = "EpgStationRecordRepo"
        const val DEFAULT_RECORDED_LIMIT = 100
        const val MAX_RECORDED_LOOKUP = 2_000
        const val MAX_JIKKYO_DURATION_MS = 3L * 24 * 60 * 60 * 1000
        const val MAX_JIKKYO_REQUESTS = 16
        val COMMENT_COLORS = mapOf(
            "white" to "#FFEAEA", "red" to "#F02840", "pink" to "#FD7E80",
            "orange" to "#FDA708", "yellow" to "#FFE133", "green" to "#64DD17",
            "cyan" to "#00D4F5", "blue" to "#4763FF", "purple" to "#D500F9",
            "black" to "#1E1310", "white2" to "#CCCC99", "niconicowhite" to "#CCCC99",
            "red2" to "#CC0033", "truered" to "#CC0033", "pink2" to "#FF33CC",
            "orange2" to "#FF6600", "passionorange" to "#FF6600", "yellow2" to "#999900",
            "madyellow" to "#999900", "green2" to "#00CC66", "elementalgreen" to "#00CC66",
            "cyan2" to "#00CCCC", "blue2" to "#3399FF", "marineblue" to "#3399FF",
            "purple2" to "#6633CC", "nobleviolet" to "#6633CC", "black2" to "#666666"
        )
    }

    /** 録画一覧を取得する。 */
    override suspend fun getRecordedPrograms(page: Int): RecordedApiResponse =
        getRecordedPrograms(page = page, limit = DEFAULT_RECORDED_LIMIT)

    /** 指定件数で録画一覧を取得する。同期時は端末プロファイルから渡す。 */
    override suspend fun getRecordedPrograms(page: Int, limit: Int): RecordedApiResponse {
        return try {
            val safeLimit = limit.coerceIn(1, 200)
            val response = api.getRecordedList(
                offset = (page - 1) * safeLimit,
                limit = safeLimit
            )
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
                offset = (page - 1) * DEFAULT_RECORDED_LIMIT,
                limit = DEFAULT_RECORDED_LIMIT,
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

    /**
     * 録画ストリーム URL を生成する。
     * 引数には videoFileId が渡ってくる想定だが、再生画面のシーク処理などからは
     * 録画 ID (recordedId) が渡ってくるため、対応表に載っていれば読み替える。
     */
    override suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double
    ): String {
        val videoFileId = resolveVideoFileId(videoId)
        val parts = quality.split(":", limit = 2)
        val format = parts.firstOrNull()?.lowercase() ?: "direct"
        val mode = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val ip = settings.epgStationIp.first()
        val port = settings.epgStationPort.first()
        return when (format) {
            "hls" -> api.startRecordedHls(videoFileId, mode, offsetSeconds.toInt()).also {
                streamId = it.streamId
            }.let { UrlBuilder.getEpgStationHlsPlaylistUrl(ip, port, it.streamId) }
            "mp4", "webm" -> UrlBuilder.getEpgStationRecordedStreamUrl(
                ip, port, videoFileId, format, mode, offsetSeconds
            )
            else -> UrlBuilder.getEpgStationVideoDirectUrl(ip, port, videoFileId)
        }
    }

    /**
     * 渡された ID が録画 ID なら対応するビデオファイル ID へ読み替える。
     * 対応表に無ければ、既にビデオファイル ID だとみなしてそのまま返す。
     */
    private suspend fun resolveVideoFileId(videoId: Int): Int {
        videoFileIdByRecordedId[videoId]?.let { return it }
        // 一覧・詳細をまだ通っていない場合に備え、録画詳細から引き直す。
        // ただし api.getRecordedDetail() は recordedId 専用の API であり、
        // videoFileId を渡すと recordedId と videoFileId の採番衝突により
        // まったく別の録画の詳細が返ってくることがある。
        // 返ってきた item.id (recordedId) が渡した videoId と一致する場合に限り
        // 「渡されたのは recordedId だった」と判断して videoFileId へ読み替える。
        // 一致しない場合は別録画の詳細を誤って掴んだということなので、
        // 渡された videoId は既に videoFileId だったとみなしてそのまま返す。
        return runCatching {
            val item = api.getRecordedDetail(videoId)
            if (item.id != videoId) return videoId
            val file = item.videoFiles?.firstOrNull { it.type == "ts" }
                ?: item.videoFiles?.firstOrNull()
            file?.id?.also { videoFileIdByRecordedId[videoId] = it }
        }.getOrNull() ?: videoId
    }

    /** EPGStation の録画情報を基準に NX-Jikkyo の実況過去ログを取得する。 */
    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val recorded = recordedByVideoFileId[videoId]
                    // api.getRecordedDetail() は recordedId 専用の API だが、ここに渡ってくる
                    // videoId は videoFileId であることが前提になっている。recordedId と
                    // videoFileId は別採番でどちらも小さい整数のため、たまたま同じ値の
                    // 別番組の録画が存在すると誤って取得してしまう。
                    // 取得できた録画の videoFiles に渡した videoId と一致する id が
                    // 実在するかを検証し、含まれていなければ「別番組を誤って引いた」と判断して
                    // この結果は採用せず (キャッシュにも入れず)、全件走査のフォールバックに委ねる。
                    ?: runCatching { api.getRecordedDetail(videoId) }.getOrNull()
                        ?.takeIf { item -> item.videoFiles.orEmpty().any { it.id == videoId } }
                        ?.also { item ->
                            item.videoFiles.orEmpty().forEach { file -> recordedByVideoFileId[file.id] = item }
                        }
                    ?: findRecordedByVideoFileId(videoId)
                    ?: throw Exception("録画ファイルに対応する録画情報が見つかりません。")
                val channel = channelCache.getChannelIndex().byId[recorded.channelId]
                    ?: throw Exception("録画番組のチャンネル情報が見つかりません。")
                // 録画実況(過去ログ)は JIKKYO_CHANNEL_ID_MAP に無いチャンネルでも
                // NX-Jikkyo の kakolog API から取得できる可能性があるため、絞り込みを掛けない。
                val jikkyoId = jikkyoChannelResolver.getJikkyoId(
                    channel.networkId.toInt(),
                    channel.serviceId.toInt(),
                    restrictToKnownChannels = false
                ) ?: throw Exception("このチャンネルは実況過去ログに対応していません。")
                val file = recorded.videoFiles?.firstOrNull { it.id == videoId }
                    ?: recorded.videoFiles?.firstOrNull { it.type == "ts" }
                    ?: recorded.videoFiles?.firstOrNull()
                    ?: throw Exception("再生対象の録画ファイルが見つかりません。")
                val videoFileId = file.id
                val metadata = if (file.startAt == null || file.duration == null) {
                    runCatching { api.getVideoFileMetadata(videoFileId) }.getOrNull()
                } else {
                    null
                }
                // file.startAt / metadata.startAt が取れない場合、最終手段として番組の
                // 開始時刻 (recorded.startAt) を使う。しかしこれは「番組」の開始時刻であり、
                // 録画ファイルの先頭時刻（録画前マージンを含みうる）とは一致しないため、
                // このフォールバックに入るとコメントの表示タイミングが数秒〜数十秒ズレる
                // 可能性がある。API 側から実開始時刻が得られない以上正確な補正はできないので、
                // 少なくともこの経路を通ったことが分かるよう警告ログを残す。
                val startAtMs = file.startAt ?: metadata?.startAt ?: recorded.startAt.also {
                    Log.w(TAG, "録画ファイルの実開始時刻が取得できないため、番組の開始時刻で代用します。実況コメントの時刻がズレる可能性があります。 videoFileId=$videoId")
                }
                val durationMs = (file.duration ?: metadata?.duration)?.takeIf { it > 0 }
                    ?.let { (it * 1000).toLong() }
                    ?: (recorded.endAt - recorded.startAt).coerceAtLeast(0)

                fetchArchivedComments(jikkyoId, startAtMs, startAtMs + durationMs)
            }.onFailure { Log.e(TAG, "実況過去ログの取得に失敗しました。", it) }
        }

    /** キャッシュに無い場合は録画一覧をページングし、videoFileId から録画を引き直す。 */
    private suspend fun findRecordedByVideoFileId(videoFileId: Int): EsRecordedItem? {
        var offset = 0
        while (offset < MAX_RECORDED_LOOKUP) {
            val page = api.getRecordedList(offset = offset, limit = DEFAULT_RECORDED_LIMIT)
            page.records.forEach { item ->
                item.videoFiles.orEmpty().forEach { file -> recordedByVideoFileId[file.id] = item }
            }
            recordedByVideoFileId[videoFileId]?.let { return it }
            if (page.records.isEmpty() || offset + page.records.size >= page.total) break
            offset += page.records.size
        }
        return null
    }

    /** NX-Jikkyo API の上限に合わせ、長時間録画を3日単位で取得する。 */
    private fun fetchArchivedComments(
        jikkyoId: String,
        startAtMs: Long,
        endAtMs: Long
    ): List<ArchivedComment> {
        if (startAtMs >= endAtMs) return emptyList()
        val comments = mutableListOf<ArchivedComment>()
        var chunkStartAt = startAtMs
        var requestCount = 0
        while (chunkStartAt < endAtMs && requestCount < MAX_JIKKYO_REQUESTS) {
            val chunkEndAt = minOf(chunkStartAt + MAX_JIKKYO_DURATION_MS, endAtMs)
            val url = "https://jikkyo.tsukumijima.net/api/kakolog/$jikkyoId" +
                "?starttime=${chunkStartAt / 1000}&endtime=${chunkEndAt / 1000}&format=json"
            directHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("NX-Jikkyo APIエラー: HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                json.optString("error").takeIf { it.isNotBlank() }?.let { throw Exception(it) }
                comments += parseArchivedComments(json, startAtMs)
            }
            chunkStartAt = chunkEndAt
            requestCount++
        }
        return comments.sortedBy { it.time }
    }

    /** APIレスポンスをプレイヤー共通の過去ログコメントへ変換する。 */
    private fun parseArchivedComments(json: JSONObject, startAtMs: Long): List<ArchivedComment> {
        val packets = json.optJSONArray("packet") ?: return emptyList()
        return buildList {
            for (index in 0 until packets.length()) {
                val chat = packets.optJSONObject(index)?.optJSONObject("chat") ?: continue
                val text = chat.optString("content")
                if (text.isBlank() || text.startsWith("/") || chat.optString("deleted") == "1") continue
                val commands = chat.optString("mail").split(' ')
                val seconds = chat.optDouble("date", Double.NaN)
                if (!seconds.isFinite()) continue
                val usec = chat.optDouble("date_usec", 0.0)
                add(
                    ArchivedComment(
                        time = seconds + usec / 1_000_000.0 - startAtMs / 1000.0,
                        text = text,
                        color = commands.firstNotNullOfOrNull { COMMENT_COLORS[it.lowercase()] }
                            ?: "#FFEAEA",
                        author = chat.optString("user_id"),
                        type = when {
                            "ue" in commands -> "top"
                            "shita" in commands -> "bottom"
                            else -> "right"
                        },
                        size = when {
                            "big" in commands -> "big"
                            "small" in commands -> "small"
                            else -> "medium"
                        }
                    )
                )
            }
        }
    }

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
