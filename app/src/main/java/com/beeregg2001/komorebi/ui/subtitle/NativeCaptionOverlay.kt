package com.beeregg2001.komorebi.ui.subtitle

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private const val TIMELINE_TICK_MS = 50L

@Composable
fun rememberNativeCaptionCue(
    events: Flow<NativeCaptionCue>,
    enabled: Boolean,
    resetKey: Any? = null,
    positionMs: () -> Long
): MutableState<NativeCaptionCue?> {
    val cueState = remember { mutableStateOf<NativeCaptionCue?>(null) }
    val timeline = remember { NativeCaptionTimeline() }
    val currentPositionMs = rememberUpdatedState(positionMs)
    LaunchedEffect(resetKey, enabled) {
        timeline.reset()
        cueState.value = null
    }
    LaunchedEffect(events, resetKey, enabled) {
        events.collect { cue ->
            if (enabled) timeline.offer(cue)
        }
    }
    LaunchedEffect(enabled, resetKey) {
        while (enabled) {
            cueState.value = timeline.advanceTo(currentPositionMs.value.invoke())
            delay(TIMELINE_TICK_MS)
        }
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
        cue.images.map { image ->
            image to image.bitmap.asImageBitmap()
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
