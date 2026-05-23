package com.beeregg2001.komorebi.data.repository.edcb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.edcb.EdcbApi
import com.beeregg2001.komorebi.data.api.edcb.EdcbAutoAddData
import com.beeregg2001.komorebi.data.api.edcb.EdcbReserveData
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdcbReserveRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheManager: EdcbEpgCacheManager
) : ReserveProvider {

    companion object {
        private const val TAG = "EdcbReserveRepository"
    }

    private suspend fun getTcpIpAndPort(): Pair<String, Int> {
        val rawIp = settingsRepository.edcbIp.first()
        val cleanIp = rawIp.replace(Regex("^https?://"), "")
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
        return Pair(cleanIp, port)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getReserves(): Result<List<ReserveItem>> = withContext(Dispatchers.IO) {
        try {
            val (ip, port) = getTcpIpAndPort()
            val edcbApi = EdcbApi(ip, port)

            cacheManager.fetchEpgDataIfNeeded()

            val reserves = edcbApi.getReserves().getOrThrow()

            val mappedReserves = reserves.map { res ->
                val isoStart = EdcbDataMapper.formatToIso(res.startTime)
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

                val channelTypeStr = cacheManager.getChannelType(res.originalNetworkID)
                val channelIdStr =
                    "edcb_${res.originalNetworkID}_${res.transportStreamID}_${res.serviceID}"

                val serviceInfo =
                    cacheManager.cachedServices.find { it.onid == res.originalNetworkID && it.tsid == res.transportStreamID && it.sid == res.serviceID }
                val remoconId = serviceInfo?.remoteControlKeyId ?: 0
                val stationName =
                    serviceInfo?.serviceName ?: res.stationName.ifBlank { "不明なチャンネル" }
                val channelNumber = cacheManager.formatChannelNumber(
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

                val eventInfo = cacheManager.cachedEvents.find {
                    it.onid == res.originalNetworkID && it.tsid == res.transportStreamID && it.sid == res.serviceID && it.eid == res.eventID
                }

                val description = eventInfo?.eventText ?: ""
                val detail = eventInfo?.detailMap ?: emptyMap()
                val genres = eventInfo?.contentList?.let {
                    EdcbDataMapper.mapEdcbGenre(it)
                        .map { g -> ReserveGenre(major = g.major, middle = g.middle) }
                } ?: emptyList()

                val reserveProgram = ReserveProgramDetail(
                    id = channelIdStr + "_${res.eventID}",
                    title = res.title,
                    description = description,
                    startTime = isoStart,
                    endTime = isoEnd,
                    duration = res.durationSec,
                    genres = genres,
                    detail = detail,
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
                    1 -> "Partial"; 2 -> "Unavailable"; else -> "Full"
                }
                val estimatedSize = Math.max((19456 / 8.0 * 1000 * res.durationSec).toLong(), 0L)

                var isRecording = false
                try {
                    val now = LocalDateTime.now()
                    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                    val startLdt = LocalDateTime.parse(res.startTime, formatter)
                    val endLdt = startLdt.plusSeconds(res.durationSec.toLong())
                    if (now.isAfter(startLdt) && now.isBefore(endLdt) && res.recSetting.recMode != 5) isRecording =
                        true
                } catch (e: Exception) {
                }

                ReserveItem(
                    id = res.reserveID,
                    channel = reserveChannel,
                    program = reserveProgram,
                    isRecordingInProgress = isRecording,
                    recordingAvailability = availability,
                    comment = res.comment,
                    estimatedRecordingFileSize = estimatedSize,
                    recordSettings = recordSettings
                )
            }
            Result.success(mappedReserves)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get reserves from EDCB", e)
            // ★ 修正: エラーメッセージを分かりやすくラップ
            Result.failure(Exception("予約一覧の取得に失敗しました。\nEDCBとの接続設定を確認してください。\n[詳細]: ${e.message}"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> =
        withContext(Dispatchers.IO) {
            try {
                val (ip, port) = getTcpIpAndPort()
                val edcbApi = EdcbApi(ip, port)

                cacheManager.fetchEpgDataIfNeeded()

                val conditions = edcbApi.getAutoAddConditions().getOrThrow()
                val uniqueServiceKeys =
                    cacheManager.cachedServices.map { "${it.onid}_${it.tsid}_${it.sid}" }.toSet()

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
                // ★ 修正
                Result.failure(Exception("自動録画ルールの取得に失敗しました。\n[詳細]: ${e.message}"))
            }
        }

    override suspend fun deleteReservation(i: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val (ip, port) = getTcpIpAndPort()
            val edcbApi = EdcbApi(ip, port)

            val result = edcbApi.sendDelReserve(listOf(i))
            if (result.isSuccess) return@withContext Result.success(Unit)

            val checkReserves = edcbApi.getReserves().getOrNull() ?: emptyList()
            if (checkReserves.none { it.reserveID == i }) return@withContext Result.success(Unit)

            Result.failure(Exception("Failed to delete reservation"))
        } catch (e: Exception) {
            // ★ 修正
            Result.failure(Exception("予約の削除に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun deleteReservationCondition(i: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val (ip, port) = getTcpIpAndPort()
                val edcbApi = EdcbApi(ip, port)

                val result = edcbApi.sendDelAutoAdd(listOf(i))
                if (result.isSuccess) return@withContext Result.success(Unit)

                val checkConditions = edcbApi.getAutoAddConditions().getOrNull() ?: emptyList()
                if (checkConditions.none { it.dataID == i }) return@withContext Result.success(Unit)

                Result.failure(Exception("Failed to delete reservation condition"))
            } catch (e: Exception) {
                // ★ 修正
                Result.failure(Exception("自動録画ルールの削除に失敗しました。\n[詳細]: ${e.message}"))
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun addReserve(r: ReserveRequest): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val (ip, port) = getTcpIpAndPort()
            val edcbApi = EdcbApi(ip, port)

            cacheManager.fetchEpgDataIfNeeded()

            val parts = r.programId.split("_")
            if (parts.size < 5) return@withContext Result.failure(Exception("Invalid program ID format"))
            val onid = parts[1].toInt();
            val tsid = parts[2].toInt();
            val sid = parts[3].toInt();
            val eid = parts[4].toInt()

            val event =
                cacheManager.cachedEvents.find { it.onid == onid && it.tsid == tsid && it.sid == sid && it.eid == eid }
                    ?: return@withContext Result.failure(Exception("Event not found in EPG cache"))
            val svc =
                cacheManager.cachedServices.find { it.onid == onid && it.tsid == tsid && it.sid == sid }

            val recSetting = EdcbDataMapper.encodeReserveRecordSettings(r.recordSettings)

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

            val result = edcbApi.sendAddReserve(listOf(reserveData))
            if (result.isSuccess) return@withContext Result.success(Unit)

            Result.failure(Exception("Failed to add reserve"))
        } catch (e: Exception) {
            // ★ 修正
            Result.failure(Exception("予約の追加に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun updateReserve(i: Int, r: ReserveRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val (ip, port) = getTcpIpAndPort()
                val edcbApi = EdcbApi(ip, port)

                val reserves = edcbApi.getReserves().getOrThrow()
                val existing =
                    reserves.find { it.reserveID == i } ?: return@withContext Result.failure(
                        Exception("Reserve not found")
                    )

                val recSetting = EdcbDataMapper.encodeReserveRecordSettings(r.recordSettings)
                val updatedData = existing.copy(recSetting = recSetting)

                val result = edcbApi.sendChgReserve(listOf(updatedData))
                if (result.isSuccess) return@withContext Result.success(Unit)

                Result.failure(Exception("Failed to update reserve"))
            } catch (e: Exception) {
                // ★ 修正
                Result.failure(Exception("予約の更新に失敗しました。\n[詳細]: ${e.message}"))
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun addReservationCondition(r: ReservationConditionAddRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val (ip, port) = getTcpIpAndPort()
                val edcbApi = EdcbApi(ip, port)

                cacheManager.fetchEpgDataIfNeeded()

                val searchInfo = EdcbDataMapper.encodeSearchKeyInfo(
                    r.programSearchCondition,
                    cacheManager.cachedServices
                )
                val recSetting = EdcbDataMapper.encodeReserveRecordSettings(
                    ReserveRecordSettings(
                        isEnabled = r.recordSettings.isEnabled,
                        priority = r.recordSettings.priority
                    )
                )

                val autoAddData = EdcbAutoAddData(
                    dataID = 0,
                    searchInfo = searchInfo,
                    recSetting = recSetting,
                    addCount = 0
                )

                val result = edcbApi.sendAddAutoAdd(listOf(autoAddData))
                if (result.isSuccess) return@withContext Result.success(Unit)

                Result.failure(Exception("Failed to add auto add condition"))
            } catch (e: Exception) {
                // ★ 修正
                Result.failure(Exception("自動録画ルールの追加に失敗しました。\n[詳細]: ${e.message}"))
            }
        }

    override suspend fun updateReservationCondition(
        i: Int,
        r: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> = withContext(Dispatchers.IO) {
        // ★ 修正: 未実装部分のプレースホルダーを正式なエラー通知に修正
        Result.failure(Exception("自動録画ルールの更新は現在EDCBバックエンドではサポートされていません。"))
    }
}