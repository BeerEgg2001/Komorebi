package com.beeregg2001.komorebi.data.api.edcb

import android.util.Log
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

class EdcbApi(private val ip: String, private val port: Int) {
    companion object {
        private const val TAG = "EdcbApi"
        const val CMD_EPG_SRV_ENUM_TUNER_PROCESS = 1066
        const val CMD_EPG_SRV_ENUM_SERVICE = 1021
        const val CMD_EPG_SRV_ENUM_PG_INFO_EX = 1029
        const val CMD_EPG_SRV_FILE_COPY2 = 2060
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

    suspend fun getEventInfos(services: List<EdcbServiceInfo>): Result<List<EdcbEventInfo>> {
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
            requestBuffer.putLong(0L)
            requestBuffer.putLong(Long.MAX_VALUE)
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

    // ★ 新規追加: 録画リスト取得 (軽量版)
    suspend fun getRecInfosBasic(): Result<List<EdcbRecFileInfo>> {
        return try {
            val payload =
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(CMD_VER.toShort())
                    .array()
            val res = tcpClient.sendCommand(CMD_EPG_SRV_ENUM_RECINFO_BASIC2, payload)
                ?: return Result.success(emptyList())
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
                    startTimeEpg = null,
                    comment = "",
                    programInfo = "",
                    errInfo = "",
                    protectFlag = false
                )
                val expectedEndPos = startPos + structSize
                if (expectedEndPos <= buf.limit()) buf.position(expectedEndPos)
                info
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ★ 新規追加: 録画詳細取得
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
}