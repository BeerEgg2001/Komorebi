package com.beeregg2001.komorebi.data.api.edcb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
            Log.d(TAG, "Requesting EPG_SRV_ENUM_PG_INFO_EX for ${services.size} services...")

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

                    // =========================================
                    // 1. ShortEventInfo
                    // =========================================
                    val startShortPos = eventBuf.position()
                    val shortInfoSize = EdcbByteUtils.readInt(eventBuf)
                    if (shortInfoSize > 4) {
                        eventName = EdcbByteUtils.readString(eventBuf)
                        eventText = EdcbByteUtils.readString(eventBuf)
                    }
                    val endShortPos = startShortPos + shortInfoSize
                    if (endShortPos <= eventBuf.limit()) eventBuf.position(endShortPos)

                    // =========================================
                    // 2. ExtendedEventInfo
                    // =========================================
                    val startExtPos = eventBuf.position()
                    val extInfoSize = EdcbByteUtils.readInt(eventBuf)
                    if (extInfoSize > 4) {
                        extendedText = EdcbByteUtils.readString(eventBuf)
                        detailMap = parseProgramExtendedText(extendedText)
                    }
                    val endExtPos = startExtPos + extInfoSize
                    if (endExtPos <= eventBuf.limit()) eventBuf.position(endExtPos)

                    // =========================================
                    // 3. ContentInfo (ジャンルのパース)
                    // =========================================
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

                            // ★神修正: ByteBufferがLittleEndianで読んでしまった2バイトを
                            // ビッグエンディアンの並びにひっくり返して（Python版の動作を完全再現）、正しいジャンルIDを復元する！
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

                    // =========================================
                    // 4~7. 以降のブロックスキップ
                    // =========================================
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
                        eOnid,
                        eTsid,
                        eSid,
                        eid,
                        if (hasStartTime) startTimeStr else null,
                        if (hasDuration) durationVal else 0,
                        eventName,
                        eventText,
                        freeCaFlag,
                        contentList,
                        extendedText,
                        detailMap
                    )
                }
                events.addAll(eventList)

                val endOuterPos = startOuterPos + outerStructSize
                if (endOuterPos <= buf.limit()) buf.position(endOuterPos)
            }

            Log.i(TAG, "🎉 Successfully parsed ${events.size} events from EDCB!")
            Result.success(events)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPG_SRV_ENUM_PG_INFO_EX", e)
            Result.failure(e)
        }
    }

    suspend fun fetchFiles(fileNames: List<String>): List<EdcbFileData>? =
        withContext(Dispatchers.IO) {
            try {
                val requestData = EdcbByteUtils.writeStringVectorWithVersion(CMD_VER, fileNames)
                val responseBuffer = tcpClient.sendCommand(CMD_EPG_SRV_FILE_COPY2, requestData)

                if (responseBuffer == null || responseBuffer.remaining() < 2) return@withContext null

                val resVersion = EdcbByteUtils.readUshort(responseBuffer)
                val list = EdcbByteUtils.readVector(responseBuffer) { buffer ->
                    EdcbByteUtils.readFileData(buffer)
                }.filterNotNull()

                return@withContext list
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching files via 2060", e)
                return@withContext null
            }
        }
}