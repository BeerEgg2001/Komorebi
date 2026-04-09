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
        // ★ 追加: ファイル取得コマンド
        const val CMD_EPG_SRV_FILE_COPY = 1060
    }

    private val tcpClient = EdcbTcpClient(ip, port)

    suspend fun checkConnection(): Result<Boolean> = withContext(Dispatchers.Default) {
        try {
            val responseBuffer = tcpClient.sendCommand(CMD_EPG_SRV_ENUM_TUNER_PROCESS)
            if (responseBuffer == null) Result.failure(Exception("Ping Failed"))
            else Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getServices(): Result<List<EdcbServiceInfo>> = withContext(Dispatchers.Default) {
        try {
            val responseBuffer = tcpClient.sendCommand(CMD_EPG_SRV_ENUM_SERVICE)
                ?: return@withContext Result.failure(Exception("Failed response"))
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

    /**
     * ★ 修正: ミリ秒単位の開始・終了時刻を引数で受け取るように変更
     * 省略された場合は全期間を取得します。
     */
    suspend fun getEventInfos(
        services: List<EdcbServiceInfo>,
        startTimeMillis: Long? = null,
        endTimeMillis: Long? = null
    ): Result<List<EdcbEventInfo>> = withContext(Dispatchers.Default) {
        if (services.isEmpty()) return@withContext Result.success(emptyList())

        try {
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

            // 時刻が指定されていればJST(+9時間)に補正してFILETIME化、なければ無制限
            val jstOffset = 9 * 3600 * 1000L
            val fileTimeStart =
                if (startTimeMillis != null) EdcbByteUtils.dateTimeToFileTime(startTimeMillis + jstOffset) else 0L
            val fileTimeEnd =
                if (endTimeMillis != null) EdcbByteUtils.dateTimeToFileTime(endTimeMillis + jstOffset) else Long.MAX_VALUE

            requestBuffer.putLong(fileTimeStart)
            requestBuffer.putLong(fileTimeEnd)

            val responseBuffer =
                tcpClient.sendCommand(CMD_EPG_SRV_ENUM_PG_INFO_EX, requestBuffer.array())
                    ?: return@withContext Result.failure(Exception("Failed response from EDCB"))

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

    /**
     * EDCBサーバーから指定したファイルをバイナリで取得します
     */
    suspend fun fetchFile(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📂 [EdcbLogo] Fetching file from EDCB: $fileName")
            val requestData = EdcbByteUtils.writeString(fileName)
            val responseBuffer = tcpClient.sendCommand(CMD_EPG_SRV_FILE_COPY, requestData)

            if (responseBuffer == null) {
                Log.e(TAG, "❌ [EdcbLogo] Fetch failed: responseBuffer is null for $fileName")
                return@withContext null
            }
            if (responseBuffer.remaining() == 0) {
                Log.w(TAG, "⚠️ [EdcbLogo] Fetch empty: 0 bytes returned for $fileName (File might not exist)")
                return@withContext null
            }

            // レスポンスのバッファ全体がファイルデータです
            val size = responseBuffer.remaining()
            val bytes = ByteArray(size)
            responseBuffer.get(bytes)

            Log.i(TAG, "✅ [EdcbLogo] Fetch success: $fileName ($size bytes)")
            return@withContext bytes
        } catch (e: Exception) {
            Log.e(TAG, "❌ [EdcbLogo] Error fetching file: $fileName", e)
            null
        }
    }
}