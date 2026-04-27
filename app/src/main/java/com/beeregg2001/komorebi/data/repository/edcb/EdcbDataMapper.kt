package com.beeregg2001.komorebi.data.repository.edcb

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.api.edcb.*
import com.beeregg2001.komorebi.data.model.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * EDCBの独自データ構造とKomorebiの共通ドメインモデルを相互に変換するマッパークラス。
 * リポジトリ層をクリーンに保つため、加工ロジックのみをここに集約。
 */
object EdcbDataMapper {

    @RequiresApi(Build.VERSION_CODES.O)
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    // ========================================================================
    // ジャンル・カテゴリー変換
    // ========================================================================

    /**
     * EDCBのNibble値（16進数）からドメインモデルのジャンルリストへ変換
     */
    fun mapEdcbGenre(contentList: List<EdcbContentData>?): List<EpgGenre> {
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

    // ========================================================================
    // 時刻・フォーマット変換
    // ========================================================================

    /**
     * EDCB形式の文字列(yyyy/MM/dd HH:mm:ss)をISO 8601形式へ変換
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun formatToIso(edcbTime: String?): String {
        if (edcbTime.isNullOrBlank()) return ""
        return try {
            val localDateTime = LocalDateTime.parse(edcbTime, DATE_FORMATTER)
            val zonedDateTime = localDateTime.atZone(ZoneId.of("Asia/Tokyo"))
            zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            ""
        }
    }

    // ========================================================================
    // 番組情報 (Program / EpgProgram) の変換
    // ========================================================================

    /**
     * EDCBのイベント情報を通常のProgramモデルへ変換
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun toProgram(event: EdcbEventInfo, channelId: String): Program {
        val isoStartTime = formatToIso(event.startTime)
        val isoEndTime = if (!event.startTime.isNullOrBlank() && event.durationSec > 0) {
            try {
                val startLdt = LocalDateTime.parse(event.startTime, DATE_FORMATTER)
                val endLdt = startLdt.plusSeconds(event.durationSec.toLong())
                endLdt.atZone(ZoneId.of("Asia/Tokyo"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (e: Exception) {
                ""
            }
        } else ""

        val mappedGenres = mapEdcbGenre(event.contentList).map {
            Genre(major = it.major, middle = it.middle)
        }

        return Program(
            id = "${channelId}_${event.eid}",
            title = event.eventName,
            description = event.eventText,
            detail = event.detailMap,
            startTime = isoStartTime,
            endTime = isoEndTime,
            duration = event.durationSec,
            genres = mappedGenres,
            videoResolution = null
        )
    }

    /**
     * EDCBのイベント情報を番組表用のEpgProgramモデルへ変換
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun toEpgProgram(
        event: EdcbEventInfo,
        channelId: String,
        networkId: Int,
        serviceId: Int
    ): EpgProgram? {
        val isoStartTime = formatToIso(event.startTime)
        if (isoStartTime.isEmpty()) return null

        val isoEndTime = if (event.durationSec > 0) {
            try {
                val startLdt = LocalDateTime.parse(event.startTime, DATE_FORMATTER)
                val endLdt = startLdt.plusSeconds(event.durationSec.toLong())
                endLdt.atZone(ZoneId.of("Asia/Tokyo"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (e: Exception) {
                ""
            }
        } else ""

        return EpgProgram(
            id = "${channelId}_${event.eid}",
            channel_id = channelId,
            network_id = networkId,
            service_id = serviceId,
            event_id = event.eid,
            title = event.eventName,
            description = event.eventText,
            extended = event.extendedText,
            detail = event.detailMap,
            start_time = isoStartTime,
            end_time = isoEndTime,
            duration = event.durationSec,
            is_free = event.freeCaFlag == 0,
            genres = mapEdcbGenre(event.contentList),
            video_type = "mpeg2",
            audio_type = "2/0",
            audio_sampling_rate = "48000"
        )
    }

    // ========================================================================
    // 録画予約・設定の変換 (Domain -> EDCB)
    // ========================================================================

    /**
     * 録画予約設定をEDCB API用のデータ構造へ変換
     */
    fun encodeReserveRecordSettings(s: ReserveRecordSettings): EdcbRecSettingData {
        val recMode = if (s.isEnabled) {
            when (s.recordingMode) {
                "AllServices" -> 0; "AllServicesWithoutDecoding" -> 2; "SpecifiedServiceWithoutDecoding" -> 3; "View" -> 4; else -> 1
            }
        } else {
            when (s.recordingMode) {
                "AllServices" -> 9; "AllServicesWithoutDecoding" -> 6; "SpecifiedServiceWithoutDecoding" -> 7; "View" -> 8; else -> 5
            }
        }

        var serviceMode = 0
        if (s.captionMode != "Default" || s.dataMode != "Default") {
            serviceMode = 1
            if (s.captionMode == "Enable") serviceMode = serviceMode or 0x10
            if (s.dataMode == "Enable") serviceMode = serviceMode or 0x20
        }

        val suspendMode = when (s.postRecordingMode) {
            "Default" -> 0; "Nothing" -> 4; "Standby", "StandbyAndReboot" -> 1; "Suspend", "SuspendAndReboot" -> 2; "Shutdown" -> 3; else -> 0
        }
        val rebootFlag = if (s.postRecordingMode.contains("Reboot")) 1 else 0

        val folderList = s.recordingFolders?.map {
            EdcbRecFileSetInfo(it, "Write_Default.dll", "RecName_Macro.dll")
        } ?: emptyList()

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
            partialRecFolder = if (s.isOnesegSeparateOutputEnabled) folderList else emptyList()
        )
    }

