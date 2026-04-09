package com.beeregg2001.komorebi.data.api.edcb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EDCB (EpgTimerSrv) の TCPバイナリプロトコル (CtrlCmd) クライアント。
 */
class EdcbTcpClient(
    private val ip: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 10000
) {
    companion object {
        private const val TAG = "EdcbTcpClient"

        // ★ 修正: EDCBの正常完了コードは 1
        const val CMD_SUCCESS = 1
    }

    suspend fun sendCommand(commandId: Int, sendData: ByteArray = ByteArray(0)): ByteBuffer? =
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.soTimeout = readTimeoutMs
                socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)

                val outStream = socket.getOutputStream()
                val inStream = socket.getInputStream()

                // 1. ヘッダーの作成 (コマンドID(4byte) + データサイズ(4byte) = 8byte)
                val headerBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                headerBuffer.putInt(commandId)
                headerBuffer.putInt(sendData.size)

                // 2. ヘッダーとデータを送信
                outStream.write(headerBuffer.array())
                if (sendData.isNotEmpty()) {
                    outStream.write(sendData)
                }
                outStream.flush()

                // 3. レスポンスヘッダーを受信 (8byte)
                val resHeaderBytes = readExactBytes(inStream, 8) ?: return@withContext null
                val resHeader = ByteBuffer.wrap(resHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)

                val retCode = resHeader.int
                val resSize = resHeader.int

                Log.d(TAG, "Response Header - Ret: $retCode, Size: $resSize")

                if (retCode != CMD_SUCCESS) {
                    Log.e(TAG, "EDCB returned error or unknown command: $retCode")
                    return@withContext null
                }

                if (resSize < 0 || resSize > 100_000_000) {
                    Log.e(
                        TAG,
                        "Abnormal response size detected: $resSize bytes. Aborting to prevent OOM."
                    )
                    return@withContext null
                }

                // 4. レスポンスデータを受信
                if (resSize > 0) {
                    val resDataBytes = readExactBytes(inStream, resSize) ?: return@withContext null
                    return@withContext ByteBuffer.wrap(resDataBytes).order(ByteOrder.LITTLE_ENDIAN)
                }

                return@withContext ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

            } catch (e: Exception) {
                Log.e(TAG, "TCP Communication Error to $ip:$port", e)
                return@withContext null
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) { /* ignore */
                }
            }
        }

    private fun readExactBytes(inStream: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = inStream.read(buffer, totalRead, length - totalRead)
            if (read == -1) {
                Log.e(
                    TAG,
                    "Connection closed prematurely by EDCB. Expected $length, got $totalRead"
                )
                return null
            }
            totalRead += read
        }
        return buffer
    }
}