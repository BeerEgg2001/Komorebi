package com.beeregg2001.komorebi.data.api.edcb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EdcbTcpClient(private val ip: String, private val port: Int) {
    companion object {
        private const val TAG = "EdcbTcpClient"

        // ★ 修正: アプリ全体からEDCBへのTCP通信を順番に処理し、Socketの競合によるConnection Errorを防ぐ
        private val tcpMutex = Mutex()
    }

    suspend fun sendCommand(cmd: Int, data: ByteArray = ByteArray(0)): ByteBuffer? =
        withContext(Dispatchers.IO) {
            // ★ 修正: EDCBへの通信全体をロック
            tcpMutex.withLock {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    // タイムアウトを少し長めに設定して安全性を高める
                    socket.soTimeout = 10000
                    socket.connect(InetSocketAddress(ip, port), 4000)

                    val outputStream = socket.getOutputStream()
                    val inputStream = socket.getInputStream()

                    // ヘッダー作成 (8 bytes)
                    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    header.putInt(cmd)
                    header.putInt(data.size)

                    // データ送信
                    outputStream.write(header.array())
                    if (data.isNotEmpty()) {
                        outputStream.write(data)
                    }
                    outputStream.flush()

                    // レスポンスヘッダー受信
                    val resHeaderBytes = readExactBytes(inputStream, 8)
                        ?: throw Exception("Failed to read response header")

                    val resHeaderBuf =
                        ByteBuffer.wrap(resHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val ret = resHeaderBuf.getInt()
                    val size = resHeaderBuf.getInt()

                    Log.d(TAG, "Response Header - Ret: $ret, Size: $size")

                    if (ret != 1) {
                        Log.e(TAG, "Command failed. Return code: $ret")
                        return@withContext null
                    }

                    if (size == 0) {
                        return@withContext ByteBuffer.allocate(0)
                    }

                    // ペイロード受信
                    val payloadBytes = readExactBytes(inputStream, size)
                        ?: throw Exception("Connection closed prematurely by EDCB. Expected $size bytes.")

                    return@withContext ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)

                } catch (e: Exception) {
                    Log.e(TAG, "TCP Communication Error to $ip:$port", e)
                    return@withContext null
                } finally {
                    try {
                        socket?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to close socket", e)
                    }
                }
            }
        }

    private fun readExactBytes(inputStream: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(buffer, totalRead, length - totalRead)
            if (read == -1) {
                // EOF（予期せぬ切断）
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