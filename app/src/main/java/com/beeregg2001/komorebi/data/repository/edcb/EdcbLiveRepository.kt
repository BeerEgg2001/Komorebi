package com.beeregg2001.komorebi.data.repository.edcb

import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import android.os.Build
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.edcb.EdcbEventInfo
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
import com.beeregg2001.komorebi.data.repository.LiveProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdcbLiveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val cacheManager: EdcbEpgCacheManager
) : LiveProvider {

    companion object {
        private const val TAG = "EdcbLiveRepository"

        // 実況対応チャンネルIDのマップ
        val JIKKYO_CHANNEL_ID_MAP = mapOf(
            "jk1" to "ch2646436", "jk2" to "ch2646437", "jk4" to "ch2646438",
            "jk5" to "ch2646439", "jk6" to "ch2646440", "jk7" to "ch2646441",
            "jk8" to "ch2646442", "jk9" to "ch2646485", "jk10" to null,
            "jk11" to null, "jk12" to null, "jk13" to null, "jk14" to null,
            "jk101" to "ch2647992", "jk103" to null, "jk141" to null,
            "jk151" to null, "jk161" to null, "jk171" to null, "jk181" to null,
            "jk191" to null, "jk192" to null, "jk193" to null, "jk200" to null,
            "jk201" to null, "jk211" to "ch2646846", "jk222" to null,
            "jk236" to null, "jk252" to null, "jk260" to null, "jk263" to null,
            "jk265" to null, "jk333" to null
        )
    }

    private val baseEdcbHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().apply {
            interceptors().clear()
        }.build()
    }

    private val failedLogoIds = mutableSetOf<String>()
    private var jikkyoChannelsCache: JSONArray? = null

    private suspend fun getHttpBaseUrl(): String {
        return settingsRepository.getEdcbFullUrl()
    }

    // -----------------------------------------------------------------------------------------
    // resolver.lua から指定した用途(xcode または view)の ctok を取得するメソッド
    // エラーレスポンスを受け取った場合はExceptionを投げ、UI側(ダイアログ)で表示させる
    // -----------------------------------------------------------------------------------------
    private suspend fun fetchResolverCtok(baseUrl: String, purpose: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/komorebi/resolver.lua"
                val request = Request.Builder().url(url).build()
                baseEdcbHttpClient.newCall(request).execute().use { response ->
                    val jsonStr = response.body?.string() ?: return@withContext null

                    // JSONをパース
                    val json = JSONObject(jsonStr)

                    // ★ 新規処理: resolver.luaからのエラー(フェールセーフ含む)をキャッチ
                    if (json.has("error")) {
                        val errMsg = json.optString("error", "Unknown Resolver Error")
                        val errDetail = json.optString("detail", "")
                        val fullMsg = if (errDetail.isNotBlank()) "$errMsg\n$errDetail" else errMsg
                        Log.e(TAG, "Resolver Lua Error: $fullMsg")
                        // 例外をスローして上位へ伝搬させる
                        throw Exception("Komorebi Resolver エラー:\n$fullMsg")
                    }

                    // HTTPステータスが200以外で、かつJSONにerrorが含まれていない場合のフェールセーフ
                    if (!response.isSuccessful) {
                        throw Exception("HTTP Error ${response.code}\nresolver.lua へのアクセスに失敗しました。")
                    }

                    return@withContext json.optJSONObject("ctok")?.optString(purpose)
                }
            } catch (e: Exception) {
                // 既存の処理: 再スローして、上位のViewModel側でキャッチさせる
                Log.e(TAG, "Failed to fetch ctok for purpose=$purpose", e)
                throw e
            }
        }

    private fun fetchNxJikkyoForce(): Map<String, Int> {
        val forceMap = mutableMapOf<String, Int>()
        try {
            val request = Request.Builder()
                .url("https://nx-jikkyo.tsukumijima.net/api/v1/channels")
                .build()

            val client = baseEdcbHttpClient.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseJson = response.body?.string() ?: return forceMap
                    val jsonArray = JSONArray(responseJson)
                    for (i in 0 until jsonArray.length()) {
                        val channelObj = jsonArray.optJSONObject(i) ?: continue
                        val jkId = channelObj.optString("id", "")
                        if (jkId.isBlank()) continue

                        val threads = channelObj.optJSONArray("threads") ?: continue
                        var maxForce = 0
                        for (j in 0 until threads.length()) {
                            val thread = threads.optJSONObject(j) ?: continue
                            val force = thread.optInt("jikkyo_force", 0)
                            if (force > maxForce) maxForce = force
                        }
                        forceMap[jkId] = maxForce
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch NX-Jikkyo force (Timeout or Network error)")
        }
        return forceMap
    }

    private fun getJikkyoChannels(): JSONArray {
        if (jikkyoChannelsCache != null) return jikkyoChannelsCache!!
        return try {
            val jsonString =
                context.assets.open("jikkyo_channels.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonString)
            jikkyoChannelsCache = array
            array
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun getJikkyoId(networkId: Int, serviceId: Int): String? {
        val channels = getJikkyoChannels()
        for (i in 0 until channels.length()) {
            val jc = channels.optJSONObject(i) ?: continue
            val jcNid = jc.optInt("network_id", -1)

            val sidRaw = jc.opt("service_id")?.toString() ?: "-1"
            val jcSid = if (sidRaw.startsWith("0x", ignoreCase = true)) {
                sidRaw.substring(2).toIntOrNull(16) ?: -1
            } else {
                sidRaw.toIntOrNull() ?: -1
            }
            val jkJikkyoId = jc.optInt("jikkyo_id", -1)

            var matched = false
            if (networkId == jcNid && serviceId == jcSid) {
                matched = true
            } else if (networkId in 0x7880..0x7FEF && jcNid == 15) {
                if (serviceId == jcSid || serviceId - 1 == jcSid || serviceId - 2 == jcSid) {
                    matched = true
                }
            }

            if (matched && jkJikkyoId != -1) {
                val jkId = "jk$jkJikkyoId"
                if (JIKKYO_CHANNEL_ID_MAP.containsKey(jkId)) {
                    return jkId
                }
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getChannels(): ChannelApiResponse = withContext(Dispatchers.Default) {
        val forceJob = async(Dispatchers.IO) { fetchNxJikkyoForce() }

        // キャッシュマネージャー経由でEPGデータを取得
        cacheManager.fetchEpgDataIfNeeded()

        val forceMap = forceJob.await()

        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
        val eventsByService =
            cacheManager.cachedEvents.groupBy { "${it.onid}_${it.tsid}_${it.sid}" }

        val presentAndFollowingMap = eventsByService.mapValues { (_, svcEvents) ->
            val sortedEvents = svcEvents.mapNotNull { ev ->
                if (ev.startTime == null) return@mapNotNull null
                try {
                    val start = LocalDateTime.parse(ev.startTime, formatter)
                    val end = start.plusSeconds(ev.durationSec.toLong())
                    Triple(ev, start, end)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.second }

            var present: EdcbEventInfo? = null
            var following: EdcbEventInfo? = null

            for (i in sortedEvents.indices) {
                val (ev, start, end) = sortedEvents[i]
                if (now.isAfter(start) && now.isBefore(end)) {
                    present = ev
                    if (i + 1 < sortedEvents.size) following = sortedEvents[i + 1].first
                    break
                } else if (now.isBefore(start) && present == null) {
                    following = ev
                    break
                }
            }
            Pair(present, following)
        }

        val gr = mutableListOf<Channel>()
        val bs = mutableListOf<Channel>()
        val cs = mutableListOf<Channel>()
        val sky = mutableListOf<Channel>()
        val bs4k = mutableListOf<Channel>()

        cacheManager.cachedServices.forEach { svc ->
            val type = cacheManager.getChannelType(svc.onid)
            val key = "${svc.onid}_${svc.tsid}_${svc.sid}"
            val channelId = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}"

            val (presentEvent, followingEvent) = presentAndFollowingMap[key] ?: Pair(null, null)

            val isSub = cacheManager.isSubChannel(type, svc.sid, svc.tsid)

            val jkId = getJikkyoId(svc.onid, svc.sid)
            val jikkyoForce = if (jkId != null) forceMap[jkId] ?: 0 else 0

            val channel = Channel(
                id = channelId,
                displayChannelId = channelId,
                name = svc.serviceName, // ※ 自動補正なし
                channelNumber = cacheManager.formatChannelNumber(
                    type,
                    svc.remoteControlKeyId,
                    svc.sid,
                    svc.tsid
                ),
                networkId = svc.onid.toLong(),
                serviceId = svc.sid.toLong(),
                transportStreamId = svc.tsid.toLong(),
                type = type,
                isWatchable = true,
                isDisplay = true,
                is_subchannel = isSub,
                // Mapperに委譲
                programPresent = presentEvent?.let { EdcbDataMapper.toProgram(it, channelId) },
                programFollowing = followingEvent?.let { EdcbDataMapper.toProgram(it, channelId) },
                remocon_Id = svc.remoteControlKeyId,
                jikkyoForce = jikkyoForce
            )

            when (type) {
                "GR" -> gr.add(channel)
                "BS" -> bs.add(channel)
                "CS" -> cs.add(channel)
                "SKY" -> sky.add(channel)
                "BS4K" -> bs4k.add(channel)
            }
        }

        return@withContext ChannelApiResponse(
            terrestrial = gr.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            bs = bs.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            cs = cs.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            sky = sky.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            bs4k = bs4k.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 }
        )
    }

    override suspend fun getLiveStreamUrl(
        channelId: String,
        quality: String,
        streamNumber: Int
    ): String =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = getHttpBaseUrl()
                if (baseUrl.isBlank()) return@withContext ""

                val parts = channelId.split("_")
                if (parts.size < 4 || parts[0] != "edcb") {
                    Log.e(TAG, "Invalid channelId format: $channelId")
                    return@withContext ""
                }
                val onid = parts[1]
                val tsid = parts[2]
                val sid = parts[3]
                val edcbId = "$onid-$tsid-$sid"

                val ctokView = fetchResolverCtok(baseUrl, "view") ?: ""
                Log.i(TAG, "[Live/Dual] 0. Main Ctok (view): $ctokView")

                val tvCastUrl =
                    "$baseUrl/api/TvCast?id=$edcbId&n=$streamNumber&json=1&ctok=$ctokView"
                val tvCastRequest = Request.Builder().url(tvCastUrl).get().build()

                Log.i(TAG, "[Live/Dual] 1. TvCast リクエスト(n=$streamNumber) 送信中...")
                baseEdcbHttpClient.newCall(tvCastRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "TvCastによるチューナーの起動に失敗しました。")
                        return@withContext ""
                    }
                }

                val ctokXcode = fetchResolverCtok(baseUrl, "xcode") ?: ctokView
                val hlsKey = "komorebi_live_${streamNumber}_${System.currentTimeMillis()}"

                val postUrl =
                    "$baseUrl/api/view?n=$streamNumber&id=$edcbId&option=$quality&hls=$hlsKey&ctok=$ctokXcode"
                val formBody = okhttp3.FormBody.Builder()
                    .add("ctok", ctokXcode)
                    .add("open", "1")
                    .build()

                val postRequest = Request.Builder()
                    .url(postUrl)
                    .post(formBody)
                    .header("Cookie", "ctok=$ctokXcode")
                    .build()

                Log.i(
                    TAG,
                    "[Live/Dual] 2. HLSトランスコード開始(n=$streamNumber) (option=$quality)"
                )
                baseEdcbHttpClient.newCall(postRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "トランスコードの開始に失敗しました。")
                        return@withContext ""
                    }
                }

                Log.i(TAG, "[Live/Dual] セグメント生成待ち...")
                kotlinx.coroutines.delay(4000)

                val m3u8Url =
                    "$baseUrl/api/view?n=$streamNumber&id=$edcbId&option=$quality&hls=$hlsKey&ctok=$ctokXcode"
                Log.i(TAG, "[Live/Dual] 3. 生成された m3u8 プレイリストURL: $m3u8Url")

                return@withContext m3u8Url

            } catch (e: Exception) {
                // 既存の処理: そのまま再スローし、ViewModel側でエラーダイアログを表示させる
                Log.e(TAG, "Failed to start live streaming", e)
                throw e
            }
        }

    override suspend fun getChannelLogoUrl(channelId: String): String =
        withContext(Dispatchers.IO) {
            if (failedLogoIds.contains(channelId)) return@withContext ""

            val logoDir = java.io.File(context.cacheDir, "channel_logos")
            if (!logoDir.exists()) logoDir.mkdirs()

            val cachedFile = java.io.File(logoDir, "$channelId.img")
            if (cachedFile.exists() && cachedFile.length() > 0) {
                return@withContext android.net.Uri.fromFile(cachedFile).toString()
            }

            val parts = channelId.split("_")
            if (parts.size < 4 || parts[0] != "edcb") return@withContext ""

            val onid = parts[1].toIntOrNull() ?: return@withContext ""
            val tsid = parts[2].toIntOrNull() ?: return@withContext ""
            val sid = parts[3].toIntOrNull() ?: return@withContext ""

            val baseUrl = getHttpBaseUrl()
            if (baseUrl.isBlank()) return@withContext ""

            val targetUrl = "$baseUrl/legacy/logo.lua?onid=$onid&sid=$sid"

            try {
                val request = Request.Builder().url(targetUrl).build()

                val client = baseEdcbHttpClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { input ->
                            java.io.FileOutputStream(cachedFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (cachedFile.length() > 0) {
                            return@withContext android.net.Uri.fromFile(cachedFile).toString()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download and cache logo for $channelId", e)
            }

            failedLogoIds.add(channelId)
            return@withContext ""
        }
}