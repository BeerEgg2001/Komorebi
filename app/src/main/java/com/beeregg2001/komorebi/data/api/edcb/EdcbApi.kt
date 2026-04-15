package com.beeregg2001.komorebi.data.api.edcb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EdcbRecFileInfo(
    val id: Int,
    val recFilePath: String,
    val title: String,
    val startTime: String?,
    val durationSec: Int,
    val serviceName: String,
    val onid: Int, val tsid: Int, val sid: Int, val eid: Int,
    val drops: Long,
    val scrambles: Long,
    val recStatus: Int,
    val startTimeEpg: String?,
    val comment: String,
    val programInfo: String,
    val errInfo: String,
    val protectFlag: Boolean
)

data class EdcbServiceInfo(
    val onid: Int, val tsid: Int, val sid: Int, val serviceType: Int,
    val partialReceptionFlag: Int, val serviceProviderName: String,
    val serviceName: String, val networkName: String, val tsName: String,
    val remoteControlKeyId: Int
)

data class EdcbContentData(
    val contentNibble: Int,
    val userNibble: Int
)

data class EdcbEventInfo(
    val onid: Int, val tsid: Int, val sid: Int, val eid: Int,
    val startTime: String?, val durationSec: Int,
    val eventName: String, val eventText: String, val freeCaFlag: Int,
    val contentList: List<EdcbContentData>? = null,
    val extendedText: String = "",
    val detailMap: Map<String, String> = emptyMap()
)

data class EdcbReserveData(
    val title: String, val startTime: String?, val durationSec: Int,
    val stationName: String, val originalNetworkID: Int, val transportStreamID: Int,
    val serviceID: Int, val eventID: Int, val comment: String, val reserveID: Int,
    val bPadding: Int, val overlapMode: Int, val strPadding: String,
    val startTimeEpg: String?, val recSetting: EdcbRecSettingData, val reserveStatus: Int,
    val recFileNameList: List<String>, val trailingInt: Int
)

data class EdcbAutoAddData(
    val dataID: Int, val searchInfo: EdcbSearchInfo,
    val recSetting: EdcbRecSettingData, val addCount: Int
)

data class EdcbSearchInfo(
    val andKey: String, val notKey: String, val keyDisabled: Boolean, val caseSensitive: Boolean,
    val regExpFlag: Int, val titleOnlyFlag: Int,
    val contentList: List<EdcbContentData>, val dateList: List<EdcbDateData>,
    val serviceList: List<Long>, val videoList: List<Int>, val audioList: List<Int>,
    val aimaiFlag: Int, val notContetFlag: Int, val notDateFlag: Int, val freeCAFlag: Int,
    val chkRecEnd: Int, var chkRecDay: Int = 6, var chkRecNoService: Int = 0,
    var chkDurationMin: Int = 0, var chkDurationMax: Int = 0
)

data class EdcbDateData(
    val startDayOfWeek: Int, val startHour: Int, val startMin: Int,
    val endDayOfWeek: Int, val endHour: Int, val endMin: Int
)

data class EdcbRecSettingData(
    val recMode: Int, val priority: Int, val tuijyuuFlag: Int, val serviceMode: Int,
    val pittariFlag: Int, val batFilePath: String, val recFolderList: List<EdcbRecFileSetInfo>,
    val suspendMode: Int, val rebootFlag: Int, val useMargineFlag: Int,
    val startMargine: Int, val endMargine: Int, val continueRecFlag: Int,
    val partialRecFlag: Int, val tunerID: Int, val partialRecFolder: List<EdcbRecFileSetInfo>
)

data class EdcbRecFileSetInfo(
    val recFolder: String,
    val writePlugIn: String,
    val recNamePlugIn: String
)

