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

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 5000)
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 10000)
    private val tempArray = ByteArray(188 * 5000)

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

        var processedSize = nativeLib.popDataBuffer(handle, outputBuffer, length)

        if (processedSize <= 0) {
            val readCount = input.read(tempArray)
            if (readCount == -1) return C.RESULT_END_OF_INPUT

            if (readCount > 0) {
                inputBuffer.clear()
                inputBuffer.put(tempArray, 0, readCount)
                nativeLib.pushDataBuffer(handle, inputBuffer, readCount)
                processedSize = nativeLib.popDataBuffer(handle, outputBuffer, length)
            }
        }

        return if (processedSize > 0) {
            outputBuffer.position(0)
            val finalReadSize = Math.min(processedSize, length)
            outputBuffer.get(buffer, offset, finalReadSize)
            finalReadSize
        } else {
            0
        }
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