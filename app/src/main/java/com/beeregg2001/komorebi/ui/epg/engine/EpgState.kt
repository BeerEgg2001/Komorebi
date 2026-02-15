package com.beeregg2001.komorebi.ui.epg.engine

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextLayoutResult
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.epg.EpgDataConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private const val TAG = "EPG_STATE"

@RequiresApi(Build.VERSION_CODES.O)
class UiProgram(
    val program: EpgProgram,
    val topY: Float,
    val height: Float,
    val isEmpty: Boolean,
    val endTimeMs: Long
)

@RequiresApi(Build.VERSION_CODES.O)
class UiChannel(
    val wrapper: EpgChannelWrapper,
    val uiPrograms: List<UiProgram>
)

@RequiresApi(Build.VERSION_CODES.O)
@Stable
class EpgState(
    private val config: EpgConfig
) {
    var filledChannelWrappers by mutableStateOf<List<EpgChannelWrapper>>(emptyList())
        private set
    var uiChannels by mutableStateOf<List<UiChannel>>(emptyList())
        private set
    var baseTime by mutableStateOf(OffsetDateTime.now())
        private set
    var limitTime by mutableStateOf(OffsetDateTime.now())
        private set

    val hasData: Boolean get() = uiChannels.isNotEmpty()
    var isCalculating by mutableStateOf(false)
        private set
    var isInitialized by mutableStateOf(false)
        private set

    var focusedCol by mutableIntStateOf(0)
    var focusedMin by mutableIntStateOf(0)
    var currentFocusedProgram by mutableStateOf<EpgProgram?>(null)

    var targetScrollX by mutableFloatStateOf(0f)
    var targetScrollY by mutableFloatStateOf(0f)
    var targetAnimX by mutableFloatStateOf(0f)
    var targetAnimY by mutableFloatStateOf(0f)
    var targetAnimH by mutableFloatStateOf(config.hhPx)

    val textLayoutCache = mutableMapOf<String, TextLayoutResult>()
    var screenWidthPx by mutableFloatStateOf(0f)
    var screenHeightPx by mutableFloatStateOf(0f)

    private val maxScrollMinutes = 1440 * 14

    suspend fun updateData(newData: List<EpgChannelWrapper>, resetFocus: Boolean = false) {
        isCalculating = true
        withContext(Dispatchers.Default) {
            try {
                val now = OffsetDateTime.now()
                val newBaseTime = now.minusHours(2).truncatedTo(ChronoUnit.HOURS)
                val newLimitTime = newBaseTime.plusMinutes(maxScrollMinutes.toLong())

                val newUiChannels = newData.map { wrapper ->
                    val filled = EpgDataConverter.getFilledPrograms(wrapper.channel.id, wrapper.programs, newBaseTime, newLimitTime)
                    val uiProgs = filled.map { p ->
                        val (sOff, dur) = EpgDataConverter.calculateSafeOffsets(p, newBaseTime)
                        val topY = (sOff / 60f) * config.hhPx
                        val height = (dur / 60f) * config.hhPx
                        val isEmpty = p.title == "（番組情報なし）"
                        val endMs = try {
                            EpgDataConverter.safeParseTime(p.end_time, newBaseTime.plusMinutes(sOff.toLong() + dur.toLong())).toInstant().toEpochMilli()
                        } catch (e: Exception) { 0L }
                        UiProgram(p, topY, height, isEmpty, endMs)
                    }
                    UiChannel(wrapper.copy(programs = filled), uiProgs)
                }

                withContext(Dispatchers.Main) {
                    baseTime = newBaseTime
                    limitTime = newLimitTime
                    uiChannels = newUiChannels
                    filledChannelWrappers = newUiChannels.map { it.wrapper }
                    textLayoutCache.clear()

                    if (!isInitialized || resetFocus) {
                        jumpToNow()
                        isInitialized = true
                    } else {
                        if (uiChannels.isNotEmpty()) {
                            if (focusedCol >= uiChannels.size) {
                                focusedCol = uiChannels.size - 1
                            }
                            updatePositions(focusedCol, focusedMin)
                        }
                    }
                    isCalculating = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    uiChannels = emptyList()
                    filledChannelWrappers = emptyList()
                    isCalculating = false
                }
            }
        }
    }

    fun updateScreenSize(width: Float, height: Float) {
        if (width > 0 && height > 0) {
            screenWidthPx = width
            screenHeightPx = height
        }
    }

    fun jumpToNow() {
        jumpToTime(OffsetDateTime.now())
    }

    fun jumpToTime(targetTime: OffsetDateTime) {
        val targetMin = try {
            Duration.between(baseTime, targetTime).toMinutes().toInt().coerceIn(0, maxScrollMinutes)
        } catch (e: Exception) { 0 }

        Log.d(TAG, "jumpToTime: target=$targetTime, min=$targetMin")

        var bestCol = 0
        if (uiChannels.isNotEmpty()) {
            val targetY = (targetMin / 60f) * config.hhPx
            for (i in uiChannels.indices) {
                val channel = uiChannels[i]
                val hasProgram = channel.uiPrograms.any {
                    targetY >= it.topY && targetY < it.topY + it.height
                }
                if (hasProgram) {
                    bestCol = i
                    break
                }
            }
        }

        updatePositionsInternal(bestCol, targetMin, forceScroll = true)

        val targetY = (targetMin / 60f) * config.hhPx
        val desiredScrollY = -targetY
        val effectiveScreenHeight = if (screenHeightPx > 0) screenHeightPx else 1080f
        val visibleH = (effectiveScreenHeight - config.hhAreaPx).coerceAtLeast(100f)
        val maxScrollY = -((maxScrollMinutes / 60f) * config.hhPx + config.bPadPx - visibleH).coerceAtLeast(0f)

        targetScrollX = 0f
        targetScrollY = desiredScrollY.coerceIn(maxScrollY, 0f)
    }

    fun updatePositions(col: Int, min: Int) {
        updatePositionsInternal(col, min, forceScroll = false)
    }

    private fun updatePositionsInternal(col: Int, min: Int, forceScroll: Boolean) {
        if (uiChannels.isEmpty()) return

        val columns = uiChannels.size
        val safeCol = col.coerceIn(0, (columns - 1).coerceAtLeast(0))
        val safeMin = min.coerceIn(0, maxScrollMinutes)

        val channel = uiChannels.getOrNull(safeCol) ?: return

        val focusY = (safeMin / 60f) * config.hhPx
        val uiProg = channel.uiPrograms.find {
            focusY >= it.topY && focusY < it.topY + it.height
        }

        currentFocusedProgram = uiProg?.program

        targetAnimX = safeCol * config.cwPx
        if (uiProg != null) {
            targetAnimY = uiProg.topY
            targetAnimH = if (uiProg.isEmpty) uiProg.height else uiProg.height.coerceAtLeast(config.minExpHPx)
        } else {
            targetAnimY = focusY
            targetAnimH = 30f / 60f * config.hhPx
        }

        focusedCol = safeCol
        focusedMin = safeMin

        if (forceScroll) return

        val effectiveScreenWidth = if (screenWidthPx > 0) screenWidthPx else 1920f
        val effectiveScreenHeight = if (screenHeightPx > 0) screenHeightPx else 1080f

        val visibleW = (effectiveScreenWidth - config.twPx).coerceAtLeast(100f)
        val topOffset = config.hhAreaPx
        val visibleH = (effectiveScreenHeight - topOffset).coerceAtLeast(100f)

        var nextTargetX = targetScrollX
        if (targetAnimX < -targetScrollX) nextTargetX = -targetAnimX
        else if (targetAnimX + config.cwPx > -targetScrollX + visibleW) nextTargetX = -(targetAnimX + config.cwPx - visibleW)

        var nextTargetY = targetScrollY
        if (targetAnimY + targetAnimH > -targetScrollY + visibleH) nextTargetY = -(targetAnimY + targetAnimH - visibleH + config.sPadPx)
        if (targetAnimY < -targetScrollY) nextTargetY = -targetAnimY

        val maxScrollX = -(columns * config.cwPx - visibleW).coerceAtLeast(0f)
        val maxScrollY = -((maxScrollMinutes / 60f) * config.hhPx + config.bPadPx - visibleH).coerceAtLeast(0f)

        targetScrollX = nextTargetX.coerceIn(maxScrollX, 0f)
        targetScrollY = nextTargetY.coerceIn(maxScrollY, 0f)
    }
}