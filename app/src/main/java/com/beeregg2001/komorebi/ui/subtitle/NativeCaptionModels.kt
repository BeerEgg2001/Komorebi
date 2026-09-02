package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap

data class NativeCaptionCue(
    val ptsMs: Long,
    val durationMs: Long,
    val clearScreen: Boolean,
    val planeWidth: Int,
    val planeHeight: Int,
    val images: List<NativeCaptionImage>
)

data class NativeCaptionImage(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap
)

data class NativeCaptionLanguage(
    val id: Int,
    val iso6392Code: String
) {
    val displayName: String
        get() = when (iso6392Code) {
            "jpn" -> "日本語"
            "eng" -> "英語"
            "por" -> "ポルトガル語"
            "spa" -> "スペイン語"
            "tgl" -> "タガログ語"
            else -> iso6392Code.ifBlank { "第${id}言語" }
        }
}