class EdcbApi(private val ip: String, private val port: Int) {
    companion object {
        private const val TAG = "EdcbApi"
        const val CMD_EPG_SRV_ENUM_TUNER_PROCESS = 1066
        const val CMD_EPG_SRV_ENUM_SERVICE = 1021
        const val CMD_EPG_SRV_ENUM_PG_INFO_EX = 1029
        const val CMD_EPG_SRV_FILE_COPY2 = 2060
        const val CMD_EPG_SRV_ENUM_RECINFO2 = 2017
        const val CMD_EPG_SRV_ENUM_RECINFO_BASIC2 = 2020
        const val CMD_EPG_SRV_GET_RECINFO2 = 2024
        const val CMD_VER = 5

        fun parseProgramExtendedText(s: String): Map<String, String> {
            val str = s.replace("\r", "")
            val map = mutableMapOf<String, String>()
            var head = ""
            var i = 0
            while (true) {
                var j = str.indexOf("\n- ", i)
                if (i == 0 && str.startsWith("- ")) {
                    j = 2
                } else if (j >= 0) {
                    var uniqueHead = head
                    while (map.containsKey(uniqueHead)) uniqueHead += "\t"
                    map[uniqueHead] = str.substring(if (i == 0) 0 else i + 1, j + 1).trim()
                    j += 3
                } else {
                    if (str.isNotEmpty()) {
                        var uniqueHead = head
                        while (map.containsKey(uniqueHead)) uniqueHead += "\t"
                        map[uniqueHead] = str.substring(if (i == 0) 0 else i + 1).trim()
                    }
                    break
                }
                i = str.indexOf("\n", j)
                if (i < 0) {
                    head = str.substring(j).trim()
                    var uniqueHead = head
                    while (map.containsKey(uniqueHead)) uniqueHead += "\t"
                    map[uniqueHead] = ""
                    break
                }
                head = str.substring(j, i).trim()
            }
            return map
        }
    }

    private val tcpClient = EdcbTcpClient(ip, port)

