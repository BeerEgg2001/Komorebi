package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.jikkyo.JikkyoChannelResolver
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.data.model.BackendConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val jikkyoChannelResolver: JikkyoChannelResolver
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _liveComments = MutableSharedFlow<LiveComment>(extraBufferCapacity = 100)
    val liveComments: SharedFlow<LiveComment> = _liveComments.asSharedFlow()

    private val _clearCommentsEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearCommentsEvent: SharedFlow<Unit> = _clearCommentsEvent.asSharedFlow()

    private var jikkyoClient: JikkyoClient? = null
    private val processedCommentIds = Collections.synchronizedSet(LinkedHashSet<String>())

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

            val jkId = jikkyoChannelResolver.getJikkyoId(networkId, serviceId)
            if (jkId != null) {
                return "wss://nx-jikkyo.tsukumijima.net/api/v1/channels/$jkId/ws/watch"
            }
            return null
        }
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