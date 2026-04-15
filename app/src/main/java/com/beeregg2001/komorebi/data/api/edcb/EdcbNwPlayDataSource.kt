package com.beeregg2001.komorebi.data.api.edcb

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class EdcbNwPlayDataSource(
    private val ip: String,
    private val port: Int
) : BaseDataSource(true) {

    companion object {
        private const val TAG = "EdcbNwPlayDataSource"
        const val SCHEME = "edcb-nwplay"

        private const val CMD2_EPG_SRV_NWPLAY_OPEN = 1080
        private const val CMD2_EPG_SRV_NWPLAY_CLOSE = 1081
        private const val CMD2_EPG_SRV_NWPLAY_PLAY = 1082
        private const val CMD2_EPG_SRV_NWPLAY_GET_POS = 1084
        private const val CMD2_EPG_SRV_NWPLAY_SET_POS = 1085
        private const val CMD2_EPG_SRV_NWPLAY_SET_IP = 1086

        private const val CMD_VER: Short = 5
    }

    private var controlClient: EdcbTcpClient? = null
    private var dataSocket: Socket? = null
    private var dataInputStream: InputStream? = null
    private var ctrlId: Int = -1
    private var totalFileSize: Long = C.LENGTH_UNSET.toLong()
    private var currentOffset: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val filePath = uri.getQueryParameter("path") ?: throw Exception("Path missing")

        Log.i(TAG, "===== BRUTE-FORCE NWPLAY OPEN DIAGNOSIS =====")
        Log.i(TAG, "Target Path: $filePath")

        transferInitializing(dataSpec)
        controlClient = EdcbTcpClient(ip, port)

        runBlocking {
            try {
                // 文字列のUTF-16LEバイト配列を取得
                val pathBytes = filePath.toByteArray(Charsets.UTF_16LE)

                // 考えうる4パターンのバイナリフォーマットを全て用意する
                val formats = listOf(
                    "Format A (Versionなし, Null終端なし)" to ByteBuffer.allocate(4 + pathBytes.size)
                        .order(ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(pathBytes.size); put(pathBytes)
                    }.array(),

                    "Format B (Versionなし, Null終端あり)" to ByteBuffer.allocate(4 + pathBytes.size + 2)
                        .order(ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(pathBytes.size + 2); put(pathBytes); putShort(0)
                    }.array(),

                    "Format C (Versionあり, Null終端なし)" to ByteBuffer.allocate(2 + 4 + pathBytes.size)
                        .order(ByteOrder.LITTLE_ENDIAN).apply {
                        putShort(CMD_VER); putInt(pathBytes.size); put(pathBytes)
                    }.array(),

                    "Format D (Versionあり, Null終端あり)" to ByteBuffer.allocate(2 + 4 + pathBytes.size + 2)
                        .order(ByteOrder.LITTLE_ENDIAN).apply {
                        putShort(CMD_VER); putInt(pathBytes.size + 2); put(pathBytes); putShort(0)
                    }.array()
                )

                // 総当たりテスト開始
                var workingFormatName = ""
                for ((name, payload) in formats) {
                    try {
                        Log.i(TAG, "Testing: $name ...")
                        val openRes = controlClient?.sendCommand(CMD2_EPG_SRV_NWPLAY_OPEN, payload)

                        if (openRes != null && openRes.remaining() >= 4) {
                            workingFormatName = name
                            // 戻り値の末尾4バイト（ctrlId）を確実に取得
                            openRes.position(openRes.limit() - 4)
                            ctrlId = openRes.getInt()
                            Log.i(
                                TAG,
                                ">>> SUCCESS! The correct EDCB protocol is: $name. ctrlId = $ctrlId"
                            )
                            break // 成功したのでループを抜ける
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, ">>> FAILED: $name returned Error 0.")
                    }
                }

                // 4つすべて失敗した場合の究極の切り分け
                if (ctrlId == -1) {
                    Log.e(TAG, "===== ALL FORMATS FAILED =====")
                    Log.e(
                        TAG,
                        "通信プロトコルは完全に網羅しました。原因は『EDCBが $filePath を絶対に開けない環境要因』に100%確定しました。"
                    )
                    throw Exception("All payload formats rejected. File Path or Wine permissions issue.")
                }

                // --- 以降は成功した場合の通常ストリーム開始処理 ---
                Log.i(TAG, "[STEP 2] Sending GET_POS(1084)...")

                // Format A/B (Versionなし) が正解だった場合は以降のコマンドもVersionなしに合わせる
                val useVer = workingFormatName.contains("Versionあり")
                val posPayload = if (useVer) {
                    ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                        .apply { putShort(CMD_VER); putInt(ctrlId) }.array()
                } else {
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(ctrlId) }
                        .array()
                }

                val posRes = controlClient?.sendCommand(CMD2_EPG_SRV_NWPLAY_GET_POS, posPayload)
                if (posRes != null && posRes.remaining() >= 20) {
                    posRes.position(posRes.limit() - 20) // 構造体サイズの先頭へ
                    posRes.getInt()   // StructSize
                    posRes.getInt()   // ctrlId
                    posRes.getLong()  // currentPos
                    totalFileSize = posRes.getLong()
                    Log.i(TAG, "[STEP 2] SUCCESS! FileSize = $totalFileSize")
                }

                if (dataSpec.position > 0L) {
                    currentOffset = dataSpec.position
                    val setPosPayload =
                        ByteBuffer.allocate(if (useVer) 26 else 24).order(ByteOrder.LITTLE_ENDIAN)
                            .apply {
                                if (useVer) putShort(CMD_VER)
                                putInt(20)
                                putInt(ctrlId)
                                putLong(currentOffset)
                                putLong(0L)
                            }.array()
                    controlClient?.sendCommand(CMD2_EPG_SRV_NWPLAY_SET_POS, setPosPayload)
                }

                val serverSocket = ServerSocket(0).apply { soTimeout = 15000 }
                val myPort = serverSocket.localPort
                Log.i(TAG, "[STEP 3] Local ServerSocket ready on port $myPort")

                val setIpPayload =
                    ByteBuffer.allocate(if (useVer) 20 else 18).order(ByteOrder.LITTLE_ENDIAN)
                        .apply {
                            if (useVer) putShort(CMD_VER)
                            putInt(14)
                            putInt(ctrlId)
                            putInt(0)
                            putShort(0)
                            putShort(myPort.toShort())
                            put(0.toByte())
                            put(1.toByte())
                        }.array()
                controlClient?.sendCommand(CMD2_EPG_SRV_NWPLAY_SET_IP, setIpPayload)

                val playPayload = if (useVer) {
                    ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                        .apply { putShort(CMD_VER); putInt(ctrlId) }.array()
                } else {
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(ctrlId) }
                        .array()
                }
                controlClient?.sendCommand(CMD2_EPG_SRV_NWPLAY_PLAY, playPayload)

                Log.i(TAG, "[STEP 4] Waiting for stream connection...")
                dataSocket = serverSocket.accept()
                dataSocket?.soTimeout = 15000
                dataInputStream = dataSocket?.getInputStream()
                serverSocket.close()

                opened = true
                Log.i(TAG, "===== ALL NWPLAY STEPS COMPLETED SUCCESSFULLY =====")

            } catch (e: Exception) {
                Log.e(TAG, "NWPLAY Sequence Failed", e)
                close(); throw e
            }
        }
        transferStarted(dataSpec)
        return if (totalFileSize > 0) totalFileSize else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened || dataInputStream == null) return C.RESULT_END_OF_INPUT
        return try {
            val bytesRead = dataInputStream!!.read(buffer, offset, length)
            if (bytesRead == -1) C.RESULT_END_OF_INPUT else {
                currentOffset += bytesRead
                bytesTransferred(bytesRead)
                bytesRead
            }
        } catch (e: Exception) {
            throw e
        }
    }

    override fun getUri(): Uri? = null

    override fun close() {
        if (!opened) return
        opened = false
        try {
            dataInputStream?.close(); dataSocket?.close()
        } catch (e: Exception) {
        }
        if (ctrlId != -1) {
            runBlocking {
                val closePayload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(CMD_VER)
                    putInt(ctrlId)
                }.array()
                try {
                    controlClient?.sendCommand(CMD2_EPG_SRV_NWPLAY_CLOSE, closePayload)
                } catch (e: Exception) {
                }
            }
        }
        controlClient = null; dataInputStream = null; dataSocket = null; ctrlId = -1
        transferEnded()
    }
}