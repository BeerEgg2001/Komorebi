package com.beeregg2001.komorebi.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.edcb.*
import com.beeregg2001.komorebi.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        private const val CACHE_EXPIRATION_MS = 15 * 60 * 1000L
    }

    private var httpPortCache: Int? = null
    private var enableHttpCache: Boolean? = null
    private var logoDataIniAttempted = false
    private val logoMutex = Mutex()
    private val failedLogoIds = mutableSetOf<String>()

    private val epgMutex = Mutex()
    private var cachedServices: List<EdcbServiceInfo> = emptyList()
    private var cachedEvents: List<EdcbEventInfo> = emptyList()
    private var lastEpgFetchTime = 0L

    private var tsidToSidsMap: Map<Int, List<Int>> = emptyMap()

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun fetchEpgDataIfNeeded() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedServices.isEmpty() || cachedEvents.isEmpty() || (now - lastEpgFetchTime) > CACHE_EXPIRATION_MS) {
            epgMutex.withLock {
                if (cachedServices.isEmpty() || cachedEvents.isEmpty() || (System.currentTimeMillis() - lastEpgFetchTime) > CACHE_EXPIRATION_MS) {
                    Log.i(TAG, "🔄 Fetching fresh EPG data from EDCB...")
                    val ip = settingsRepository.edcbIp.first()
                    val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
                    if (ip.isBlank()) throw Exception("EDCB IP is not set")

                    val edcbApi = EdcbApi(ip, port)
                    val services = edcbApi.getServices().getOrNull() ?: emptyList()
                    val targetServices =
                        services.filter { it.serviceType == 1 || it.serviceType == 165 }
                    val events = edcbApi.getEventInfos(targetServices).getOrNull() ?: emptyList()

                    cachedServices = targetServices
                    cachedEvents = events
                    lastEpgFetchTime = System.currentTimeMillis()

                    tsidToSidsMap = targetServices
                        .filter { getChannelType(it.onid) == "GR" }
                        .groupBy { it.tsid }
                        .mapValues { (_, svcs) -> svcs.map { it.sid }.sorted() }

                    Log.i(
                        TAG,
                        "✅ EPG Cache updated! Services=${cachedServices.size}, Events=${cachedEvents.size}"
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getChannels(): ChannelApiResponse = withContext(Dispatchers.Default) {
        fetchEpgDataIfNeeded()

        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
        val eventsByService = cachedEvents.groupBy { "${it.onid}_${it.tsid}_${it.sid}" }

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

        cachedServices.forEach { svc ->
            val type = getChannelType(svc.onid)
            val key = "${svc.onid}_${svc.tsid}_${svc.sid}"
            val (presentEvent, followingEvent) = presentAndFollowingMap[key] ?: Pair(null, null)

            val channel = Channel(
                id = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}",
                displayChannelId = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}",
                name = svc.serviceName,
                channelNumber = formatChannelNumber(
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
                programPresent = presentEvent?.toProgram("edcb_${svc.onid}_${svc.tsid}_${svc.sid}"),
                programFollowing = followingEvent?.toProgram("edcb_${svc.onid}_${svc.tsid}_${svc.sid}"),
                remocon_Id = svc.remoteControlKeyId,
                jikkyoForce = 0
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

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ): List<EpgChannelWrapper> = withContext(Dispatchers.Default) {

        fetchEpgDataIfNeeded()

        val filteredServices = if (channelType != null) {
            cachedServices.filter { getChannelType(it.onid) == channelType }
        } else {
            cachedServices
        }

        val eventsByService = cachedEvents.groupBy { "${it.onid}_${it.tsid}_${it.sid}" }

        val wrappers = filteredServices.map { svc ->
            val type = getChannelType(svc.onid)
            val channelId = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}"

            val isSubChannel = if (type == "GR") {
                val sidsInTs = tsidToSidsMap[svc.tsid]
                sidsInTs != null && sidsInTs.isNotEmpty() && sidsInTs[0] != svc.sid
            } else {
                false
            }

            val epgChannel = EpgChannel(
                id = channelId,
                display_channel_id = channelId,
                network_id = svc.onid,
                service_id = svc.sid,
                transport_stream_id = svc.tsid,
                remocon_id = svc.remoteControlKeyId,
                channel_number = formatChannelNumber(
                    type,
                    svc.remoteControlKeyId,
                    svc.sid,
                    svc.tsid
                ),
                type = type,
                name = svc.serviceName,
                jikkyo_force = 0,
                is_subchannel = isSubChannel,
                is_radiochannel = false,
                is_watchable = true
            )

            val svcEvents = eventsByService["${svc.onid}_${svc.tsid}_${svc.sid}"] ?: emptyList()
            val epgPrograms = svcEvents.mapNotNull { ev ->
                ev.toEpgProgram(channelId, svc.onid, svc.sid)
            }

            EpgChannelWrapper(
                channel = epgChannel,
                programs = epgPrograms.sortedBy { it.start_time }
            )
        }

        return@withContext wrappers.sortedBy { it.channel.channel_number.toIntOrNull() ?: 9999 }
    }

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        return emptyList()
    }

    // ==========================================
    // ヘルパーメソッド・拡張関数
    // ==========================================

    private fun getChannelType(onid: Int): String {
        return when {
            onid == 4 -> "BS"
            onid == 6 || onid == 7 -> "CS"
            onid == 10 -> "SKY"
            onid in 0x7880..0x7FE8 -> "GR"
            else -> "UNKNOWN"
        }
    }

    private fun formatChannelNumber(
        type: String,
        remoconId: Int,
        serviceId: Int,
        tsid: Int
    ): String {
        return if (type == "GR") {
            if (remoconId in 1..12) {
                val sidsInTs = tsidToSidsMap[tsid]
                val index = sidsInTs?.indexOf(serviceId) ?: 0
                val branchNum = (index + 1).coerceIn(1, 8)
                String.format("%03d", remoconId * 10 + branchNum)
            } else {
                String.format("%03d", serviceId % 1000)
            }
        } else {
            String.format("%03d", serviceId)
        }
    }

    // ★ EdcbConstants を参照するように変更
    private fun mapEdcbGenre(contentList: List<EdcbContentData>?): List<EpgGenre> {
        if (contentList.isNullOrEmpty()) return emptyList()
        return contentList.mapNotNull { content ->
            val majorNibble = content.contentNibble shr 8
            val middleNibble = content.contentNibble and 0x0F

            val genreTuple = EdcbConstants.CONTENT_TYPE[majorNibble]
            if (genreTuple != null) {
                var major = genreTuple.first
                var middle = genreTuple.second[middleNibble] ?: "未定義"

                // 拡張情報の処理
                if (major == "拡張") {
                    if (middle == "BS/地上デジタル放送用番組付属情報") {
                        val userNibble =
                            (content.userNibble shr 8 shl 4) or (content.userNibble and 0x0F)
                        middle = EdcbConstants.USER_TYPE[userNibble] ?: "未定義"
                    } else {
                        return@mapNotNull null // 拡張はあるが不明なものはスキップ
                    }
                }

                EpgGenre(major = major, middle = middle)
            } else {
                null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun EdcbEventInfo.toProgram(channelId: String): Program {
        val isoStartTime = formatToIso(this.startTime)
        val isoEndTime = if (this.startTime != null && this.durationSec > 0) {
            try {
                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                val startLdt = LocalDateTime.parse(this.startTime, formatter)
                val endLdt = startLdt.plusSeconds(this.durationSec.toLong())
                endLdt.atZone(ZoneId.of("Asia/Tokyo"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (e: Exception) {
                ""
            }
        } else ""

        val mappedGenres =
            mapEdcbGenre(this.contentList).map { Genre(major = it.major, middle = it.middle) }

        return Program(
            id = channelId + "_${this.eid}",
            title = this.eventName,
            description = this.eventText,
            detail = this.detailMap,
            startTime = isoStartTime,
            endTime = isoEndTime,
            duration = this.durationSec,
            genres = mappedGenres,
            videoResolution = null
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun EdcbEventInfo.toEpgProgram(
        channelId: String,
        networkId: Int,
        serviceId: Int
    ): EpgProgram? {
        val isoStartTime = formatToIso(this.startTime)
        if (isoStartTime.isEmpty()) return null

        val isoEndTime = if (this.durationSec > 0) {
            try {
                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                val startLdt = LocalDateTime.parse(this.startTime, formatter)
                val endLdt = startLdt.plusSeconds(this.durationSec.toLong())
                endLdt.atZone(ZoneId.of("Asia/Tokyo"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (e: Exception) {
                ""
            }
        } else ""

        return EpgProgram(
            id = channelId + "_${this.eid}",
            channel_id = channelId,
            network_id = networkId,
            service_id = serviceId,
            event_id = this.eid,
            title = this.eventName,
            description = this.eventText,
            extended = this.extendedText,
            detail = this.detailMap,
            start_time = isoStartTime,
            end_time = isoEndTime,
            duration = this.durationSec,
            is_free = this.freeCaFlag == 0,
            genres = mapEdcbGenre(this.contentList),
            video_type = "mpeg2",
            audio_type = "2/0",
            audio_sampling_rate = "48000"
        )
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


    // ==========================================
    // ロゴ取得 (変更なし)
    // ==========================================
    override suspend fun getChannelLogoUrl(channelId: String): String =
        withContext(Dispatchers.IO) {
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
                }
            }

            if (enableHttpCache == true) {
                return@withContext "http://$ip:$httpPortCache/legacy/logo.lua?onid=$onid&sid=$sid"
            }

            failedLogoIds.add(channelId)
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
}