package com.beeregg2001.komorebi.data.repository.edcb

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.edcb.*
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.RecordProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdcbRecordRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val cacheManager: EdcbEpgCacheManager,
    private val liveRepository: EdcbLiveRepository
) : RecordProvider {

    companion object {
        private const val TAG = "EdcbRecordRepository"
        private const val MAX_ALLOWED_DROPS = 1000L
    }

    private val baseEdcbHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().apply { interceptors().clear() }.build()
    }

    private val recordMutex = Mutex()
    private var cachedRecInfos: List<EdcbRecFileInfo>? = null
    private var lastRecFetchTime = 0L

    private data class KomorebiResolverUrls(
        val videoUrl: String, val thumbnailUrl: String, val chapterUrl: String,
        val chapterAltUrl: String, val tileImageUrl: String, val tileJsonUrl: String
    )

    private data class EdcbResolverSettings(
        val ctokXcode: String,
        val ctokView: String,
        val options: List<StreamQuality>
    )

    private suspend fun getTcpIpAndPort(): Pair<String, Int> {
        val rawIp = settingsRepository.edcbIp.first()
        val cleanIp = rawIp.replace(Regex("^https?://"), "")
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
        return Pair(cleanIp, port)
    }

    private suspend fun getHttpBaseUrl(): String {
        return settingsRepository.getEdcbFullUrl()
    }

    // -----------------------------------------------------------------------------------------
    // resolver.lua から共通設定 (ctok, options) を取得するメソッド
    // エラーレスポンスを受け取った場合はExceptionを投げ、UI側(ダイアログ)で表示させる
    // -----------------------------------------------------------------------------------------
    private suspend fun fetchResolverSettings(baseUrl: String): EdcbResolverSettings? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/komorebi/resolver.lua"
                val request = Request.Builder().url(url).build()
                baseEdcbHttpClient.newCall(request).execute().use { response ->

                    // ★ 修正: JSONパースの前に、HTTPステータスコードを最優先でチェックする
                    // 404等でHTMLが返ってきている場合に即座に例外を投げるための防波堤
                    if (!response.isSuccessful) {
                        throw Exception("HTTP Error ${response.code}\nresolver.lua へのアクセスに失敗しました。\nファイルが正しく配置されているか確認してください。")
                    }

                    val jsonStr = response.body?.string() ?: return@withContext null

                    // ★ 修正: プレーンテキスト(Luaエラー等)が返ってきた際のクラッシュ(Value Error)を防ぐためtry-catchで保護
                    val json = try {
                        JSONObject(jsonStr)
                    } catch (e: Exception) {
                        throw Exception("resolver.lua の応答が不正(非JSON)です。\nファイルが正しく配置されているか確認してください。\n[詳細]: ${e.message}")
                    }

                    // 既存: resolver.luaからのエラー(フェールセーフ含む)をキャッチ
                    if (json.has("error")) {
                        val errMsg = json.optString("error", "Unknown Resolver Error")
                        val errDetail = json.optString("detail", "")
                        val fullMsg = if (errDetail.isNotBlank()) "$errMsg\n$errDetail" else errMsg
                        Log.e(TAG, "Resolver Lua Error: $fullMsg")
                        // 例外をスローして上位へ伝搬させる
                        throw Exception("Komorebi Resolver エラー:\n$fullMsg")
                    }

                    val ctokObj = json.optJSONObject("ctok")
                    val ctokXcode = ctokObj?.optString("xcode") ?: ""
                    val ctokView = ctokObj?.optString("view") ?: ""

                    val optionsArray = json.optJSONArray("option")
                    val qualities = mutableListOf<StreamQuality>()
                    if (optionsArray != null) {
                        for (i in 0 until optionsArray.length()) {
                            val opt = optionsArray.getJSONObject(i)
                            val value = opt.optString("id")
                            val label = opt.optString("name")
                            Log.i(TAG, "Found stream quality: $label ($value)")
                            qualities.add(
                                StreamQuality(
                                    label = label,
                                    value = value,
                                    isRawTs = false
                                )
                            )
                        }
                    }
                    return@withContext EdcbResolverSettings(ctokXcode, ctokView, qualities)
                }
            } catch (e: Exception) {
                // そのまま例外として再スローして、上位のViewModel側でキャッチ・ダイアログ表示させる
                Log.e(TAG, "Failed to fetch resolver settings", e)
                throw e
            }
        }

    override suspend fun getStreamQualities(): List<StreamQuality> = withContext(Dispatchers.IO) {
        try {
            val playMethod = settingsRepository.edcbRecordPlayMethod.first()
            if (playMethod == "DIRECT") {
                return@withContext listOf(
                    StreamQuality(label = "オリジナル (Direct)", value = "direct", isRawTs = true)
                )
            }

            val baseUrl = getHttpBaseUrl()
            val settings = fetchResolverSettings(baseUrl)
            return@withContext settings?.options ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch stream qualities from EDCB", e)
            // ★ 修正: エラーを握りつぶして空リストを返さず、UI層へ伝搬させる
            throw e
        }
    }

    // -----------------------------------------------------------------------------------------
    // resolver.lua から指定した動画IDのURL群を取得するメソッド
    // エラーレスポンスを受け取った場合はExceptionを投げ、UI側(ダイアログ)で表示させる
    // -----------------------------------------------------------------------------------------
    private suspend fun fetchResolverUrls(baseUrl: String, videoId: Int): KomorebiResolverUrls? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/komorebi/resolver.lua?id=$videoId"
                val request = Request.Builder().url(url).build()
                val client = baseEdcbHttpClient.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS).build()

                client.newCall(request).execute().use { response ->

                    // ★ 修正: JSONパースの前にHTTPステータスコードをチェック
                    if (!response.isSuccessful) {
                        throw Exception("HTTP Error ${response.code}\nresolver.lua へのアクセスに失敗しました。\nファイルが正しく配置されているか確認してください。")
                    }

                    val jsonStr = response.body?.string() ?: return@withContext null

                    // ★ 修正: JSONパースをtry-catchで保護。HTML等の不正なレスポンスでクラッシュさせない
                    val json = try {
                        JSONObject(jsonStr)
                    } catch (e: Exception) {
                        throw Exception("resolver.lua の応答が不正(非JSON)です。\nファイルが正しく配置されているか確認してください。\n[詳細]: ${e.message}")
                    }

                    // 既存: resolver.luaからのエラー(フェールセーフ含む)をキャッチ
                    if (json.has("error")) {
                        val errMsg = json.optString("error", "Unknown Resolver Error")
                        val errDetail = json.optString("detail", "")
                        val fullMsg = if (errDetail.isNotBlank()) "$errMsg\n$errDetail" else errMsg
                        Log.e(TAG, "Resolver Lua Error for ID $videoId: $fullMsg")
                        // 例外をスローして上位へ伝搬させる
                        throw Exception("Komorebi Resolver エラー:\n$fullMsg")
                    }

                    return@withContext KomorebiResolverUrls(
                        videoUrl = json.optString("video_url"),
                        thumbnailUrl = json.optString("thumbnail_url"),
                        chapterUrl = json.optString("chapter_url"),
                        chapterAltUrl = json.optString("chapter_alt_url"),
                        tileImageUrl = json.optString("tile_image_url"),
                        tileJsonUrl = json.optString("tile_json_url")
                    )
                }
            } catch (e: Exception) {
                // 再スローして、上位のViewModel側でキャッチさせる
                Log.e(TAG, "Failed to fetch resolver urls for id=$videoId", e)
                throw e
            }
        }

    private fun isValidRecord(info: EdcbRecFileInfo): Boolean {
        if (info.drops > MAX_ALLOWED_DROPS) return false
        if (info.recFilePath.isBlank() || info.recFilePath.contains(
                "録画ファイルがありません",
                ignoreCase = true
            )
        ) return false
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getRecordedPrograms(page: Int): RecordedApiResponse =
        withContext(Dispatchers.IO) {
            recordMutex.withLock {
                try {
                    if (page == 1) Log.i(TAG, "[getRecordedPrograms] 録画同期を開始します")
                    val (ip, port) = getTcpIpAndPort()
                    if (ip.isBlank()) return@withContext RecordedApiResponse(0, emptyList())

                    if (cachedRecInfos == null || (System.currentTimeMillis() - lastRecFetchTime) > 30_000L) {
                        val result = EdcbApi(ip, port).getRecInfosFull()
                        if (result.isSuccess) {
                            val validInfos =
                                result.getOrNull()?.filter { isValidRecord(it) } ?: emptyList()
                            cachedRecInfos = validInfos.sortedByDescending { it.startTime }
                            lastRecFetchTime = System.currentTimeMillis()
                        } else {
                            return@withContext RecordedApiResponse(0, emptyList())
                        }
                    }

                    val all = cachedRecInfos ?: emptyList()
                    val total = all.size
                    val from = (page - 1) * 50
                    if (from >= total) return@withContext RecordedApiResponse(total, emptyList())

                    val to = (from + 50).coerceAtMost(total)
                    val baseUrl = getHttpBaseUrl()

                    val programs = all.subList(from, to).map { info ->
                        async { mapToRecordedProgram(info, ip, baseUrl) }
                    }.awaitAll()

                    Log.i(TAG, "[getRecordedPrograms] Page $page 返却完了 (件数: ${programs.size})")
                    RecordedApiResponse(total, programs)
                } catch (e: Exception) {
                    Log.e(TAG, "同期エラー", e)
                    // ★ 修正: TCP通信エラーとresolver.luaのエラーを明確に区別し、
                    // エラーを握りつぶして空リストを返すのではなく、例外を上に投げてUIにダイアログを出させる
                    throw e
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> =
        withContext(Dispatchers.IO) {
            try {
                val (ip, port) = getTcpIpAndPort()
                val info =
                    cachedRecInfos?.find { it.id == videoId } ?: EdcbApi(ip, port).getRecInfo(
                        videoId
                    ).getOrNull()

                if (info == null || !isValidRecord(info)) {
                    return@withContext Result.failure(Exception("Not found or invalid record"))
                }
                val baseUrl = getHttpBaseUrl()
                Result.success(mapToRecordedProgram(info, ip, baseUrl))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getRecordStreamUrl(
        videoId: Int, quality: String, sessionId: String, offsetSeconds: Double
    ): String {
        val baseUrl = getHttpBaseUrl()
        val playMethod = settingsRepository.edcbRecordPlayMethod.first()
        val optionIdStr = quality.toIntOrNull()?.toString() ?: "2"

        val resolverUrls = fetchResolverUrls(baseUrl, videoId)
        val videoPath = resolverUrls?.videoUrl

        if (playMethod == "DIRECT") {
            if (!videoPath.isNullOrEmpty()) {
                val safePath = if (videoPath.startsWith("/")) videoPath.substring(1) else videoPath
                val videoUri = "$baseUrl/$safePath"
                Log.i(TAG, "Generated HTTP Stream URL (DIRECT via Lua): $videoUri")
                return videoUri
            } else {
                try {
                    val (ip, port) = getTcpIpAndPort()
                    val info =
                        cachedRecInfos?.find { it.id == videoId } ?: EdcbApi(ip, port).getRecInfo(
                            videoId
                        ).getOrNull()

                    if (info != null && info.recFilePath.isNotBlank()) {
                        val fileName =
                            info.recFilePath.substringAfterLast("\\").substringAfterLast("/")
                        val encodedFileName =
                            URLEncoder.encode("video/rec/$fileName", "UTF-8").replace("+", "%20")
                        val ctokView = fetchResolverSettings(baseUrl)?.ctokView ?: ""

                        val builder = android.net.Uri.parse(baseUrl).buildUpon()
                            .appendPath("api").appendPath("xcode")
                            .appendQueryParameter("fname", encodedFileName)
                            .appendQueryParameter("option", "10")
                            .appendQueryParameter("ctok", ctokView)
                        if (offsetSeconds > 0) builder.appendQueryParameter(
                            "ofssec",
                            offsetSeconds.toInt().toString()
                        )
                        return builder.build().toString()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to generate DIRECT fallback URI", e)
                }
            }
        }

        if (!videoPath.isNullOrEmpty()) {
            val fnameRaw = videoPath.trimStart('/')
            val decodedFname = java.net.URLDecoder.decode(fnameRaw, "UTF-8")
            val ctokView = fetchResolverSettings(baseUrl)?.ctokView ?: ""

            val builder = android.net.Uri.parse(baseUrl).buildUpon()
                .appendPath("api").appendPath("xcode")
                .appendQueryParameter("fname", decodedFname)
                .appendQueryParameter("option", optionIdStr)
                .appendQueryParameter("ctok", ctokView)
            if (offsetSeconds > 0) builder.appendQueryParameter(
                "ofssec",
                offsetSeconds.toInt().toString()
            )
            return builder.build().toString()
        }

        val fallbackBuilder = android.net.Uri.parse(baseUrl).buildUpon()
            .appendPath("api").appendPath("xcode")
            .appendQueryParameter("id", videoId.toString())
            .appendQueryParameter("option", optionIdStr)
        if (offsetSeconds > 0) fallbackBuilder.appendQueryParameter(
            "ofssec",
            offsetSeconds.toInt().toString()
        )
        return fallbackBuilder.build().toString()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun mapToRecordedProgram(
        info: EdcbRecFileInfo,
        tcpIp: String,
        baseUrl: String
    ): RecordedProgram = withContext(Dispatchers.IO) {
        var isoStart = ""
        var isoEnd = ""
        if (!info.startTime.isNullOrBlank()) {
            try {
                val startDt = LocalDateTime.parse(
                    info.startTime,
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                )
                isoStart = startDt.atZone(ZoneId.of("Asia/Tokyo"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                isoEnd =
                    startDt.plusSeconds(info.durationSec.toLong()).atZone(ZoneId.of("Asia/Tokyo"))
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (e: Exception) {
            }
        }
        val isRecording = info.durationSec == 0 || info.recStatus == 0
        val channelId = "edcb_${info.onid}_${info.tsid}_${info.sid}"

        val detailStartIdx = info.programInfo.indexOf("詳細情報")
        val genreStartIdx =
            info.programInfo.indexOf("ジャンル").takeIf { it != -1 } ?: info.programInfo.length

        val cleanDescription = if (info.programInfo.isNotBlank()) {
            val endIdx = if (detailStartIdx != -1) detailStartIdx else genreStartIdx
            info.programInfo.substring(0, endIdx).trim().ifBlank { info.comment }
        } else info.comment

        val detailMap =
            mutableMapOf<String, String>("Error" to "${info.drops}", "Path" to info.recFilePath)
        if (detailStartIdx != -1 && genreStartIdx > detailStartIdx) {
            val extText = info.programInfo.substring(detailStartIdx + 4, genreStartIdx).trim()
            val parsedDetails = EdcbApi.parseProgramExtendedText(extText)
            if (parsedDetails.isNotEmpty() && parsedDetails.keys.any { it.isNotBlank() }) {
                detailMap.putAll(parsedDetails)
            } else if (extText.isNotBlank()) {
                detailMap["番組詳細"] = extText
            }
        }

        val fallbackUrl = "$baseUrl/api/Thumbnail?id=${info.id}"
        val resolverUrls = fetchResolverUrls(baseUrl, info.id)
        val primaryUrl =
            if (resolverUrls != null) "$baseUrl${resolverUrls.thumbnailUrl}" else fallbackUrl

        var cmSections: List<CmSection>? = null
        if (resolverUrls != null) {
            try {
                val urlsToTry = listOf(
                    "$baseUrl${resolverUrls.chapterUrl}",
                    "$baseUrl${resolverUrls.chapterAltUrl}"
                )
                val client =
                    baseEdcbHttpClient.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS)
                        .readTimeout(1500, TimeUnit.MILLISECONDS).build()
                var chapterText: String? = null
                for (urlStr in urlsToTry) {
                    try {
                        val request = Request.Builder().url(urlStr).build()
                        client.newCall(request).execute().use { response ->
                            // ステータスコードが 200 OK かつ ボディが空でないことを確認
                            if (response.isSuccessful && response.code == 200) {
                                val bytes = response.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val rawText = decodeEdcbString(bytes)
                                    // 取得したテキストがHTMLやエラーメッセージでないか簡易チェック
                                    if (!rawText.contains("Error 404") && !rawText.contains("<!DOCTYPE html>")) {
                                        chapterText = rawText
                                    }
                                }
                            }
                        }
                        if (chapterText != null) break
                    } catch (e: Exception) {
                        Log.w(TAG, "Chapter fetch failed for $urlStr", e)
                    }
                }
                if (chapterText != null) {
                    // Mapperを使用して変換
                    val parsed = EdcbDataMapper.parseChapterTextToCmSections(
                        chapterText,
                        info.durationSec.toDouble()
                    )
                    if (parsed.isNotEmpty()) cmSections = parsed
                }
            } catch (e: Exception) {
            }
        }

        var thumbnailInfo: ThumbnailInfo? = null
        if (resolverUrls != null) {
            try {
                val jsonUrlsToTry = listOf("$baseUrl${resolverUrls.tileJsonUrl}")
                val client =
                    baseEdcbHttpClient.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS)
                        .readTimeout(1500, TimeUnit.MILLISECONDS).build()
                var jsonText: String? = null
                for (urlStr in jsonUrlsToTry) {
                    try {
                        val request = Request.Builder().url(urlStr).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                if (bytes != null) jsonText = String(bytes, Charsets.UTF_8)
                            }
                        }
                        if (jsonText != null) break
                    } catch (e: Exception) {
                    }
                }
                if (jsonText != null) {
                    val jsonObj = JSONObject(jsonText)
                    val tileInfo = TileInfo(
                        imageWidth = jsonObj.optInt("image_width", 0),
                        imageHeight = jsonObj.optInt("image_height", 0),
                        tileWidth = jsonObj.optInt("tile_width", 320),
                        tileHeight = jsonObj.optInt("tile_height", 180),
                        columnCount = jsonObj.optInt("column_count", 1),
                        rowCount = jsonObj.optInt("row_count", 1),
                        intervalSec = jsonObj.optDouble("interval_sec", 10.0),
                        totalTiles = jsonObj.optInt("total_tiles", 1)
                    )
                    thumbnailInfo = ThumbnailInfo(version = 1, tile = tileInfo)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tile.json", e)
            }
        }

        val contentList = mutableListOf<EdcbContentData>()
        val dummyGenre = EdcbDataMapper.mapEdcbGenre(contentList)

        return@withContext RecordedProgram(
            info.id,
            info.title,
            null,
            false,
            cleanDescription,
            detailMap,
            isoStart,
            isoEnd,
            info.durationSec.toDouble(),
            info.drops > 0,
            RecordedChannel(
                channelId,
                info.onid,
                info.sid,
                channelId,
                cacheManager.getChannelType(info.onid),
                info.serviceName,
                String.format("%03d", info.sid % 1000)
            ),
            RecordedVideo(
                info.id,
                if (isRecording) "Recording" else "Recorded",
                "$baseUrl/legacy/view.lua?id=${info.id}",
                isoStart,
                isoEnd,
                info.durationSec.toDouble(),
                "mpegts",
                "mpeg2",
                "aac",
                true,
                thumbnailInfo,
                cmSections
            ),
            dummyGenre,
            isRecording,
            0.0,
            primaryUrl,
            fallbackUrl
        )
    }

    private fun decodeEdcbString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        try {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            return String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return try {
                String(bytes, charset("Shift_JIS"))
            } catch (ex: Exception) {
                String(bytes)
            }
        }
    }


    private fun getCommentColor(color: String): String? {
        if (color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) return color
        val map = mapOf(
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
        return map[color]
    }

    private fun getCommentPosition(pos: String): String? {
        val map = mapOf("ue" to "top", "naka" to "right", "shita" to "bottom")
        return map[pos]
    }

    private fun getCommentSize(size: String): String? {
        val map = mapOf("big" to "big", "medium" to "medium", "small" to "small")
        return map[size]
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getArchivedJikkyo(v: Int): Result<List<ArchivedComment>> =
        withContext(Dispatchers.IO) {
            try {
                val (ip, port) = getTcpIpAndPort()

                val info = cachedRecInfos?.find { it.id == v } ?: EdcbApi(ip, port).getRecInfo(v)
                    .getOrNull()
                if (info == null || info.startTime.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("録画情報が見つからないか、開始時刻が不正です。"))
                }

                val jikkyoId = liveRepository.getJikkyoId(info.onid, info.sid)
                    ?: return@withContext Result.failure(Exception("このチャンネルは実況(過去ログ)に対応していません。"))

                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                val startLdt = LocalDateTime.parse(info.startTime, formatter)
                val startUnix = startLdt.atZone(ZoneId.of("Asia/Tokyo")).toEpochSecond()
                val endUnix = startUnix + info.durationSec

                Log.i(TAG, "Fetching jikkyo past log for $jikkyoId ($startUnix ~ $endUnix)")

                val urlStr =
                    "https://jikkyo.tsukumijima.net/api/kakolog/$jikkyoId?starttime=$startUnix&endtime=$endUnix&format=json"

                val request = Request.Builder().url(urlStr).build()

                val client = baseEdcbHttpClient.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("NX-Jikkyo APIエラー: HTTP ${response.code}"))
                    }

                    val responseJson = response.body?.string() ?: ""
                    val jsonObject = JSONObject(responseJson)

                    if (jsonObject.has("error")) {
                        return@withContext Result.failure(
                            Exception(
                                "NX-Jikkyo APIエラー: ${
                                    jsonObject.getString(
                                        "error"
                                    )
                                }"
                            )
                        )
                    }

                    val packetArray = jsonObject.optJSONArray("packet") ?: org.json.JSONArray()
                    val comments = mutableListOf<ArchivedComment>()

                    for (i in 0 until packetArray.length()) {
                        val packet = packetArray.optJSONObject(i) ?: continue
                        val chat = packet.optJSONObject("chat") ?: continue

                        val content = chat.optString("content", "")
                        if (content.isBlank()) continue
                        if (chat.optString("deleted") == "1") continue

                        if (content.startsWith("/") && content.matches(Regex("^/[a-z][a-z0-9_-]*(?:\\s|$).*"))) {
                            if (chat.optString("premium") == "3") continue
                        }

                        val mail = chat.optString("mail", "")
                        var color = "#FFEAEA"
                        var position = "right"
                        var size = "medium"

                        val commands = mail.replace("184", "").split(" ")
                        for (cmd in commands) {
                            getCommentColor(cmd)?.let { color = it }
                            getCommentPosition(cmd)?.let { position = it }
                            getCommentSize(cmd)?.let { size = it }
                        }

                        val chatDate = chat.optDouble("date", 0.0)
                        val chatDateUsec = chat.optDouble("date_usec", 0.0)
                        val commentTime = (chatDate - startUnix) + (chatDateUsec / 1000000.0)

                        comments.add(
                            ArchivedComment(
                                time = commentTime,
                                type = position,
                                size = size,
                                color = color,
                                author = chat.optString("user_id", ""),
                                text = content
                            )
                        )
                    }

                    Log.i(TAG, "Successfully mapped ${comments.size} archived comments.")
                    return@withContext Result.success(comments)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch archived jikkyo", e)
                Result.failure(e)
            }
        }

    override suspend fun getTiledThumbnailUrl(videoId: Int): String? = withContext(Dispatchers.IO) {
        val baseUrl = getHttpBaseUrl()
        fetchResolverUrls(baseUrl, videoId)?.tileImageUrl?.let { "$baseUrl$it" }
    }

    override suspend fun searchRecordedPrograms(keyword: String, page: Int): RecordedApiResponse =
        RecordedApiResponse(0, emptyList())

    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
    }
}