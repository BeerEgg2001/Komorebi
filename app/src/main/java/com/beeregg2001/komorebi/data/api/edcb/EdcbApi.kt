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

data class EdcbEventInfo(
    val onid: Int, val tsid: Int, val sid: Int, val eid: Int,
    val startTime: String?, val durationSec: Int,
    val eventName: String, val eventText: String, val freeCaFlag: Int
)

class EdcbApi(private val ip: String, private val port: Int) {
    companion object {
        private const val TAG = "EdcbApi"
        const val CMD_EPG_SRV_ENUM_TUNER_PROCESS = 1066
        const val CMD_EPG_SRV_ENUM_SERVICE = 1021
        const val CMD_EPG_SRV_ENUM_PG_INFO_EX = 1029
        const val CMD_EPG_SRV_FILE_COPY2 = 2060
        const val CMD_VER = 5
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

    // ★ お客様が提供してくださったパース成功コード（完全無変更）
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

                    val startShortPos = eventBuf.position()
                    val nextFieldSize = EdcbByteUtils.readInt(eventBuf)
                    if (nextFieldSize > 4) {
                        eventBuf.position(eventBuf.position() - 4)
                        EdcbByteUtils.readStructIntro(eventBuf)
                        eventName = EdcbByteUtils.readString(eventBuf)
                        eventText = EdcbByteUtils.readString(eventBuf)
                    }
                    val endShortPos = startShortPos + nextFieldSize
                    if (endShortPos <= eventBuf.limit()) eventBuf.position(endShortPos)

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
                        0
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

    // ==========================================
    // ★ ロゴファイル取得(2060コマンド)用の追加メソッド
    // ==========================================
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