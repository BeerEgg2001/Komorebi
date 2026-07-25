package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

private const val MAX_AUTO_HIDE_DURATION_MS = 60_000L
private const val INDEFINITE_AUTO_HIDE_DURATION_MS = 5_000L
private const val UNKNOWN_AUTO_HIDE_DURATION_MS = 5_000L
private const val AUTO_HIDE_TICK_MS = 250L

@Composable
fun rememberNativeCaptionCue(
    events: Flow<NativeCaptionCue>,
    enabled: Boolean,
    resetKey: Any? = null,
    clockRunning: Boolean = true
): MutableState<NativeCaptionCue?> {
    val cueState = remember { mutableStateOf<NativeCaptionCue?>(null) }
    val currentClockRunning = rememberUpdatedState(clockRunning)
    LaunchedEffect(resetKey) {
        cueState.value = null
    }
    LaunchedEffect(events, enabled, resetKey) {
        if (!enabled) {
            cueState.value = null
            return@LaunchedEffect
        }
        events.collect { cue ->
            cueState.value = if (cue.clearScreen || cue.images.isEmpty()) null else cue
        }
    }
    LaunchedEffect(cueState.value, enabled) {
        val cue = cueState.value ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        val duration = when (cue.durationMs) {
            -1L -> INDEFINITE_AUTO_HIDE_DURATION_MS
            in 1..MAX_AUTO_HIDE_DURATION_MS -> cue.durationMs
            else -> UNKNOWN_AUTO_HIDE_DURATION_MS
        }
        var remainingMs = duration
        while (remainingMs > 0 && cueState.value === cue) {
            if (!currentClockRunning.value) {
                snapshotFlow { currentClockRunning.value }
                    .filter { it }
                    .first()
                continue
            }
            val tickMs = minOf(remainingMs, AUTO_HIDE_TICK_MS)
            delay(tickMs)
            if (currentClockRunning.value) {
                remainingMs -= tickMs
            }
        }
        if (cueState.value === cue) cueState.value = null
    }
    return cueState
}

@Composable
fun NativeCaptionOverlay(
    cue: NativeCaptionCue?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible || cue == null) return

    val bitmaps = remember(cue) {
        cue.images.mapNotNull { image ->
            image.toBitmap()?.let { image to it.asImageBitmap() }
        }
    }

    Canvas(modifier = modifier) {
        val scaleX = size.width / cue.planeWidth.coerceAtLeast(1).toFloat()
        val scaleY = size.height / cue.planeHeight.coerceAtLeast(1).toFloat()
        bitmaps.forEach { (image, bitmap) ->
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(
                    x = (image.x * scaleX).toInt(),
                    y = (image.y * scaleY).toInt()
                ),
                dstSize = IntSize(
                    width = (image.width * scaleX).toInt().coerceAtLeast(1),
                    height = (image.height * scaleY).toInt().coerceAtLeast(1)
                )
            )
        }
    }
}

private fun NativeCaptionImage.toBitmap(): Bitmap? {
    if (width <= 0 || height <= 0 || rgba.isEmpty()) return null
    val pixels = IntArray(width * height)
    var out = 0
    for (row in 0 until height) {
        var offset = row * stride
        for (col in 0 until width) {
            if (offset + 3 >= rgba.size) return null
            val r = rgba[offset].toInt() and 0xff
            val g = rgba[offset + 1].toInt() and 0xff
            val b = rgba[offset + 2].toInt() and 0xff
            val a = rgba[offset + 3].toInt() and 0xff
            pixels[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            offset += 4
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