    /**
     * 自動録画条件の検索キー情報をEDCB API用のデータ構造へ変換
     */
    fun encodeSearchKeyInfo(
        cond: ProgramSearchCondition,
        cachedServices: List<EdcbServiceInfo>
    ): EdcbSearchInfo {
        val serviceList = cond.serviceRanges?.map {
            (it.networkId.toLong() shl 32) or (it.transportStreamId.toLong() shl 16) or it.serviceId.toLong()
        } ?: cachedServices.map {
            (it.onid.toLong() shl 32) or (it.tsid.toLong() shl 16) or it.sid.toLong()
        }

        val dateList = cond.dateRanges?.map {
            EdcbDateData(
                it.startDayOfWeek,
                it.startHour,
                it.startMinute,
                it.endDayOfWeek,
                it.endHour,
                it.endMinute
            )
        } ?: emptyList()

        val contentList = mutableListOf<EdcbContentData>()
        cond.genreRanges?.forEach { genre ->
            var cn1 = 0xFF;
            var cn2 = 0xFF;
            var un = 0x0
            val majorStr = genre.major.replace("・", "／");
            val middleStr = genre.middle.replace("・", "／")

            for ((key, value) in EdcbConstants.CONTENT_TYPE) {
                if (value.first == majorStr) {
                    cn1 = key
                    if (cn1 == 0x0E) {
                        for ((uKey, uVal) in EdcbConstants.USER_TYPE) {
                            if (uVal == middleStr) {
                                cn2 = 0x00; un = uKey; break
                            }
                        }
                    } else if (middleStr == "すべて") {
                        cn2 = 0xFF
                    } else {
                        for ((mKey, mVal) in value.second) {
                            if (mVal == middleStr) {
                                cn2 = mKey; break
                            }
                        }
                    }
                    break
                }
            }
            contentList.add(EdcbContentData((cn1 shl 8) or cn2, un))
        }

        return EdcbSearchInfo(
            andKey = cond.keyword,
            notKey = cond.excludeKeyword,
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
            freeCAFlag = if (cond.broadcastType == "FreeOnly") 1 else if (cond.broadcastType == "PaidOnly") 2 else 0,
            chkRecEnd = if (cond.duplicateTitleCheckScope != "None") 1 else 0,
            chkRecDay = cond.duplicateTitleCheckPeriodDays,
            chkRecNoService = if (cond.duplicateTitleCheckScope == "AllChannels") 1 else 0,
            chkDurationMin = cond.durationRangeMin ?: 0,
            chkDurationMax = cond.durationRangeMax ?: 0
        )
    }

    // ========================================================================
    // チャプター・CM解析
    // ========================================================================

    /**
     * TvtPlay形式のチャプターテキストをCMセクションのリストへ解析
     */
    fun parseChapterTextToCmSections(chapterText: String, durationSec: Double): List<CmSection> {
        val lines = chapterText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val chapters = mutableListOf<Triple<Int, String, Double>>()
        val cmSections = mutableListOf<CmSection>()
        var pendingTime: Double? = null

        for (line in lines) {
            if (line.startsWith("CHAPTER") && !line.contains("NAME=")) {
                pendingTime = line.substringAfter("=").split(":").let {
                    if (it.size == 3) it[0].toDouble() * 3600 + it[1].toDouble() * 60 + it[2].toDouble() else null
                }
            } else if (line.startsWith("CHAPTER") && line.contains("NAME=")) {
                val name = line.substringAfter("=")
                if (pendingTime != null) {
                    if (pendingTime <= durationSec + 5.0) chapters.add(Triple(0, name, pendingTime))
                    pendingTime = null
                }
            }
        }

        var currentCmStart: Double? = null
        for ((_, name, ctime) in chapters) {
            val lowerName = name.lowercase()
            if (lowerName.startsWith("o") || lowerName.startsWith("cm")) {
                if (currentCmStart == null) currentCmStart = ctime
            } else if (lowerName.startsWith("i") || lowerName.isNotBlank()) {
                if (currentCmStart != null) {
                    cmSections.add(CmSection(currentCmStart, ctime))
                    currentCmStart = null
                }
            }
        }
        if (currentCmStart != null) cmSections.add(CmSection(currentCmStart, durationSec))
        return cmSections
    }
}