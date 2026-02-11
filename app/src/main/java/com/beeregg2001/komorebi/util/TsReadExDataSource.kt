package com.beeregg2001.komorebi.util

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.beeregg2001.komorebi.NativeLib
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

@UnstableApi
class TsReadExDataSource(
    private val nativeLib: NativeLib,
    private val tsArgs: Array<String>
) : BaseDataSource(true) {

    private var handle: Long = 0
    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null

    // バッファサイズを大きく確保
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 5000)
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 10000)
    private val tempArray = ByteArray(188 * 5000)

    // エラー解消: 抽象メンバ getUri() の実装
    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        transferInitializing(dataSpec)

        handle = nativeLib.openFilter(tsArgs)

        val url = URL(dataSpec.uri.toString())
        connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            doInput = true
        }

        // Java 側の I/O 効率化
        inputStream = BufferedInputStream(connection?.inputStream, 128 * 1024)

        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val input = inputStream ?: return C.RESULT_END_OF_INPUT

        // 1. まず JNI の非同期キューからデータを取り出す
        var processedSize = nativeLib.popDataBuffer(handle, outputBuffer, length)

        // 2. キューが空なら Mirakurun から読み込んで供給し、再度取り出す
        if (processedSize <= 0) {
            val readCount = input.read(tempArray)
            if (readCount == -1) return C.RESULT_END_OF_INPUT

            if (readCount > 0) {
                inputBuffer.clear()
                inputBuffer.put(tempArray, 0, readCount)
                nativeLib.pushDataBuffer(handle, inputBuffer, readCount)

                // プッシュ直後に再度ポップ
                processedSize = nativeLib.popDataBuffer(handle, outputBuffer, length)
            }
        }

        // 3. 取得できたデータを ExoPlayer に渡す
        return if (processedSize > 0) {
            outputBuffer.position(0)
            val finalReadSize = Math.min(processedSize, length)
            outputBuffer.get(buffer, offset, finalReadSize)
            finalReadSize
        } else {
            // パケットがまだ準備できていない場合は 0 を返し、ExoPlayer に再度呼ばせる
            0
        }
    }

    override fun close() {
        transferEnded()
        try {
            inputStream?.close()
            connection?.disconnect()
        } finally {
            inputStream = null
            connection = null
            if (handle != 0L) {
                nativeLib.closeFilter(handle)
                handle = 0
            }
        }
    }
}