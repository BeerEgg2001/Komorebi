package com.beeregg2001.komorebi.ui.subtitle

import android.util.Log
import com.beeregg2001.komorebi.NativeLib

class NativeCaptionDecoder(
    private val nativeLib: NativeLib = NativeLib()
) : AutoCloseable {
    private var handle: Long = nativeLib.openCaptionDecoder()
    private var languages: List<NativeCaptionLanguage> = emptyList()
    private var lastPtsMs: Long? = null
    private var lastManagementData: ByteArray? = null

    @Synchronized
    fun decode(
        data: ByteArray,
        ptsMs: Long,
        renderCaptions: Boolean = true
    ): NativeCaptionCue? {
        val activeHandle = handle
        if (activeHandle == 0L) return null
        return try {
            if (lastPtsMs?.let { ptsMs < it } == true) {
                nativeLib.flushCaptionDecoder(activeHandle)
            }
            lastPtsMs = ptsMs
            val cue = nativeLib.decodeCaption(activeHandle, data, ptsMs)
            // 管理データが言語表を更新したときだけ JNI 問い合わせを行う。
            val managementChanged = AribCaptionData.isManagementPacket(data) &&
                lastManagementData?.contentEquals(data) != true
            if (managementChanged) {
                val detectedLanguages = nativeLib.getCaptionLanguageCodes(activeHandle)
                    .mapIndexed { index, code ->
                        NativeCaptionLanguage(
                            id = index + 1,
                            iso6392Code = code.toIso6392Code()
                        )
                }
                if (detectedLanguages.isNotEmpty()) languages = detectedLanguages
                lastManagementData = data.copyOf()
            }
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
        lastPtsMs = null
    }

    @Synchronized
    fun flush() {
        val activeHandle = handle
        if (activeHandle != 0L) nativeLib.flushCaptionDecoder(activeHandle)
        lastPtsMs = null
        lastManagementData = null
    }

    @Synchronized
    fun reset(languageId: Int = 1) {
        val activeHandle = handle
        if (activeHandle != 0L) nativeLib.closeCaptionDecoder(activeHandle)
        handle = nativeLib.openCaptionDecoder()
        languages = emptyList()
        lastPtsMs = null
        lastManagementData = null
        if (handle != 0L && languageId != 1) {
            nativeLib.switchCaptionLanguage(handle, languageId)
        }
    }

    @Synchronized
    override fun close() {
        val activeHandle = handle
        handle = 0L
        languages = emptyList()
        lastPtsMs = null
        lastManagementData = null
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
