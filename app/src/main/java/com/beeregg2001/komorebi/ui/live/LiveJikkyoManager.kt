package com.beeregg2001.komorebi.ui.live

import android.content.Context
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.data.model.BackendConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

data class LiveComment(
    val text: String,
    val color: String,
    val position: String,
    val size: String
)

/**
 * ライブ視聴中の実況コメント（NX-Jikkyo / KonomiTV）の接続、取得、パースを管理するマネージャークラスです。
 */
@Singleton
class LiveJikkyoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private val JIKKYO_CHANNEL_ID_MAP = mapOf(
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

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _liveComments = MutableSharedFlow<LiveComment>(extraBufferCapacity = 100)
    val liveComments: SharedFlow<LiveComment> = _liveComments.asSharedFlow()

    private val _clearCommentsEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearCommentsEvent: SharedFlow<Unit> = _clearCommentsEvent.asSharedFlow()

    private var jikkyoClient: JikkyoClient? = null
    private val processedCommentIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private var jikkyoChannelsCache: JSONArray? = null

    fun startJikkyo(channel: Channel, source: StreamSource) {
        stopJikkyo()

        managerScope.launch(Dispatchers.IO) {
            _clearCommentsEvent.emit(Unit)
            val watchUrl = getJikkyoWatchSessionUrl(channel, source)
            if (watchUrl.isNullOrEmpty()) return@launch

            jikkyoClient = JikkyoClient(watchUrl)
            jikkyoClient?.start { jsonText ->
                parseAndEmitComment(jsonText)
            }
        }
    }

    fun stopJikkyo() {
        jikkyoClient?.stop()
        jikkyoClient = null
        processedCommentIds.clear()
    }

    private suspend fun getJikkyoWatchSessionUrl(channel: Channel, source: StreamSource): String? {
        if (source == StreamSource.KONOMITV) {
            val config = settingsRepository.getBackendConfig(source) as? BackendConfig.KonomiTv
            if (config == null) return null
            try {
                val apiUrl =
                    "${config.ip}:${config.port}/api/channels/${channel.displayChannelId}/jikkyo"
                val request = Request.Builder().url(apiUrl).build()
                val response = okHttpClient.newCall(request).execute()

                val bodyString = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    val json = JSONObject(bodyString)
                    return json.optString("watch_session_url").takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return null
        } else {
            val networkId = channel.networkId.toInt()
            val serviceId = channel.serviceId.toInt()

            val jkId = getJikkyoId(networkId, serviceId)
            if (jkId != null) {
                return "wss://nx-jikkyo.tsukumijima.net/api/v1/channels/$jkId/ws/watch"
            }
            return null
        }
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

    private fun getJikkyoId(networkId: Int, serviceId: Int): String? {
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

    private fun parseAndEmitComment(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val chat = json.optJSONObject("chat") ?: return
            val content = chat.optString("content", "")
            if (content.isBlank()) return
            if (chat.optString("deleted") == "1") return

            // /から始まるコマンドは除外
            if (content.startsWith("/") && content.matches(Regex("^/[a-z][a-z0-9_-]*(?:\\s|$).*"))) {
                if (chat.optString("premium") == "3") return
            }

            val commentId = chat.optString("no", "") + "_" + content
            if (!processedCommentIds.add(commentId)) return
            if (processedCommentIds.size > 2000) processedCommentIds.clear()

            var color = "#FFEAEA"
            var position = "right"
            var size = "medium"
            val mail = chat.optString("mail", "")
            val commands = mail.replace("184", "").split(" ")
            for (cmd in commands) {
                getCommentColor(cmd)?.let { color = it }
                getCommentPosition(cmd)?.let { position = it }
                getCommentSize(cmd)?.let { size = it }
            }

            managerScope.launch(Dispatchers.Main) {
                _liveComments.emit(LiveComment(content, color, position, size))
            }

        } catch (e: Exception) {
            // Ignore parsing errors
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
}