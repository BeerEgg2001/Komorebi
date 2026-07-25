package com.beeregg2001.komorebi.ui.subtitle

import android.util.Log
import com.beeregg2001.komorebi.NativeLib

class NativeCaptionDecoder(
    private val nativeLib: NativeLib = NativeLib()
) : AutoCloseable {
    private var handle: Long = nativeLib.openCaptionDecoder()
    private var languages: List<NativeCaptionLanguage> = emptyList()

    @Synchronized
    fun decode(
        data: ByteArray,
        ptsMs: Long,
        renderCaptions: Boolean = true
    ): NativeCaptionCue? {
        val activeHandle = handle
        if (activeHandle == 0L) return null
        // Caption management data carries the language table. Keep feeding it while
        // subtitles are hidden, but skip statement packets to avoid background rendering.
        if (!renderCaptions && !AribCaptionData.isManagementPacket(data)) return null
        return try {
            val cue = nativeLib.decodeCaption(activeHandle, data, ptsMs)
            val detectedLanguages = nativeLib.getCaptionLanguageCodes(activeHandle)
                .mapIndexed { index, code ->
                    NativeCaptionLanguage(
                        id = index + 1,
                        iso6392Code = code.toIso6392Code()
                    )
                }
            if (detectedLanguages.isNotEmpty()) languages = detectedLanguages
            if (renderCaptions) cue else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode ARIB caption", e)
            null
        }
    }

    @Synchronized
    fun availableLanguages(): List<NativeCaptionLanguage> = languages

    @Synchronized
    fun switchLanguage(languageId: Int) {
        val activeHandle = handle
        if (activeHandle == 0L || languageId !in 1..2) return
        nativeLib.switchCaptionLanguage(activeHandle, languageId)
    }

    @Synchronized
    fun flush() {
        val activeHandle = handle
        if (activeHandle != 0L) nativeLib.flushCaptionDecoder(activeHandle)
    }

    @Synchronized
    fun reset(languageId: Int = 1) {
        val activeHandle = handle
        if (activeHandle != 0L) nativeLib.closeCaptionDecoder(activeHandle)
        handle = nativeLib.openCaptionDecoder()
        languages = emptyList()
        if (handle != 0L && languageId != 1) {
            nativeLib.switchCaptionLanguage(handle, languageId)
        }
    }

    @Synchronized
    override fun close() {
        val activeHandle = handle
        handle = 0L
        languages = emptyList()
        if (activeHandle != 0L) nativeLib.closeCaptionDecoder(activeHandle)
    }

    private fun Int.toIso6392Code(): String = buildString(3) {
        append(((this@toIso6392Code shr 16) and 0xff).toChar())
        append(((this@toIso6392Code shr 8) and 0xff).toChar())
        append((this@toIso6392Code and 0xff).toChar())
    }

    companion object {
        private const val TAG = "NativeCaptionDecoder"
    }
}
