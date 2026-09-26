package com.beeregg2001.komorebi.ui.video.smb.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException
import java.io.InterruptedIOException

@UnstableApi
class SmbDataSource(
    private val cifsContext: CIFSContext
) : BaseDataSource(/* isNetwork = */ true) {

    private var file: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    // ★ 新規追加: 通信速度を爆増させるための内部巨大バッファ (2MB)
    private val INTERNAL_BUFFER_SIZE = 4 * 1024 * 1024
    private val internalBuffer = ByteArray(INTERNAL_BUFFER_SIZE)
    private var internalBufferLength = 0
    private var internalBufferPosition = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        try {
            val smbPath = uri.toString()
            val smbFile = SmbFile(smbPath, cifsContext)
            file = SmbRandomAccessFile(smbFile, "r")

            file?.seek(dataSpec.position)

            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                file!!.length() - dataSpec.position
            } else {
                dataSpec.length
            }

            // シークや新しく開かれた際は、古いバッファをリセットする
            internalBufferLength = 0
            internalBufferPosition = 0

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            handleException(e)
            throw SmbDataSourceException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // ★ 変更: 内部バッファが空になった時だけ、NASから一気に2MBをフェッチする
        if (internalBufferPosition >= internalBufferLength) {
            val bytesToFetch = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                INTERNAL_BUFFER_SIZE.toLong()
            } else {
                minOf(bytesRemaining, INTERNAL_BUFFER_SIZE.toLong())
            }.toInt()

            internalBufferLength = try {
                file?.read(internalBuffer, 0, bytesToFetch) ?: -1
            } catch (e: Exception) {
                handleException(e)
                throw SmbDataSourceException(e)
            }

            internalBufferPosition = 0

            if (internalBufferLength == -1) {
                return C.RESULT_END_OF_INPUT
            }
        }

        // 内部バッファのデータを、ExoPlayerが要求した分(length)だけ切り取って渡す（ノータイム）
        val bytesToCopy = minOf(length, internalBufferLength - internalBufferPosition)
        System.arraycopy(internalBuffer, internalBufferPosition, buffer, offset, bytesToCopy)

        internalBufferPosition += bytesToCopy

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesToCopy
        }
        bytesTransferred(bytesToCopy)

        return bytesToCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } catch (e: Exception) {
            handleException(e)
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun handleException(e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is InterruptedException || cause is InterruptedIOException) {
                throw InterruptedIOException(e.message).apply { initCause(e) }
            }
            cause = cause.cause
        }
        throw SmbDataSourceException(e)
    }

    class SmbDataSourceException(cause: Throwable) : IOException(cause)
}