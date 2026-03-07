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
import java.io.IOException

@UnstableApi
class TsReadExDataSource(
    private val nativeLib: NativeLib,
    private val tsArgs: Array<String>
) : BaseDataSource(true) {

    private var handle: Long = 0
    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var opened = false

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 20000)
    private val tempArray = ByteArray(188 * 20000)
    // outputBuffer も同様に拡大（10000 -> 30000など）
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 30000)

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        transferInitializing(dataSpec)

        try {
            handle = nativeLib.openFilter(tsArgs)
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("Native library method 'openFilter' not found", e)
        } catch (e: Exception) {
            throw IOException("Failed to open native filter", e)
        }

        val url = URL(dataSpec.uri.toString())
        connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            doInput = true
        }

        val responseCode = connection?.responseCode ?: -1
        // ★修正: !in (スペースを削除)
        if (responseCode !in 200..299) {
            throw IOException("Server returned code $responseCode")
        }

        inputStream = BufferedInputStream(connection!!.inputStream)

        transferStarted(dataSpec)
        opened = true

        // ★修正: .toLong() を追加して型を Long に合わせる
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val input = inputStream ?: return C.RESULT_END_OF_INPUT

        var totalProcessedSize = 0

        // 要求された length を満たすまで、あるいは入力が切れるまでループする
        while (totalProcessedSize < length) {
            val remainingToFill = length - totalProcessedSize
            var processedSize = nativeLib.popDataBuffer(handle, outputBuffer, remainingToFill)

            if (processedSize <= 0) {
                // JNIにデータがないなら、元の入力（Stream）から取ってきて流し込む
                val readCount = input.read(tempArray)
                if (readCount == -1) {
                    return if (totalProcessedSize > 0) totalProcessedSize else C.RESULT_END_OF_INPUT
                }

                if (readCount > 0) {
                    inputBuffer.clear()
                    inputBuffer.put(tempArray, 0, readCount)
                    nativeLib.pushDataBuffer(handle, inputBuffer, readCount)
                    // 流し込んだ直後に再度 pop を試みる
                    continue
                } else {
                    break // 入力が一時的に空なら抜ける
                }
            }

            // 取れたデータを buffer に詰め替える
            outputBuffer.position(0)
            outputBuffer.get(buffer, offset + totalProcessedSize, processedSize)
            totalProcessedSize += processedSize
        }

        return totalProcessedSize
    }

    override fun close() {
        if (opened) {
            transferEnded()
            opened = false
        }

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