package com.beeregg2001.komorebi.util

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.beeregg2001.komorebi.NativeLib
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class TsReadExDataSource(
    private val nativeLib: NativeLib,
    var tsArgs: Array<String>
) : BaseDataSource(true) {

    private var handle: Long = 0
    private var connection: HttpURLConnection? = null
    private var edcbSocket: Socket? = null

    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var opened = false

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 20000)
    private val tempArray = ByteArray(188 * 20000)
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 30000)

    companion object {
        private const val CMD_EPG_SRV_RELAY_VIEW_STREAM = 301
        private const val CMD_EPG_SRV_NWTV_ID_SET_CH = 1073
        private const val CMD_EPG_SRV_NWTV_ID_CLOSE = 1074
        private const val CMD_SUCCESS = 1
        private const val TAG = "TsReadExDataSource"
        private const val NWTV_ID = 500
    }

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        transferInitializing(dataSpec)

        try {
            handle = nativeLib.openFilter(tsArgs)
        } catch (e: Exception) {
            throw IOException("Failed to open native filter", e)
        }

        if (dataSpec.uri.scheme == "edcb") {
            openEdcbStream(dataSpec.uri)
        } else {
            openHttpStream(dataSpec.uri)
        }

        transferStarted(dataSpec)
        opened = true
        return C.LENGTH_UNSET.toLong()
    }

    private fun openHttpStream(uri: Uri) {
        val url = URL(uri.toString())
        connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; doInput = true
        }
        val responseCode = connection?.responseCode ?: -1
        if (responseCode !in 200..299) throw IOException("Server returned code $responseCode")
        inputStream = BufferedInputStream(connection!!.inputStream)
    }

    private fun openEdcbStream(uri: Uri) {
        val ip = uri.host ?: throw IOException("Host not found")
        val port = if (uri.port != -1) uri.port else 4510
        val onid = uri.getQueryParameter("onid")?.toIntOrNull() ?: 0
        val tsid = uri.getQueryParameter("tsid")?.toIntOrNull() ?: 0
        val sid = uri.getQueryParameter("sid")?.toIntOrNull() ?: 0

        var targetProcessId = 0
        val startTime = System.currentTimeMillis()

        cleanupEdcbSession(ip, port)

        while (System.currentTimeMillis() - startTime < 10000) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 4000
                    socket.connect(InetSocketAddress(ip, port), 2000)

                    val body = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
                    body.putInt(26)
                    body.putInt(1)
                    body.putShort(onid.toShort())
                    body.putShort(tsid.toShort())
                    body.putShort(sid.toShort())
                    body.putInt(1)
                    body.putInt(NWTV_ID)
                    body.putInt(2)

                    sendEdcbCommand(
                        socket.getOutputStream(),
                        CMD_EPG_SRV_NWTV_ID_SET_CH,
                        body.array()
                    )

                    val (ret, size) = readEdcbResponseHeader(socket.getInputStream())
                    if (ret == CMD_SUCCESS && size >= 4) {
                        val resData = readExactBytes(socket.getInputStream(), size)
                        if (resData != null) {
                            targetProcessId =
                                ByteBuffer.wrap(resData).order(ByteOrder.LITTLE_ENDIAN).getInt()
                            if (targetProcessId != 0) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SetCh fail: ${e.message}")
            }
            Thread.sleep(1000)
        }

        if (targetProcessId == 0) throw IOException("EDCB SetCh failed (Tuner could not start)")

        val relayStartTime = System.currentTimeMillis()
        var relaySocket: Socket? = null
        var relayConnected = false

        while (System.currentTimeMillis() - relayStartTime < 10000) {
            try {
                val s = Socket()
                s.soTimeout = 15000
                s.connect(InetSocketAddress(ip, port), 3000)

                val relayReq =
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(targetProcessId)
                        .array()
                sendEdcbCommand(s.getOutputStream(), CMD_EPG_SRV_RELAY_VIEW_STREAM, relayReq)

                val (retRelay, _) = readEdcbResponseHeader(s.getInputStream())
                if (retRelay == CMD_SUCCESS) {
                    relaySocket = s
                    relayConnected = true
                    break
                } else {
                    s.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay attempt fail: ${e.message}")
            }
            Thread.sleep(1000)
        }

        if (!relayConnected || relaySocket == null) {
            cleanupEdcbSession(ip, port)
            throw IOException("EDCB Relay failed.")
        }

        this.edcbSocket = relaySocket
        Log.i(TAG, "EDCB Stream Success! ProcessID: $targetProcessId")
        this.inputStream = BufferedInputStream(edcbSocket!!.getInputStream(), 188 * 30000)
    }

    // ★ 修正: メインスレッドでNetworkアクセスしてクラッシュするのを防ぐため、Threadに分離
    private fun cleanupEdcbSession(ip: String, port: Int) {
        Thread {
            try {
                Socket().use { s ->
                    s.soTimeout = 2000
                    s.connect(InetSocketAddress(ip, port), 1500)
                    val closeReq =
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(NWTV_ID)
                            .array()
                    sendEdcbCommand(s.getOutputStream(), CMD_EPG_SRV_NWTV_ID_CLOSE, closeReq)
                    readEdcbResponseHeader(s.getInputStream())
                    Log.d(TAG, "EDCB Tuner released successfully (NWTV_ID_CLOSE sent to $ip:$port)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release EDCB Tuner: ${e.message}")
            }
        }.start()
    }

    private fun sendEdcbCommand(outStream: OutputStream, cmd: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd)
        header.putInt(data.size)
        outStream.write(header.array())
        if (data.isNotEmpty()) outStream.write(data)
        outStream.flush()
    }

    private fun readEdcbResponseHeader(ins: InputStream): Pair<Int, Int> {
        val header = readExactBytes(ins, 8) ?: throw IOException("EDCB Header missing")
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return Pair(buf.getInt(), buf.getInt())
    }

    private fun readExactBytes(ins: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = ins.read(buffer, totalRead, length - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return buffer
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val input = inputStream ?: return C.RESULT_END_OF_INPUT
        var total = 0
        while (total < length) {
            val processed = nativeLib.popDataBuffer(handle, outputBuffer, length - total)
            if (processed <= 0) {
                val readCount = input.read(tempArray)
                if (readCount == -1) return if (total > 0) total else C.RESULT_END_OF_INPUT
                if (readCount > 0) {
                    inputBuffer.clear(); inputBuffer.put(tempArray, 0, readCount)
                    nativeLib.pushDataBuffer(handle, inputBuffer, readCount)
                    continue
                } else break
            }
            outputBuffer.position(0); outputBuffer.get(buffer, offset + total, processed)
            total += processed
        }
        return total
    }

    override fun close() {
        if (opened) {
            transferEnded(); opened = false
        }
        try {
            uri?.let {
                if (it.scheme == "edcb") {
                    val ip = it.host
                    val port = if (it.port != -1) it.port else 4510
                    if (ip != null) {
                        cleanupEdcbSession(ip, port)
                    }
                }
            }
            inputStream?.close()
            connection?.disconnect()
            edcbSocket?.close()
        } finally {
            inputStream = null; connection = null; edcbSocket = null
            if (handle != 0L) {
                nativeLib.closeFilter(handle); handle = 0L
            }
        }
    }
}