package com.beeregg2001.komorebi.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.edcb.EdcbApi
import com.beeregg2001.komorebi.data.api.edcb.EdcbEventInfo
import com.beeregg2001.komorebi.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdcbRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider {

    companion object {
        private const val TAG = "EdcbRepository"
    }

    // ★ 追加: HTTPサーバー設定のキャッシュ
    private var httpPortCache: Int? = null
    private var enableHttpCache: Boolean? = null
    private var logoDataIniAttempted = false
    private val logoMutex = Mutex()
    private val failedLogoIds = mutableSetOf<String>()

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getChannels(): ChannelApiResponse = withContext(Dispatchers.Default) {
        val ip = settingsRepository.edcbIp.first()
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
        if (ip.isBlank()) throw Exception("IP not set")

        val edcbApi = EdcbApi(ip, port)

        val services = edcbApi.getServices().getOrNull() ?: emptyList()
        val events = edcbApi.getEventInfos(services).getOrNull() ?: emptyList()

        Log.i(TAG, "📡 EDCB Summary (Live): Services=${services.size}, Events=${events.size}")

        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

        val eventsByService = events.groupBy { "${it.onid}_${it.tsid}_${it.sid}" }

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

        fun EdcbEventInfo.toProgram(): Program {
            val isoStartTime = formatToIso(this.startTime)
            val isoEndTime = if (this.startTime != null && this.durationSec > 0) {
                try {
                    val startLdt = LocalDateTime.parse(this.startTime, formatter)
                    val endLdt = startLdt.plusSeconds(this.durationSec.toLong())
                    endLdt.atZone(ZoneId.of("Asia/Tokyo"))
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                } catch (e: Exception) {
                    ""
                }
            } else ""

            return Program(
                id = this.eid.toString(),
                title = this.eventName,
                description = this.eventText,
                detail = emptyMap(),
                startTime = isoStartTime,
                endTime = isoEndTime,
                duration = this.durationSec,
                genres = emptyList(),
                videoResolution = null
            )
        }

        val gr = mutableListOf<Channel>()
        val bs = mutableListOf<Channel>()
        val cs = mutableListOf<Channel>()
        val sky = mutableListOf<Channel>()
        val bs4k = mutableListOf<Channel>()

        services.forEach { svc ->
            if (svc.serviceType != 1 && svc.serviceType != 165) return@forEach
            val type = when {
                svc.onid == 4 -> "BS"
                svc.onid == 6 || svc.onid == 7 -> "CS"
                svc.onid == 10 -> "SKY"
                else -> "GR"
            }

            val key = "${svc.onid}_${svc.tsid}_${svc.sid}"
            val (presentEvent, followingEvent) = presentAndFollowingMap[key] ?: Pair(null, null)

            val channel = Channel(
                id = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}",
                displayChannelId = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}",
                name = svc.serviceName,
                channelNumber = if (svc.remoteControlKeyId > 0) svc.remoteControlKeyId.toString() else svc.sid.toString(),
                networkId = svc.onid.toLong(),
                serviceId = svc.sid.toLong(),
                transportStreamId = svc.tsid.toLong(),
                type = type,
                isWatchable = true,
                isDisplay = true,
                programPresent = presentEvent?.toProgram(),
                programFollowing = followingEvent?.toProgram(),
                remocon_Id = svc.remoteControlKeyId,
                jikkyoForce = 0
            )

            when (type) {
                "GR" -> gr.add(channel)
                "BS" -> bs.add(channel)
                "CS" -> cs.add(channel)
                "SKY" -> sky.add(channel)
                else -> if (svc.serviceType == 165) bs4k.add(channel)
            }
        }
        return@withContext ChannelApiResponse(
            // 数値に変換してソート（数値にできない場合は一番後ろ 9999 に回す）
            terrestrial = gr.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            bs = bs.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            cs = cs.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            sky = sky.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 },
            bs4k = bs4k.sortedBy { it.channelNumber.toIntOrNull() ?: 9999 }
        )
    }

    override suspend fun getChannelLogoUrl(channelId: String): String = withContext(Dispatchers.IO) {
        if (failedLogoIds.contains(channelId)) return@withContext ""

        val parts = channelId.split("_")
        if (parts.size < 4 || parts[0] != "edcb") return@withContext ""

        val onid = parts[1].toIntOrNull() ?: return@withContext ""
        val tsid = parts[2].toIntOrNull() ?: return@withContext ""
        val sid = parts[3].toIntOrNull() ?: return@withContext ""

        val ip = settingsRepository.edcbIp.first()
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
        if (ip.isBlank()) return@withContext ""

        val edcbApi = EdcbApi(ip, port)

        logoMutex.withLock {
            if (!logoDataIniAttempted) {
                logoDataIniAttempted = true
                httpPortCache = 5510
                enableHttpCache = true

                // EDCBのTCPサーバーから EpgTimerSrv.ini を引っこ抜き、HTTPサーバーのポートを調べる
                val srvIni = edcbApi.fetchFiles(listOf("EpgTimerSrv.ini"))
                    ?.firstOrNull { it.data.isNotEmpty() }
                if (srvIni != null) {
                    val iniText = decodeEdcbString(srvIni.data)
                    val portMatch = Regex("HttpPort\\s*=\\s*(\\d+)").find(iniText)
                    if (portMatch != null) {
                        httpPortCache = portMatch.groupValues[1].toInt()
                    }
                    val enableMatch = Regex("EnableHttpSrv\\s*=\\s*(\\d+)").find(iniText)
                    if (enableMatch != null) {
                        enableHttpCache = enableMatch.groupValues[1] != "0"
                    }
                }
                Log.i(
                    TAG,
                    "✅ [EdcbLogo] EDCB HTTP Server Config -> Enabled: $enableHttpCache, Port: $httpPortCache"
                )
            }
        }

        // ★ 究極の解決策: EDCBネイティブの logo.lua (HTTP API) に全てを丸投げする！
        // これなら Wine の大文字小文字問題もパス区切り文字問題も EDCB側がすべて吸収してくれます。
        if (enableHttpCache == true) {
            val logoUrl = "http://$ip:$httpPortCache/legacy/logo.lua?onid=$onid&sid=$sid"
            Log.i(TAG, "🎯 [EdcbLogo] Providing HTTP URL to Image Loader: $logoUrl")
            return@withContext logoUrl
        }

        failedLogoIds.add(channelId)
        Log.w(TAG, "❌ [EdcbLogo] HTTP server is disabled. Cannot retrieve logo.")
        return@withContext ""
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun formatToIso(edcbTime: String?): String {
        if (edcbTime.isNullOrBlank()) return ""
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            val localDateTime = LocalDateTime.parse(edcbTime, formatter)
            val zonedDateTime = localDateTime.atZone(ZoneId.of("Asia/Tokyo"))
            zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            ""
        }
    }

    // --- 未実装メソッドのスタブ ---
    override suspend fun getLiveStreamUrl(channelId: String, quality: String): String = ""
    override suspend fun getRecordedPrograms(page: Int): RecordedApiResponse =
        RecordedApiResponse(0, emptyList())

    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> =
        Result.failure(Exception("Not implemented"))

    override suspend fun searchRecordedPrograms(keyword: String, page: Int): RecordedApiResponse =
        RecordedApiResponse(0, emptyList())

    override suspend fun getRecordStreamUrl(v: Int, q: String, s: String): String = ""
    override suspend fun getArchivedJikkyo(v: Int): Result<List<ArchivedComment>> =
        Result.success(emptyList())

    @androidx.annotation.OptIn(UnstableApi::class)
    override suspend fun keepAlive(v: Int, q: String, s: String) {
    }

    override suspend fun getReserves(): Result<List<ReserveItem>> = Result.success(emptyList())
    override suspend fun addReserve(r: ReserveRequest): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun updateReserve(i: Int, r: ReserveRequest): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun deleteReservation(i: Int): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> =
        Result.success(emptyList())

    override suspend fun addReservationCondition(r: ReservationConditionAddRequest): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun updateReservationCondition(
        i: Int,
        r: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> = Result.failure(Exception("Not implemented"))

    override suspend fun deleteReservationCondition(i: Int): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun getEpgPrograms(
        s: String?,
        e: String?,
        t: String?
    ): List<EpgChannelWrapper> = emptyList()

    override suspend fun getPinnedEpgPrograms(p: String): List<EpgChannelWrapper> = emptyList()
}