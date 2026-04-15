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
        private const val MAX_ALLOWED_DROPS = 1000L
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
    private var bsPrefixToSidsMap: Map<Int, List<Int>> = emptyMap()

    private val recordMutex = Mutex()
    private var cachedRecInfos: List<EdcbRecFileInfo>? = null
    private var lastRecFetchTime = 0L

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

                    bsPrefixToSidsMap = targetServices
                        .filter { getChannelType(it.onid) == "BS" }
                        .groupBy { it.sid / 10 }
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

            val isSub = isSubChannelInternal(type, svc.sid, svc.tsid)

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
                is_subchannel = isSub,
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

            val isSubChannel = isSubChannelInternal(type, svc.sid, svc.tsid)

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

    private fun isSubChannelInternal(type: String, sid: Int, tsid: Int): Boolean {
        return when (type) {
            "GR" -> {
                val sidsInTs = tsidToSidsMap[tsid]
                sidsInTs != null && sidsInTs.isNotEmpty() && sidsInTs[0] != sid
            }

            "BS" -> {
                if (sid in 101..189) {
                    val prefix = sid / 10
                    val sidsForPrefix = bsPrefixToSidsMap[prefix]
                    sidsForPrefix != null && sidsForPrefix.isNotEmpty() && sidsForPrefix[0] != sid
                } else {
                    false
                }
            }

            else -> false
        }
    }

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        return emptyList()
    }

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

    private fun mapEdcbGenre(contentList: List<EdcbContentData>?): List<EpgGenre> {
        if (contentList.isNullOrEmpty()) return emptyList()
        return contentList.mapNotNull { content ->
            val majorNibble = content.contentNibble shr 8
            val middleNibble = content.contentNibble and 0x0F

            val genreTuple = EdcbConstants.CONTENT_TYPE[majorNibble]
            if (genreTuple != null) {
                var major = genreTuple.first
                var middle = genreTuple.second[middleNibble] ?: "未定義"

                if (major == "拡張") {
                    if (middle == "BS/地上デジタル放送用番組付属情報") {
                        val userNibble =
                            (content.userNibble shr 8 shl 4) or (content.userNibble and 0x0F)
                        middle = EdcbConstants.USER_TYPE[userNibble] ?: "未定義"
                    } else {
                        return@mapNotNull null
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

    override suspend fun getLiveStreamUrl(channelId: String, quality: String): String = ""

    private fun extractGenresFromProgramInfo(programInfo: String): List<EpgGenre> {
        if (programInfo.isBlank()) return emptyList()
        val lines = programInfo.lines()

        val genreIndex = lines.indexOfFirst { it.trim().startsWith("ジャンル") && it.contains(":") }
        if (genreIndex != -1 && genreIndex + 1 < lines.size) {
            val genreList = mutableListOf<EpgGenre>()

            for (i in genreIndex + 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) break

                val parts = line.split("　", "\t")

                for (part in parts) {
                    val p = part.trim()
                    if (p.isEmpty()) continue

                    var major = ""
                    var middle = ""

                    if (p.contains("〔") && p.contains("〕")) {
                        major = p.substringBefore("〔").trim()
                        middle = p.substringAfter("〔").substringBefore("〕").trim()
                    } else if (p.contains(" - ")) {
                        major = p.substringBefore(" - ").trim()
                        middle = p.substringAfter(" - ").trim()
                    } else {
                        major = p
                    }

                    major = major.replace("／", "・")

                    if (major.isNotEmpty()) {
                        genreList.add(EpgGenre(major = major, middle = middle))
                    }
                }
            }
            return genreList
        }
        return emptyList()
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
                    val ip = settingsRepository.edcbIp.first()
                    val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
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

                    var safeHttpPort = httpPortCache ?: 5510
                    if (httpPortCache == null && !logoDataIniAttempted) {
                        try {
                            val srvIni = EdcbApi(ip, port).fetchFiles(listOf("EpgTimerSrv.ini"))
                                ?.firstOrNull { it.data.isNotEmpty() }
                            if (srvIni != null) {
                                val iniText = decodeEdcbString(srvIni.data)
                                Regex("HttpPort\\s*=\\s*(\\d+)").find(iniText)
                                    ?.let { safeHttpPort = it.groupValues[1].toInt() }
                            }
                        } catch (e: Exception) {
                        }
                    }

                    val programs =
                        all.subList(from, to).map { mapToRecordedProgram(it, ip, safeHttpPort) }
                    Log.i(TAG, "[getRecordedPrograms] Page $page 返却完了 (件数: ${programs.size})")
                    RecordedApiResponse(total, programs)
                } catch (e: Exception) {
                    Log.e(TAG, "同期エラー", e); RecordedApiResponse(0, emptyList())
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> =
        withContext(Dispatchers.IO) {
            try {
                val ip = settingsRepository.edcbIp.first()
                val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510

                val info =
                    cachedRecInfos?.find { it.id == videoId } ?: EdcbApi(ip, port).getRecInfo(
                        videoId
                    ).getOrNull()

                if (info == null || !isValidRecord(info)) {
                    return@withContext Result.failure(Exception("Not found or invalid record"))
                }

                var safeHttpPort = httpPortCache ?: 5510
                if (httpPortCache == null && !logoDataIniAttempted) {
                    try {
                        val srvIni = EdcbApi(ip, port).fetchFiles(listOf("EpgTimerSrv.ini"))
                            ?.firstOrNull { it.data.isNotEmpty() }
                        if (srvIni != null) {
                            val iniText = decodeEdcbString(srvIni.data)
                            Regex("HttpPort\\s*=\\s*(\\d+)").find(iniText)
                                ?.let { safeHttpPort = it.groupValues[1].toInt() }
                        }
                    } catch (e: Exception) {
                    }
                }

                Result.success(
                    mapToRecordedProgram(
                        info,
                        ip,
                        safeHttpPort
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String
    ): String {
        val ip = settingsRepository.edcbIp.first()
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510

        val info = cachedRecInfos?.find { it.id == videoId } ?: EdcbApi(
            ip,
            port
        ).getRecInfo(videoId).getOrNull()

        var safeHttpPort = httpPortCache ?: 5510
        if (httpPortCache == null && !logoDataIniAttempted) {
            try {
                val srvIni = EdcbApi(ip, port).fetchFiles(listOf("EpgTimerSrv.ini"))
                    ?.firstOrNull { it.data.isNotEmpty() }
                if (srvIni != null) {
                    val iniText = decodeEdcbString(srvIni.data)
                    Regex("HttpPort\\s*=\\s*(\\d+)").find(iniText)
                        ?.let { safeHttpPort = it.groupValues[1].toInt() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get HttpPort from EpgTimerSrv.ini", e)
            }
        }

        val playMethod = settingsRepository.edcbRecordPlayMethod.first()

        if (playMethod == "DIRECT") {
            val filePath = info?.recFilePath ?: return ""
            val relativePath = filePath
                .replace(Regex("^[a-zA-Z]:\\\\"), "")
                .replace("\\", "/")

            val videoUri = android.net.Uri.Builder()
                .scheme("http")
                .encodedAuthority("$ip:$safeHttpPort")
                .appendPath("rec")
                .appendEncodedPath(android.net.Uri.encode(relativePath, "/"))
                .build()

            Log.i(TAG, "Generated HTTP Stream URL (DIRECT): $videoUri")
            return videoUri.toString()
        } else {
            val videoUri = android.net.Uri.Builder()
                .scheme("http")
                .encodedAuthority("$ip:$safeHttpPort")
                .appendPath("api")
                .appendPath("Movie")
                .appendQueryParameter("id", videoId.toString())
                .build()

            Log.i(TAG, "Generated HTTP Stream URL (API): $videoUri")
            return videoUri.toString()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun mapToRecordedProgram(
        info: EdcbRecFileInfo,
        ip: String,
        httpPort: Int
    ): RecordedProgram {
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

        val extractedGenres = extractGenresFromProgramInfo(info.programInfo)

        val detailStartIdx = info.programInfo.indexOf("詳細情報")
        val genreStartIdx =
            info.programInfo.indexOf("ジャンル").takeIf { it != -1 } ?: info.programInfo.length

        val cleanDescription = if (info.programInfo.isNotBlank()) {
            val endIdx = if (detailStartIdx != -1) detailStartIdx else genreStartIdx
            info.programInfo.substring(0, endIdx).trim().ifBlank { info.comment }
        } else {
            info.comment
        }

        val detailMap = mutableMapOf<String, String>(
            "Error" to "${info.drops}",
            "Path" to info.recFilePath
        )

        val relativePath = info.recFilePath
            .replace(Regex("^[a-zA-Z]:\\\\"), "")
            .replace("\\", "/")
        val encodedPath = android.net.Uri.encode(relativePath, "/")

        val primaryUrl = "http://$ip:$httpPort/rec/$encodedPath.jpg"
        val fallbackUrl = "http://$ip:$httpPort/api/Thumbnail?id=${info.id}"

        if (detailStartIdx != -1 && genreStartIdx > detailStartIdx) {
            val extText = info.programInfo.substring(detailStartIdx + 4, genreStartIdx).trim()

            val parsedDetails = EdcbApi.parseProgramExtendedText(extText)
            if (parsedDetails.isNotEmpty() && parsedDetails.keys.any { it.isNotBlank() }) {
                detailMap.putAll(parsedDetails)
            } else if (extText.isNotBlank()) {
                detailMap["番組詳細"] = extText
            }
        }

        return RecordedProgram(
            info.id, info.title, null, false,
            cleanDescription,
            detailMap,
            isoStart, isoEnd, info.durationSec.toDouble(), info.drops > 0,
            RecordedChannel(
                channelId,
                info.onid,
                channelId,
                getChannelType(info.onid),
                info.serviceName,
                String.format("%03d", info.sid % 1000)
            ),
            RecordedVideo(
                info.id,
                if (isRecording) "Recording" else "Recorded",
                "http://$ip:$httpPort/legacy/view.lua?id=${info.id}",
                isoStart,
                isoEnd,
                info.durationSec.toDouble(),
                "mpegts",
                "mpeg2",
                "aac"
            ),
            extractedGenres,
            isRecording, 0.0,
            directThumbnailUrl = primaryUrl,
            apiThumbnailUrl = fallbackUrl
        )
    }

    override suspend fun searchRecordedPrograms(keyword: String, page: Int): RecordedApiResponse =
        RecordedApiResponse(0, emptyList())

    override suspend fun getArchivedJikkyo(v: Int): Result<List<ArchivedComment>> =
        Result.success(emptyList())

    @androidx.annotation.OptIn(UnstableApi::class)
    override suspend fun keepAlive(v: Int, q: String, s: String) {
    }

    // ==========================================
    // ★ 録画予約（Reserve）関連
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getReserves(): Result<List<ReserveItem>> = withContext(Dispatchers.IO) {
        try {
            val ip = settingsRepository.edcbIp.first()
            val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
            val edcbApi = EdcbApi(ip, port)

            fetchEpgDataIfNeeded()

            val reserves = edcbApi.getReserves().getOrThrow()

            val mappedReserves = reserves.map { res ->
                val isoStart = formatToIso(res.startTime)
                val isoEnd = if (res.startTime != null && res.durationSec > 0) {
                    try {
                        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                        val startLdt = LocalDateTime.parse(res.startTime, formatter)
                        startLdt.plusSeconds(res.durationSec.toLong())
                            .atZone(ZoneId.of("Asia/Tokyo"))
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    } catch (e: Exception) {
                        ""
                    }
                } else ""

                val channelTypeStr = getChannelType(res.originalNetworkID)
                val channelIdStr =
                    "edcb_${res.originalNetworkID}_${res.transportStreamID}_${res.serviceID}"

                val serviceInfo =
                    cachedServices.find { it.onid == res.originalNetworkID && it.tsid == res.transportStreamID && it.sid == res.serviceID }
                val remoconId = serviceInfo?.remoteControlKeyId ?: 0
                val stationName =
                    serviceInfo?.serviceName ?: res.stationName.ifBlank { "不明なチャンネル" }
                val channelNumber = formatChannelNumber(
                    channelTypeStr,
                    remoconId,
                    res.serviceID,
                    res.transportStreamID
                )

                val reserveChannel = ReserveChannel(
                    id = channelIdStr,
                    network_Id = res.originalNetworkID.toLong(),
                    service_Id = res.serviceID.toLong(),
                    channelNumber = channelNumber,
                    displayChannelId = channelIdStr,
                    type = channelTypeStr,
                    name = stationName
                )

                // ★ 修正: EPGキャッシュから詳細情報を引っ張ってくる
                val eventInfo = cachedEvents.find {
                    it.onid == res.originalNetworkID &&
                            it.tsid == res.transportStreamID &&
                            it.sid == res.serviceID &&
                            it.eid == res.eventID
                }

                val description = eventInfo?.eventText ?: ""
                val detail = eventInfo?.detailMap ?: emptyMap()
                val genres = eventInfo?.contentList?.let {
                    mapEdcbGenre(it).map { g -> ReserveGenre(major = g.major, middle = g.middle) }
                } ?: emptyList()

                val reserveProgram = ReserveProgramDetail(
                    id = channelIdStr + "_${res.eventID}",
                    title = res.title,
                    description = description, // ★修正: EPGの概要をセット
                    startTime = isoStart,
                    endTime = isoEnd,
                    duration = res.durationSec,
                    genres = genres,           // ★修正: EPGのジャンルをセット
                    detail = detail,           // ★修正: EPGの詳細情報をセット
                    isFree = true,
                    videoType = "mpeg2",
                    audioType = "2/0",
                    audioSamplingRate = "48000"
                )

                val recordSettings = ReserveRecordSettings(
                    isEnabled = res.recSetting.recMode != 5,
                    priority = res.recSetting.priority,
                    recordingFolders = res.recSetting.recFolderList.map { it.recFolder }
                        .filter { it.isNotBlank() },
                    startMargin = res.recSetting.startMargine,
                    endMargin = res.recSetting.endMargine,
                    recordingMode = "SpecifiedService",
                    postRecordingBatFilePath = res.recSetting.batFilePath.takeIf { it.isNotBlank() },
                    isEventRelayFollowEnabled = res.recSetting.tuijyuuFlag != 0,
                    isExactRecordingEnabled = res.recSetting.pittariFlag != 0,
                    forcedTunerId = res.recSetting.tunerID
                )

                val availability = when (res.overlapMode) {
                    1 -> "Partial"
                    2 -> "Unavailable"
                    else -> "Full"
                }

                val bitrateKbps = 19456
                val estimatedSize =
                    Math.max((bitrateKbps / 8.0 * 1000 * res.durationSec).toLong(), 0L)

                var isRecording = false
                try {
                    val now = LocalDateTime.now()
                    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                    val startLdt = LocalDateTime.parse(res.startTime, formatter)
                    val endLdt = startLdt.plusSeconds(res.durationSec.toLong())
                    if (now.isAfter(startLdt) && now.isBefore(endLdt) && res.recSetting.recMode != 5) {
                        isRecording = true
                    }
                } catch (e: Exception) {
                }

                ReserveItem(
                    id = res.reserveID,
                    channel = reserveChannel,
                    program = reserveProgram,
                    isRecordingInProgress = isRecording,
                    recordingAvailability = availability,
                    comment = res.comment, // コメントはメモ欄として別で保持する
                    estimatedRecordingFileSize = estimatedSize,
                    recordSettings = recordSettings
                )
            }
            Result.success(mappedReserves)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get reserves from EDCB", e)
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> =
        withContext(Dispatchers.IO) {
            try {
                val ip = settingsRepository.edcbIp.first()
                val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
                val edcbApi = EdcbApi(ip, port)

                fetchEpgDataIfNeeded()

                val conditions = edcbApi.getAutoAddConditions().getOrThrow()

                val uniqueServiceKeys =
                    cachedServices.map { "${it.onid}_${it.tsid}_${it.sid}" }.toSet()

                val mappedConditions = conditions.map { cond ->

                    val dateRanges = cond.searchInfo.dateList.map { dateInfo ->
                        ProgramSearchConditionDate(
                            startDayOfWeek = dateInfo.startDayOfWeek,
                            startHour = dateInfo.startHour,
                            startMinute = dateInfo.startMin,
                            endDayOfWeek = dateInfo.endDayOfWeek,
                            endHour = dateInfo.endHour,
                            endMinute = dateInfo.endMin
                        )
                    }.takeIf { it.isNotEmpty() }

                    val serviceRangesList = cond.searchInfo.serviceList.mapNotNull { serviceLong ->
                        val onid = (serviceLong ushr 32).toInt() and 0xFFFF
                        val tsid = (serviceLong ushr 16).toInt() and 0xFFFF
                        val sid = serviceLong.toInt() and 0xFFFF
                        ProgramSearchConditionService(
                            networkId = onid,
                            transportStreamId = tsid,
                            serviceId = sid
                        )
                    }

                    val conditionServiceKeys =
                        serviceRangesList.map { "${it.networkId}_${it.transportStreamId}_${it.serviceId}" }
                            .toSet()
                    val isAllChannelsSelected =
                        conditionServiceKeys.isNotEmpty() && conditionServiceKeys == uniqueServiceKeys
                    val serviceRanges =
                        if (isAllChannelsSelected) null else serviceRangesList.takeIf { it.isNotEmpty() }

                    val channels = cond.searchInfo.serviceList.mapNotNull { serviceLong ->
                        val onid = (serviceLong ushr 32).toInt() and 0xFFFF
                        val tsid = (serviceLong ushr 16).toInt() and 0xFFFF
                        val sid = serviceLong.toInt() and 0xFFFF

                        val typeStr = getChannelType(onid)
                        val svc =
                            cachedServices.find { it.onid == onid && it.tsid == tsid && it.sid == sid }

                        Channel(
                            id = "edcb_${onid}_${tsid}_${sid}",
                            displayChannelId = "edcb_${onid}_${tsid}_${sid}",
                            name = svc?.serviceName ?: "不明なチャンネル",
                            channelNumber = formatChannelNumber(
                                typeStr,
                                svc?.remoteControlKeyId ?: 0,
                                sid,
                                tsid
                            ),
                            networkId = onid.toLong(),
                            serviceId = sid.toLong(),
                            transportStreamId = tsid.toLong(),
                            type = typeStr,
                            isWatchable = true,
                            isDisplay = true,
                            is_subchannel = isSubChannelInternal(typeStr, sid, tsid),
                            programPresent = null,
                            programFollowing = null,
                            remocon_Id = svc?.remoteControlKeyId ?: 0,
                            jikkyoForce = 0
                        )
                    }

                    val searchCondition = ProgramSearchCondition(
                        isEnabled = cond.recSetting.recMode != 5,
                        keyword = cond.searchInfo.andKey,
                        excludeKeyword = cond.searchInfo.notKey,
                        note = "",
                        isTitleOnly = cond.searchInfo.titleOnlyFlag != 0,
                        isCaseSensitive = cond.searchInfo.caseSensitive,
                        isFuzzySearchEnabled = cond.searchInfo.aimaiFlag != 0,
                        isRegexSearchEnabled = cond.searchInfo.regExpFlag != 0,
                        serviceRanges = serviceRanges,
//                        channels = channels.takeIf { it.isNotEmpty() },
                        genreRanges = emptyList(),
                        isExcludeGenreRanges = cond.searchInfo.notContetFlag != 0,
                        dateRanges = dateRanges,
                        isExcludeDateRanges = cond.searchInfo.notDateFlag != 0,
                        durationRangeMin = cond.searchInfo.chkDurationMin.takeIf { it > 0 },
                        durationRangeMax = cond.searchInfo.chkDurationMax.takeIf { it > 0 },
                        broadcastType = "All",
                        duplicateTitleCheckScope = if (cond.searchInfo.chkRecEnd != 0) "AllChannels" else "None",
                        duplicateTitleCheckPeriodDays = cond.searchInfo.chkRecDay
                    )

                    val recFolders = cond.recSetting.recFolderList.mapNotNull { folderInfo ->
                        if (folderInfo.recFolder.isNotBlank()) {
                            RecordingFolder(
                                recordingFolderPath = folderInfo.recFolder,
                                recordingFileNameTemplate = folderInfo.recNamePlugIn.takeIf { it.isNotBlank() },
                                isOnesegSeparateRecordingFolder = false
                            )
                        } else null
                    }

                    val recordSettings = RecordSettings(
                        isEnabled = cond.recSetting.recMode != 5,
                        priority = cond.recSetting.priority,
                        recordingFolders = recFolders,
                        recordingStartMargin = if (cond.recSetting.useMargineFlag != 0) cond.recSetting.startMargine else null,
                        recordingEndMargin = if (cond.recSetting.useMargineFlag != 0) cond.recSetting.endMargine else null,
                        recordingMode = "SpecifiedService",
                        captionRecordingMode = "Default",
                        dataBroadcastingRecordingMode = "Default",
                        postRecordingMode = "Default",
                        postRecordingBatFilePath = cond.recSetting.batFilePath.takeIf { it.isNotBlank() },
                        isEventRelayFollowEnabled = cond.recSetting.tuijyuuFlag != 0,
                        isExactRecordingEnabled = cond.recSetting.pittariFlag != 0,
                        isOnesegSeparateOutputEnabled = false,
                        isSequentialRecordingInSingleFileEnabled = false,
                        forcedTunerId = cond.recSetting.tunerID.takeIf { it != 0 }
                    )

                    ReservationCondition(
                        id = cond.dataID,
                        reservationCount = cond.addCount,
                        programSearchCondition = searchCondition,
                        recordSettings = recordSettings
                    )
                }
                Result.success(mappedConditions)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get conditions from EDCB", e)
                Result.failure(e)
            }
        }

    // ★修正: 単発予約の削除
    override suspend fun deleteReservation(i: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ip = settingsRepository.edcbIp.first()
            val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
            val edcbApi = EdcbApi(ip, port)

            // 1回目の送信リクエスト
            val result = edcbApi.sendDelReserve(listOf(i))
            if (result.isSuccess) {
                return@withContext Result.success(Unit)
            }

            // ★ 削除時のフェイルセーフ
            // EDCBがエラー(0)を返しても、実際には消えているケースが多発するため再確認する
            val checkReserves = edcbApi.getReserves().getOrNull() ?: emptyList()
            val isDeleted = checkReserves.none { it.reserveID == i } // リストにもう存在しなければ削除成功

            if (isDeleted) {
                Log.w(TAG, "EDCB returned error on delete, but reservation was actually deleted.")
                return@withContext Result.success(Unit)
            }

            Result.failure(Exception("Failed to delete reservation"))
        } catch (e: Exception) {
            Log.e(TAG, "deleteReservation failed", e)
            Result.failure(e)
        }
    }

    // ★修正: 自動予約条件の削除
    override suspend fun deleteReservationCondition(i: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val ip = settingsRepository.edcbIp.first()
                val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
                val edcbApi = EdcbApi(ip, port)

                // 1回目の送信リクエスト
                val result = edcbApi.sendDelAutoAdd(listOf(i))
                if (result.isSuccess) {
                    return@withContext Result.success(Unit)
                }

                // ★ 削除時のフェイルセーフ
                val checkConditions = edcbApi.getAutoAddConditions().getOrNull() ?: emptyList()
                val isDeleted = checkConditions.none { it.dataID == i }

                if (isDeleted) {
                    Log.w(
                        TAG,
                        "EDCB returned error on delete, but auto add condition was actually deleted."
                    )
                    return@withContext Result.success(Unit)
                }

                Result.failure(Exception("Failed to delete reservation condition"))
            } catch (e: Exception) {
                Log.e(TAG, "deleteReservationCondition failed", e)
                Result.failure(e)
            }
        }

    // ==========================================
    // ★ 単発予約のエンコード（Kotlin -> EDCBバイナリ）処理
    // ==========================================

    private fun encodeReserveRecordSettings(s: ReserveRecordSettings): EdcbRecSettingData {
        // 録画モード (有効: 0~4, 無効: 5~9)
        val recMode = if (s.isEnabled) {
            when (s.recordingMode) {
                "AllServices" -> 0
                "AllServicesWithoutDecoding" -> 2
                "SpecifiedServiceWithoutDecoding" -> 3
                "View" -> 4
                else -> 1 // SpecifiedService
            }
        } else {
            when (s.recordingMode) {
                "AllServices" -> 9
                "AllServicesWithoutDecoding" -> 6
                "SpecifiedServiceWithoutDecoding" -> 7
                "View" -> 8
                else -> 5 // SpecifiedService (Disabled)
            }
        }

        // 字幕・データ放送の録画設定
        var serviceMode = 0
        if (s.captionMode != "Default" || s.dataMode != "Default") {
            serviceMode = 1 // 個別の設定値を使用するフラグ
            if (s.captionMode == "Enable") serviceMode = serviceMode or 0x10
            if (s.dataMode == "Enable") serviceMode = serviceMode or 0x20
        }

        // 録画後実行モード
        val suspendMode = when (s.postRecordingMode) {
            "Default" -> 0
            "Nothing" -> 4
            "Standby", "StandbyAndReboot" -> 1
            "Suspend", "SuspendAndReboot" -> 2
            "Shutdown" -> 3
            else -> 0
        }
        val rebootFlag = if (s.postRecordingMode.contains("Reboot")) 1 else 0

        // 録画フォルダ
        val folderList = s.recordingFolders?.map {
            EdcbRecFileSetInfo(it, "Write_Default.dll", "RecName_Macro.dll")
        } ?: emptyList()
        val partialFolderList = if (s.isOnesegSeparateOutputEnabled) folderList else emptyList()

        return EdcbRecSettingData(
            recMode = recMode,
            priority = s.priority,
            tuijyuuFlag = if (s.isEventRelayFollowEnabled) 1 else 0,
            serviceMode = serviceMode,
            pittariFlag = if (s.isExactRecordingEnabled) 1 else 0,
            batFilePath = s.postRecordingBatFilePath ?: "",
            recFolderList = folderList,
            suspendMode = suspendMode,
            rebootFlag = rebootFlag,
            useMargineFlag = if (s.startMargin != 0 || s.endMargin != 0) 1 else 0,
            startMargine = s.startMargin,
            endMargine = s.endMargin,
            continueRecFlag = if (s.isSequentialRecordingEnabled) 1 else 0,
            partialRecFlag = if (s.isOnesegSeparateOutputEnabled) 1 else 0,
            tunerID = s.forcedTunerId,
            partialRecFolder = partialFolderList
        )
    }

    // ==========================================
    // ★ リクエスト処理の実装（フェイルセーフ追加）
    // ==========================================

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun addReserve(r: ReserveRequest): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ip = settingsRepository.edcbIp.first()
            val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
            val edcbApi = EdcbApi(ip, port)

            fetchEpgDataIfNeeded()

            val parts = r.programId.split("_")
            if (parts.size < 5) return@withContext Result.failure(Exception("Invalid program ID format"))
            val onid = parts[1].toInt()
            val tsid = parts[2].toInt()
            val sid = parts[3].toInt()
            val eid = parts[4].toInt()

            val event =
                cachedEvents.find { it.onid == onid && it.tsid == tsid && it.sid == sid && it.eid == eid }
                    ?: return@withContext Result.failure(Exception("Event not found in EPG cache"))

            val svc = cachedServices.find { it.onid == onid && it.tsid == tsid && it.sid == sid }

            // ★ 事前重複チェック
            val reserves = edcbApi.getReserves().getOrNull() ?: emptyList()
            val isDuplicate = reserves.any {
                it.originalNetworkID == onid && it.transportStreamID == tsid &&
                        it.serviceID == sid && it.eventID == eid
            }
            if (isDuplicate) {
                return@withContext Result.failure(Exception("既に同じ番組が予約されています"))
            }

            val recSetting = encodeReserveRecordSettings(r.recordSettings)

            val reserveData = EdcbReserveData(
                title = event.eventName,
                startTime = event.startTime,
                durationSec = event.durationSec,
                stationName = svc?.serviceName ?: "",
                originalNetworkID = onid,
                transportStreamID = tsid,
                serviceID = sid,
                eventID = eid,
                comment = "",
                reserveID = 0,
                bPadding = 0,
                overlapMode = 0,
                strPadding = "",
                startTimeEpg = event.startTime,
                recSetting = recSetting,
                reserveStatus = 0,
                recFileNameList = emptyList(),
                trailingInt = 0
            )

            // 送信
            val result = edcbApi.sendAddReserve(listOf(reserveData))
            if (result.isSuccess) {
                return@withContext Result.success(Unit)
            }

            // ★ チューナー不足（録画重複）時のフェイルセーフ
            // EDCBはチューナー不足時、エラーコード 0 を返しますが、データベースには「一部録画」として登録します。
            // そのため、エラーが返ってきても、実際には登録に成功していないかを再取得して確認します。
            val checkReserves = edcbApi.getReserves().getOrNull() ?: emptyList()
            val successfullyAdded = checkReserves.any {
                it.originalNetworkID == onid && it.transportStreamID == tsid &&
                        it.serviceID == sid && it.eventID == eid
            }
            if (successfullyAdded) {
                Log.w(
                    TAG,
                    "EDCB returned 0 (Overlap warning), but reservation was added successfully."
                )
                return@withContext Result.success(Unit)
            }

            // KonomiTVのフェイルオーバー（放送終了扱い時の時刻延長リトライ）
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            val startLdt = LocalDateTime.parse(event.startTime, formatter)
            val startMs = startLdt.atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
            val nowMs = System.currentTimeMillis()
            val endMs = startMs + (event.durationSec * 1000)

            if (nowMs >= endMs) {
                val retryDuration = maxOf(((nowMs - startMs) / 1000).toInt() + 120, 120)
                val retryData = reserveData.copy(durationSec = retryDuration)
                Log.w(TAG, "Retrying with adjusted duration: $retryDuration")

                val retryResult = edcbApi.sendAddReserve(listOf(retryData))
                if (retryResult.isSuccess) return@withContext Result.success(Unit)
            }

            Result.failure(Exception("Failed to add reserve (EDCB rejected)"))
        } catch (e: Exception) {
            Log.e(TAG, "addReserve failed", e)
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun updateReserve(i: Int, r: ReserveRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val ip = settingsRepository.edcbIp.first()
                val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
                val edcbApi = EdcbApi(ip, port)

                val reserves = edcbApi.getReserves().getOrThrow()
                val existing = reserves.find { it.reserveID == i }
                    ?: return@withContext Result.failure(Exception("Reserve not found on server"))

                val recSetting = encodeReserveRecordSettings(r.recordSettings)
                val updatedData = existing.copy(recSetting = recSetting)

                val result = edcbApi.sendChgReserve(listOf(updatedData))
                if (result.isSuccess) {
                    return@withContext Result.success(Unit)
                }

                // ★ 更新時も重複警告で 0 が返ることがあるためフェイルセーフ
                val checkReserves = edcbApi.getReserves().getOrNull() ?: emptyList()
                val successfullyUpdated = checkReserves.find { it.reserveID == i }
                if (successfullyUpdated != null) {
                    Log.w(
                        TAG,
                        "EDCB returned 0, but reservation update might have succeeded (Overlap)."
                    )
                    return@withContext Result.success(Unit)
                }

                Result.failure(Exception("Failed to update reserve"))
            } catch (e: Exception) {
                Log.e(TAG, "updateReserve failed", e)
                Result.failure(e)
            }
        }

// ==========================================
    // ★ 自動予約のエンコード処理
    // ==========================================

    private fun encodeAutoAddRecordSettings(s: RecordSettings): EdcbRecSettingData {
        val recMode = if (s.isEnabled) {
            when (s.recordingMode) {
                "AllServices" -> 0
                "AllServicesWithoutDecoding" -> 2
                "SpecifiedServiceWithoutDecoding" -> 3
                "View" -> 4
                else -> 1
            }
        } else {
            when (s.recordingMode) {
                "AllServices" -> 9
                "AllServicesWithoutDecoding" -> 6
                "SpecifiedServiceWithoutDecoding" -> 7
                "View" -> 8
                else -> 5
            }
        }

        var serviceMode = 0
        if (s.captionRecordingMode != "Default" || s.dataBroadcastingRecordingMode != "Default") {
            serviceMode = 1
            if (s.captionRecordingMode == "Enable") serviceMode = serviceMode or 0x10
            if (s.dataBroadcastingRecordingMode == "Enable") serviceMode = serviceMode or 0x20
        }

        val suspendMode = when (s.postRecordingMode) {
            "Default" -> 0
            "Nothing" -> 4
            "Standby", "StandbyAndReboot" -> 1
            "Suspend", "SuspendAndReboot" -> 2
            "Shutdown" -> 3
            else -> 0
        }
        val rebootFlag = if (s.postRecordingMode.contains("Reboot")) 1 else 0

        val folderList = s.recordingFolders.map {
            val template = if (it.recordingFileNameTemplate.isNullOrBlank()) "RecName_Macro.dll" else "RecName_Macro.dll?${it.recordingFileNameTemplate}"
            EdcbRecFileSetInfo(it.recordingFolderPath, "Write_Default.dll", template)
        }
        val partialFolderList = if (s.isOnesegSeparateOutputEnabled) folderList else emptyList()

        return EdcbRecSettingData(
            recMode = recMode,
            priority = s.priority,
            tuijyuuFlag = if (s.isEventRelayFollowEnabled) 1 else 0,
            serviceMode = serviceMode,
            pittariFlag = if (s.isExactRecordingEnabled) 1 else 0,
            batFilePath = s.postRecordingBatFilePath ?: "",
            recFolderList = folderList,
            suspendMode = suspendMode,
            rebootFlag = rebootFlag,
            useMargineFlag = if (s.recordingStartMargin != null && s.recordingEndMargin != null) 1 else 0,
            startMargine = s.recordingStartMargin ?: 0,
            endMargine = s.recordingEndMargin ?: 0,
            continueRecFlag = if (s.isSequentialRecordingInSingleFileEnabled) 1 else 0,
            partialRecFlag = if (s.isOnesegSeparateOutputEnabled) 1 else 0,
            tunerID = s.forcedTunerId ?: 0,
            partialRecFolder = partialFolderList
        )
    }

    private fun encodeSearchKeyInfo(cond: ProgramSearchCondition): EdcbSearchInfo {
        // 対象サービスリスト。nullの場合はEPGキャッシュから全チャンネルを生成して「全指定」とする
        val serviceList = cond.serviceRanges?.map {
            (it.networkId.toLong() shl 32) or (it.transportStreamId.toLong() shl 16) or it.serviceId.toLong()
        } ?: cachedServices.map {
            (it.onid.toLong() shl 32) or (it.tsid.toLong() shl 16) or it.sid.toLong()
        }

        val dateList = cond.dateRanges?.map {
            EdcbDateData(it.startDayOfWeek, it.startHour, it.startMinute, it.endDayOfWeek, it.endHour, it.endMinute)
        } ?: emptyList()

        val freeCaFlag = when (cond.broadcastType) {
            "All" -> 0
            "FreeOnly" -> 1
            "PaidOnly" -> 2
            else -> 0
        }

        val chkRecEnd = if (cond.duplicateTitleCheckScope != "None") 1 else 0
        val chkRecNoService = if (cond.duplicateTitleCheckScope == "AllChannels") 1 else 0
        val chkRecDay = cond.duplicateTitleCheckPeriodDays

        // KonomiTVの「・」から「／」への戻しを含め、ジャンルの逆変換を試みる（簡易実装）
        val contentList = mutableListOf<EdcbContentData>()
        cond.genreRanges?.forEach { genre ->
            var cn1 = 0xFF
            var cn2 = 0xFF
            var un = 0x0
            val majorStr = genre.major.replace("・", "／")
            val middleStr = genre.middle.replace("・", "／")

            for ((key, value) in EdcbConstants.CONTENT_TYPE) {
                if (value.first == majorStr) {
                    cn1 = key
                    if (cn1 == 0x0E) { // 拡張
                        for ((uKey, uVal) in EdcbConstants.USER_TYPE) {
                            if (uVal == middleStr) {
                                cn2 = 0x00
                                un = uKey
                                break
                            }
                        }
                    } else if (middleStr == "すべて") {
                        cn2 = 0xFF
                    } else {
                        for ((mKey, mVal) in value.second) {
                            if (mVal == middleStr) {
                                cn2 = mKey
                                break
                            }
                        }
                    }
                    break
                }
            }
            val contentNibble = (cn1 shl 8) or cn2
            contentList.add(EdcbContentData(contentNibble, un))
        }

        return EdcbSearchInfo(
            andKey = cond.keyword,
            notKey = cond.excludeKeyword, // EDCBByteUtils.writeSearchKeyInfo内でnoteと共に処理される
            keyDisabled = !cond.isEnabled,
            caseSensitive = cond.isCaseSensitive,
            regExpFlag = if (cond.isRegexSearchEnabled) 1 else 0,
            titleOnlyFlag = if (cond.isTitleOnly) 1 else 0,
            contentList = contentList,
            dateList = dateList,
            serviceList = serviceList,
            videoList = emptyList(),
            audioList = emptyList(),
            aimaiFlag = if (cond.isFuzzySearchEnabled) 1 else 0,
            notContetFlag = if (cond.isExcludeGenreRanges) 1 else 0,
            notDateFlag = if (cond.isExcludeDateRanges) 1 else 0,
            freeCAFlag = freeCaFlag,
            chkRecEnd = chkRecEnd,
            chkRecDay = chkRecDay,
            chkRecNoService = chkRecNoService,
            chkDurationMin = cond.durationRangeMin ?: 0,
            chkDurationMax = cond.durationRangeMax ?: 0
        )
    }

    // ==========================================
    // ★ 自動予約 (AutoAdd) の追加・更新処理
    // ==========================================

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun addReservationCondition(r: ReservationConditionAddRequest): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ip = settingsRepository.edcbIp.first()
            val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
            val edcbApi = EdcbApi(ip, port)

            fetchEpgDataIfNeeded()

            // 追加前の条件リストの数を記憶（フェイルセーフ用）
            val existingConditions = edcbApi.getAutoAddConditions().getOrNull() ?: emptyList()

            // 検索条件と録画設定をEDCBバイナリ用にエンコード
            val searchInfo = encodeSearchKeyInfo(r.programSearchCondition)
            val recSetting = encodeAutoAddRecordSettings(r.recordSettings)

            val autoAddData = EdcbAutoAddData(
                dataID = 0, // 新規追加なのでIDは0
                searchInfo = searchInfo,
                recSetting = recSetting,
                addCount = 0
            )

            // 送信
            val result = edcbApi.sendAddAutoAdd(listOf(autoAddData))
            if (result.isSuccess) {
                return@withContext Result.success(Unit)
            }

            // ★ フェイルセーフ: EDCBが 0 (エラー) を返しても、実は登録されているか確認する
            val afterConditions = edcbApi.getAutoAddConditions().getOrNull() ?: emptyList()
            if (afterConditions.size > existingConditions.size) {
                Log.w(TAG, "EDCB returned 0, but auto add condition was successfully added.")
                return@withContext Result.success(Unit)
            }

            Result.failure(Exception("Failed to add auto add condition (EDCB rejected)"))
        } catch (e: Exception) {
            Log.e(TAG, "addReservationCondition failed", e)
            Result.failure(e)
        }
    }

    override suspend fun updateReservationCondition(i: Int, r: ReservationConditionUpdateRequest): Result<ReservationCondition> = withContext(Dispatchers.IO) {
        try {
            val ip = settingsRepository.edcbIp.first()
            val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
            val edcbApi = EdcbApi(ip, port)

            val existingList = edcbApi.getAutoAddConditions().getOrThrow()
            val existing = existingList.find { it.dataID == i }
                ?: return@withContext Result.failure(Exception("Condition not found on server"))

            val searchInfo = encodeSearchKeyInfo(r.programSearchCondition)
            val recSetting = encodeAutoAddRecordSettings(r.recordSettings)

            val updatedData = existing.copy(
                searchInfo = searchInfo,
                recSetting = recSetting
            )

            val result = edcbApi.sendChgAutoAdd(listOf(updatedData))
            if (result.isSuccess) {
                // 更新後のリストを再取得して結果として返す
                val newList = getReservationConditions().getOrThrow()
                return@withContext Result.success(newList.find { it.id == i }!!)
            }

            // ★ フェイルセーフ
            val checkList = edcbApi.getAutoAddConditions().getOrNull() ?: emptyList()
            if (checkList.any { it.dataID == i }) {
                Log.w(TAG, "EDCB returned 0, but auto add condition update might have succeeded.")
                val newList = getReservationConditions().getOrThrow()
                return@withContext Result.success(newList.find { it.id == i }!!)
            }

            Result.failure(Exception("Failed to update auto add condition"))
        } catch (e: Exception) {
            Log.e(TAG, "updateReservationCondition failed", e)
            Result.failure(e)
        }
    }
}