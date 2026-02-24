package com.beeregg2001.komorebi.ui.live

import android.util.Base64
import android.util.SparseArray
import android.webkit.WebView
import androidx.compose.runtime.MutableState
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import com.beeregg2001.komorebi.extractor.ts.DefaultTsPayloadReaderFactory
import com.beeregg2001.komorebi.extractor.ts.TsPayloadReader
import com.beeregg2001.komorebi.extractor.ts.PesReader
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import java.io.ByteArrayOutputStream

@UnstableApi
class DirectSubtitlePayloadReader(
    private val webViewRef: MutableState<WebView?>,
    private val isSubtitleEnabledState: MutableState<Boolean>
) : TsPayloadReader {
    private lateinit var timestampAdjuster: TimestampAdjuster
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
        if (!isSubtitleEnabledState.value) return
        val isStart = (flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0
        if (isStart && buffer.size() > 0) {
            parseAndSendBuffer()
            buffer.reset()
        }
        val bytesAvailable = data.bytesLeft()
        if (bytesAvailable > 0) {
            val chunk = ByteArray(bytesAvailable)
            data.readBytes(chunk, 0, bytesAvailable)
            buffer.write(chunk)
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
                val frameId = String(rawData, offset, 4)
                val frameSize =
                    (rawData[offset + 4].toInt() and 0x7F shl 21) or (rawData[offset + 5].toInt() and 0x7F shl 14) or (rawData[offset + 6].toInt() and 0x7F shl 7) or (rawData[offset + 7].toInt() and 0x7F)
                offset += 10
                if (frameId == "PRIV") {
                    var ownerEnd = offset
                    while (ownerEnd < offset + frameSize && ownerEnd < rawData.size && rawData[ownerEnd].toInt() != 0) ownerEnd++
                    val ownerString = String(rawData, offset, ownerEnd - offset)
                    if (ownerString.contains("aribb24", true) || ownerString.contains("B24", true)) {
                        val privateDataStart = ownerEnd + 1
                        val privateDataLength = frameSize - (privateDataStart - offset)
                        if (privateDataStart + privateDataLength <= rawData.size) {
                            val privateData = rawData.copyOfRange(privateDataStart, privateDataStart + privateDataLength)
                            val base64Data = Base64.encodeToString(privateData, Base64.NO_WRAP)
                            val currentPtsMs = (timestampAdjuster.lastAdjustedTimestampUs / 1000) + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS
                            webViewRef.value?.post {
                                webViewRef.value?.evaluateJavascript(
                                    "if(window.receiveSubtitleData){ window.receiveSubtitleData($currentPtsMs, '$base64Data'); }",
                                    null
                                )
                            }
                        }
                    }
                }
                offset += frameSize
            }
        } catch (e: Exception) {
            android.util.Log.e("LiveSubtitle", "Parse error", e)
        }
    }
}

@UnstableApi
class DirectSubtitlePayloadReaderFactory(
    private val webViewRef: MutableState<WebView?>,
    private val isSubtitleEnabledState: MutableState<Boolean>
) : TsPayloadReader.Factory {
    private val defaultFactory = DefaultTsPayloadReaderFactory(
        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
    )

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = defaultFactory.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        if (streamType == 0x06 || streamType == 0x15) {
            return DirectSubtitlePayloadReader(webViewRef, isSubtitleEnabledState)
        }
        return defaultFactory.createPayloadReader(streamType, esInfo)
    }
}