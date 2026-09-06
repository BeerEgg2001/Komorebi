@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.util.SparseArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsPayloadReader
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import java.io.ByteArrayOutputStream

/**
 * 字幕データを抽出し、native デコーダーへ渡す PayloadReader
 */
@UnstableApi
class DirectSubtitlePayloadReader(
    private val onSubtitleDataReceived: (Long, ByteArray) -> Unit
) : TsPayloadReader {
    private var timestampAdjuster: TimestampAdjuster? = null
    private val buffer = ByteArrayOutputStream()

    override fun init(
        adjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    ) {
        this.timestampAdjuster = adjuster
    }

    override fun seek() {
        buffer.reset()
    }

    override fun consume(data: ParsableByteArray, flags: Int) {
        val isStart = (flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0
        if (isStart && buffer.size() > 0) {
            parseAndSendBuffer()
            buffer.reset()
        }
        val bytesAvailable = data.bytesLeft()
        if (bytesAvailable > 0) {
            buffer.write(data.data, data.position, bytesAvailable)
            data.skipBytes(bytesAvailable)
        }
    }

    private fun parseAndSendBuffer() {
        val rawData = buffer.toByteArray()
        var id3StartIndex = -1
        for (i in 0 until rawData.size - 2) {
            if (rawData[i] == 0x49.toByte() && rawData[i + 1] == 0x44.toByte() && rawData[i + 2] == 0x33.toByte()) {
                id3StartIndex = i; break
            }
        }
        if (id3StartIndex == -1) return
        try {
            var offset = id3StartIndex + 10
            while (offset < rawData.size - 10) {
                val frameSize =
                    (rawData[offset + 4].toInt() and 0x7F shl 21) or (rawData[offset + 5].toInt() and 0x7F shl 14) or (rawData[offset + 6].toInt() and 0x7F shl 7) or (rawData[offset + 7].toInt() and 0x7F)
                offset += 10
                if (isPrivFrame(rawData, offset - 10)) {
                    var ownerEnd = offset
                    while (ownerEnd < offset + frameSize && ownerEnd < rawData.size && rawData[ownerEnd].toInt() != 0) ownerEnd++
                    if (containsAsciiIgnoreCase(rawData, offset, ownerEnd, "aribb24") ||
                        containsAsciiIgnoreCase(rawData, offset, ownerEnd, "B24")
                    ) {
                        val privateDataStart = ownerEnd + 1
                        val privateDataLength = frameSize - (privateDataStart - offset)
                        if (privateDataStart + privateDataLength <= rawData.size) {
                            val privateData = rawData.copyOfRange(
                                privateDataStart,
                                privateDataStart + privateDataLength
                            )
                            val currentPtsMs = ((timestampAdjuster?.lastAdjustedTimestampUs
                                ?: 0L) / 1000) + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS

                            onSubtitleDataReceived(currentPtsMs, privateData)
                        }
                    }
                }
                offset += frameSize
                if (frameSize <= 0) break
            }
        } catch (e: Exception) {
            android.util.Log.e("DirectSubtitle", "Parse error", e)
        }
    }

    private fun isPrivFrame(data: ByteArray, offset: Int): Boolean {
        return data[offset] == 'P'.code.toByte() &&
            data[offset + 1] == 'R'.code.toByte() &&
            data[offset + 2] == 'I'.code.toByte() &&
            data[offset + 3] == 'V'.code.toByte()
    }

    private fun containsAsciiIgnoreCase(
        data: ByteArray,
        start: Int,
        endExclusive: Int,
        needle: String
    ): Boolean {
        val needleLength = needle.length
        if (needleLength == 0 || endExclusive - start < needleLength) return false
        val lastStart = endExclusive - needleLength
        for (i in start..lastStart) {
            var matched = true
            for (j in 0 until needleLength) {
                if (toLowerAscii(data[i + j]) != needle[j].lowercaseChar().code) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    private fun toLowerAscii(value: Byte): Int {
        val charCode = value.toInt() and 0xff
        return if (charCode in 'A'.code..'Z'.code) charCode + 32 else charCode
    }
}

@UnstableApi
class DirectSubtitlePayloadReaderFactory(
    private val onSubtitleDataReceived: (Long, ByteArray) -> Unit
) : TsPayloadReader.Factory {
    private val defaultFactory = DefaultTsPayloadReaderFactory(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
    )

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> =
        defaultFactory.createInitialPayloadReaders()

    override fun createPayloadReader(
        streamType: Int,
        esInfo: TsPayloadReader.EsInfo
    ): TsPayloadReader? {
        if (streamType == 0x06 || streamType == 0x15) {
            return DirectSubtitlePayloadReader(onSubtitleDataReceived)
        }
        return defaultFactory.createPayloadReader(streamType, esInfo)
    }
}