    suspend fun checkConnection(): Result<Boolean> {
        return try {
            val responseBuffer = tcpClient.sendCommand(CMD_EPG_SRV_ENUM_TUNER_PROCESS)
            if (responseBuffer == null) return Result.failure(Exception("Ping Failed"))
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getServices(): Result<List<EdcbServiceInfo>> {
        return try {
            val responseBuffer = tcpClient.sendCommand(CMD_EPG_SRV_ENUM_SERVICE)
                ?: return Result.failure(Exception("Failed response"))
            val services = EdcbByteUtils.readVector(responseBuffer) { buf ->
                val startPos = buf.position()
                val structSize = EdcbByteUtils.readStructIntro(buf)
                val info = EdcbServiceInfo(
                    onid = EdcbByteUtils.readUshort(buf),
                    tsid = EdcbByteUtils.readUshort(buf),
                    sid = EdcbByteUtils.readUshort(buf),
                    serviceType = EdcbByteUtils.readByte(buf),
                    partialReceptionFlag = EdcbByteUtils.readByte(buf),
                    serviceProviderName = EdcbByteUtils.readString(buf),
                    serviceName = EdcbByteUtils.readString(buf),
                    networkName = EdcbByteUtils.readString(buf),
                    tsName = EdcbByteUtils.readString(buf),
                    remoteControlKeyId = EdcbByteUtils.readByte(buf)
                )
                val endPos = startPos + structSize
                if (endPos <= buf.limit()) buf.position(endPos)
                info
            }
            Result.success(services)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun getEventInfos(
        services: List<EdcbServiceInfo>,
        startTime: java.time.LocalDateTime? = null,
        endTime: java.time.LocalDateTime? = null
    ): Result<List<EdcbEventInfo>> {
        if (services.isEmpty()) return Result.success(emptyList())
        return try {
            val elementCount = services.size * 2 + 2
            val vectorTotalSize = 8 + elementCount * 8
            val requestBuffer = ByteBuffer.allocate(vectorTotalSize).order(ByteOrder.LITTLE_ENDIAN)
            requestBuffer.putInt(vectorTotalSize)
            requestBuffer.putInt(elementCount)
            for (svc in services) {
                val serviceIdLong =
                    (svc.onid.toLong() shl 32) or (svc.tsid.toLong() shl 16) or svc.sid.toLong()
                requestBuffer.putLong(0L)
                requestBuffer.putLong(serviceIdLong)
            }

            // ★ 高速化用: 取得期間を指定された場合、FILETIMEに変換してリクエストに付与
            val startFileTime = if (startTime != null) {
                val startMs = startTime.atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
                EdcbByteUtils.dateTimeToFileTime(startMs)
            } else 0L

            val endFileTime = if (endTime != null) {
                val endMs = endTime.atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
                EdcbByteUtils.dateTimeToFileTime(endMs)
            } else Long.MAX_VALUE

            requestBuffer.putLong(startFileTime)
            requestBuffer.putLong(endFileTime)

            val responseBuffer =
                tcpClient.sendCommand(CMD_EPG_SRV_ENUM_PG_INFO_EX, requestBuffer.array())
                    ?: return Result.failure(Exception("Failed response from EDCB"))
            val events = mutableListOf<EdcbEventInfo>()
            EdcbByteUtils.readVector(responseBuffer) { buf ->
                val startOuterPos = buf.position()
                val outerStructSize = EdcbByteUtils.readStructIntro(buf)
                val startSvcPos = buf.position()
                val svcStructSize = EdcbByteUtils.readStructIntro(buf)
                val onid = EdcbByteUtils.readUshort(buf)
                val tsid = EdcbByteUtils.readUshort(buf)
                val sid = EdcbByteUtils.readUshort(buf)
                val endSvcPos = startSvcPos + svcStructSize
                if (endSvcPos <= buf.limit()) buf.position(endSvcPos)
                val eventList = EdcbByteUtils.readVector(buf) { eventBuf ->
                    val startEventPos = eventBuf.position()
                    val eventStructSize = EdcbByteUtils.readStructIntro(eventBuf)
                    val eOnid = EdcbByteUtils.readUshort(eventBuf)
                    val eTsid = EdcbByteUtils.readUshort(eventBuf)
                    val eSid = EdcbByteUtils.readUshort(eventBuf)
                    val eid = EdcbByteUtils.readUshort(eventBuf)
                    val hasStartTime = EdcbByteUtils.readByte(eventBuf) != 0
                    val startTimeStr = EdcbByteUtils.readSystemTime(eventBuf)
                    val hasDuration = EdcbByteUtils.readByte(eventBuf) != 0
                    val durationVal = EdcbByteUtils.readInt(eventBuf)
                    var eventName = ""
                    var eventText = ""
                    var extendedText = ""
                    var detailMap = emptyMap<String, String>()
                    var contentList: List<EdcbContentData> = emptyList()
                    val startShortPos = eventBuf.position()
                    val shortInfoSize = EdcbByteUtils.readInt(eventBuf)
                    if (shortInfoSize > 4) {
                        eventName = EdcbByteUtils.readString(eventBuf)
                        eventText = EdcbByteUtils.readString(eventBuf)
                    }
                    val endShortPos = startShortPos + shortInfoSize
                    if (endShortPos <= eventBuf.limit()) eventBuf.position(endShortPos)
                    val startExtPos = eventBuf.position()
                    val extInfoSize = EdcbByteUtils.readInt(eventBuf)
                    if (extInfoSize > 4) {
                        extendedText = EdcbByteUtils.readString(eventBuf)
                        detailMap = parseProgramExtendedText(extendedText)
                    }
                    val endExtPos = startExtPos + extInfoSize
                    if (endExtPos <= eventBuf.limit()) eventBuf.position(endExtPos)
                    val startContentPos = eventBuf.position()
                    val contentInfoSize = EdcbByteUtils.readInt(eventBuf)
                    if (contentInfoSize > 4) {
                        val vectorStartPos = eventBuf.position()
                        val vs = EdcbByteUtils.readInt(eventBuf)
                        val vc = EdcbByteUtils.readInt(eventBuf)
                        val list = mutableListOf<EdcbContentData>()
                        for (i in 0 until vc) {
                            val cStart = eventBuf.position()
                            val cSize = EdcbByteUtils.readStructIntro(eventBuf)
                            val cn = EdcbByteUtils.readUshort(eventBuf)
                            val un = EdcbByteUtils.readUshort(eventBuf)
                            val endC = cStart + cSize
                            if (endC <= eventBuf.limit()) eventBuf.position(endC)
                            val contentNibble = ((cn shr 8) or (cn shl 8)) and 0xFFFF
                            val userNibble = ((un shr 8) or (un shl 8)) and 0xFFFF
                            list.add(EdcbContentData(contentNibble, userNibble))
                        }
                        contentList = list
                        val endVectorPos = vectorStartPos + vs
                        if (endVectorPos <= eventBuf.limit()) eventBuf.position(endVectorPos)
                    }
                    val endContentPos = startContentPos + contentInfoSize
                    if (endContentPos <= eventBuf.limit()) eventBuf.position(endContentPos)
                    val startCompPos = eventBuf.position()
                    val compInfoSize = EdcbByteUtils.readInt(eventBuf)
                    val endCompPos = startCompPos + compInfoSize
                    if (endCompPos <= eventBuf.limit()) eventBuf.position(endCompPos)
                    val startAudioPos = eventBuf.position()
                    val audioInfoSize = EdcbByteUtils.readInt(eventBuf)
                    val endAudioPos = startAudioPos + audioInfoSize
                    if (endAudioPos <= eventBuf.limit()) eventBuf.position(endAudioPos)
                    val startEgPos = eventBuf.position()
                    val egInfoSize = EdcbByteUtils.readInt(eventBuf)
                    val endEgPos = startEgPos + egInfoSize
                    if (endEgPos <= eventBuf.limit()) eventBuf.position(endEgPos)
                    val startErPos = eventBuf.position()
                    val erInfoSize = EdcbByteUtils.readInt(eventBuf)
                    val endErPos = startErPos + erInfoSize
                    if (endErPos <= eventBuf.limit()) eventBuf.position(endErPos)
                    val freeCaFlag = EdcbByteUtils.readByte(eventBuf)
                    val endEventPos = startEventPos + eventStructSize
                    if (endEventPos <= eventBuf.limit()) eventBuf.position(endEventPos)
                    EdcbEventInfo(
                        eOnid, eTsid, eSid, eid, if (hasStartTime) startTimeStr else null,
                        if (hasDuration) durationVal else 0, eventName, eventText,
                        freeCaFlag, contentList, extendedText, detailMap
                    )
                }
                events.addAll(eventList)
                val endOuterPos = startOuterPos + outerStructSize
                if (endOuterPos <= buf.limit()) buf.position(endOuterPos)
            }
            Result.success(events)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchFiles(fileNames: List<String>): List<EdcbFileData>? =
        withContext(Dispatchers.IO) {
            try {
                val req = EdcbByteUtils.writeStringVectorWithVersion(CMD_VER, fileNames)
                val res =
                    tcpClient.sendCommand(CMD_EPG_SRV_FILE_COPY2, req) ?: return@withContext null
                EdcbByteUtils.readUshort(res)
                EdcbByteUtils.readVector(res) { EdcbByteUtils.readFileData(it) }.filterNotNull()
            } catch (e: Exception) {
                null
            }
        }

    suspend fun getRecInfosFull(): Result<List<EdcbRecFileInfo>> {
        return try {
            val payload =
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(CMD_VER.toShort())
                    .array()
            val res =
                tcpClient.sendCommand(CMD_EPG_SRV_ENUM_RECINFO2, payload) ?: return Result.success(
                    emptyList()
                )
            EdcbByteUtils.readUshort(res)

            val list = EdcbByteUtils.readVector(res) { buf ->
                val startPos = buf.position()
                val structSize = EdcbByteUtils.readStructIntro(buf)
                val info = EdcbRecFileInfo(
                    id = EdcbByteUtils.readInt(buf),
                    recFilePath = EdcbByteUtils.readString(buf),
                    title = EdcbByteUtils.readString(buf),
                    startTime = EdcbByteUtils.readSystemTime(buf),
                    durationSec = EdcbByteUtils.readInt(buf).let { if (it < 0) 0 else it },
                    serviceName = EdcbByteUtils.readString(buf),
                    onid = EdcbByteUtils.readUshort(buf),
                    tsid = EdcbByteUtils.readUshort(buf),
                    sid = EdcbByteUtils.readUshort(buf),
                    eid = EdcbByteUtils.readUshort(buf),
                    drops = EdcbByteUtils.readLong(buf),
                    scrambles = EdcbByteUtils.readLong(buf),
                    recStatus = EdcbByteUtils.readInt(buf),
                    startTimeEpg = EdcbByteUtils.readSystemTime(buf),
                    comment = EdcbByteUtils.readString(buf),
                    programInfo = EdcbByteUtils.readString(buf),
                    errInfo = EdcbByteUtils.readString(buf),
                    protectFlag = EdcbByteUtils.readByte(buf) != 0
                )
                val expectedEndPos = startPos + structSize
                if (expectedEndPos <= buf.limit()) buf.position(expectedEndPos)
                info
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching full rec info", e)
            Result.failure(e)
        }
    }

    suspend fun getRecInfo(infoId: Int): Result<EdcbRecFileInfo> {
        return try {
            val payload =
                ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(CMD_VER.toShort())
                    .putInt(infoId).array()
            val res =
                tcpClient.sendCommand(CMD_EPG_SRV_GET_RECINFO2, payload) ?: return Result.failure(
                    Exception("NULL")
                )
            EdcbByteUtils.readUshort(res)

            val startPos = res.position()
            val structSize = EdcbByteUtils.readStructIntro(res)
            val info = EdcbRecFileInfo(
                id = EdcbByteUtils.readInt(res),
                recFilePath = EdcbByteUtils.readString(res),
                title = EdcbByteUtils.readString(res),
                startTime = EdcbByteUtils.readSystemTime(res),
                durationSec = EdcbByteUtils.readInt(res).let { if (it < 0) 0 else it },
                serviceName = EdcbByteUtils.readString(res),
                onid = EdcbByteUtils.readUshort(res),
                tsid = EdcbByteUtils.readUshort(res),
                sid = EdcbByteUtils.readUshort(res),
                eid = EdcbByteUtils.readUshort(res),
                drops = EdcbByteUtils.readLong(res),
                scrambles = EdcbByteUtils.readLong(res),
                recStatus = EdcbByteUtils.readInt(res),
                startTimeEpg = EdcbByteUtils.readSystemTime(res),
                comment = EdcbByteUtils.readString(res),
                programInfo = EdcbByteUtils.readString(res),
                errInfo = EdcbByteUtils.readString(res),
                protectFlag = EdcbByteUtils.readByte(res) != 0
            )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getReserves(): Result<List<EdcbReserveData>> = withContext(Dispatchers.IO) {
        val client = EdcbTcpClient(ip, port)
        val payload =
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(CMD_VER.toShort())
                .array()

        val res = client.sendCommand(2011, payload)
            ?: return@withContext Result.failure(Exception("Network Error"))
        try {
            EdcbByteUtils.readUshort(res)
            val list = EdcbByteUtils.readVector(
                res,
                res.limit()
            ) { buf, endPos -> EdcbByteUtils.readReserveData(buf, endPos) }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error in getReserves", e)
            Result.failure(e)
        }
    }

    suspend fun getAutoAddConditions(): Result<List<EdcbAutoAddData>> =
        withContext(Dispatchers.IO) {
            val client = EdcbTcpClient(ip, port)
            val payload =
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(CMD_VER.toShort())
                    .array()

            val res = client.sendCommand(2131, payload) ?: return@withContext Result.failure(
                Exception("Network Error")
            )
            try {
                EdcbByteUtils.readUshort(res)
                val list = EdcbByteUtils.readVector(
                    res,
                    res.limit()
                ) { buf, endPos -> EdcbByteUtils.readAutoAddData(buf, endPos) }
                Result.success(list)
            } catch (e: Exception) {
                Log.e(TAG, "Parse error in getAutoAddConditions", e)
                Result.failure(e)
            }
        }

    // ★追加: 単発予約の削除
    suspend fun sendDelReserve(reserveIds: List<Int>): Result<Boolean> {
        return try {
            val req = EdcbByteUtils.writeIntVector(reserveIds)
            val res = tcpClient.sendCommand(1014, req) // CMD_EPG_SRV_DEL_RESERVE
            if (res != null) {
                // 成功時は 1 が返る
                val status = EdcbByteUtils.readInt(res)
                if (status == 1) Result.success(true) else Result.failure(Exception("EDCB returned failure status: $status"))
            } else {
                Result.failure(Exception("Failed response from EDCB"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ★追加: 自動予約条件の削除
    suspend fun sendDelAutoAdd(dataIds: List<Int>): Result<Boolean> {
        return try {
            val req = EdcbByteUtils.writeIntVector(dataIds)
            val res = tcpClient.sendCommand(1033, req) // CMD_EPG_SRV_DEL_AUTO_ADD
            if (res != null) {
                val status = EdcbByteUtils.readInt(res)
                if (status == 1) Result.success(true) else Result.failure(Exception("EDCB returned failure status: $status"))
            } else {
                Result.failure(Exception("Failed response from EDCB"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ★ 修正: 単発予約の追加 (2013: CMD_EPG_SRV_ADD_RESERVE2) - 先頭にCMD_VERを付与
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAddReserve(reserveList: List<EdcbReserveData>): Result<Boolean> {
        return try {
            val vecBytes = EdcbByteUtils.writeReserveDataVector(reserveList)
            val req = ByteBuffer.allocate(2 + vecBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            req.putShort(CMD_VER.toShort())
            req.put(vecBytes)

            val res = tcpClient.sendCommand(2013, req.array())
            if (res != null) {
                val status = EdcbByteUtils.readInt(res)
                if (status == 1) Result.success(true) else Result.failure(Exception("EDCB returned failure status: $status"))
            } else {
                Result.failure(Exception("Failed response from EDCB"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ★ 修正: 単発予約の変更 (2015: CMD_EPG_SRV_CHG_RESERVE2) - 先頭にCMD_VERを付与
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendChgReserve(reserveList: List<EdcbReserveData>): Result<Boolean> {
        return try {
            val vecBytes = EdcbByteUtils.writeReserveDataVector(reserveList)
            val req = ByteBuffer.allocate(2 + vecBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            req.putShort(CMD_VER.toShort())
            req.put(vecBytes)

            val res = tcpClient.sendCommand(2015, req.array())
            if (res != null) {
                val status = EdcbByteUtils.readInt(res)
                if (status == 1) Result.success(true) else Result.failure(Exception("EDCB returned failure status: $status"))
            } else {
                Result.failure(Exception("Failed response from EDCB"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ★ 修正: 自動予約の追加 (2132: CMD_EPG_SRV_ADD_AUTO_ADD2) - 先頭にCMD_VERを付与
    suspend fun sendAddAutoAdd(dataList: List<EdcbAutoAddData>): Result<Boolean> {
        return try {
            val vecBytes = EdcbByteUtils.writeAutoAddDataVector(dataList)
            val req = ByteBuffer.allocate(2 + vecBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            req.putShort(CMD_VER.toShort())
            req.put(vecBytes)

            val res = tcpClient.sendCommand(2132, req.array())
            if (res != null) {
                val status = EdcbByteUtils.readInt(res)
                if (status == 1) Result.success(true) else Result.failure(Exception("EDCB returned failure status: $status"))
            } else {
                Result.failure(Exception("Failed response from EDCB"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ★ 修正: 自動予約の変更 (2134: CMD_EPG_SRV_CHG_AUTO_ADD2) - 先頭にCMD_VERを付与
    suspend fun sendChgAutoAdd(dataList: List<EdcbAutoAddData>): Result<Boolean> {
        return try {
            val vecBytes = EdcbByteUtils.writeAutoAddDataVector(dataList)
            val req = ByteBuffer.allocate(2 + vecBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            req.putShort(CMD_VER.toShort())
            req.put(vecBytes)

            val res = tcpClient.sendCommand(2134, req.array())
            if (res != null) {
                val status = EdcbByteUtils.readInt(res)
                if (status == 1) Result.success(true) else Result.failure(Exception("EDCB returned failure status: $status"))
            } else {
                Result.failure(Exception("Failed response from EDCB"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}