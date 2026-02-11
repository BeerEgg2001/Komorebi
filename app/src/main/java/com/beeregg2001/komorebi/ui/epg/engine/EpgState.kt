package com.beeregg2001.komorebi.ui.epg.engine

import android.os.Build
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

// 描画用の事前計算済みデータクラス
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
    // --- データ ---
    var filledChannelWrappers by mutableStateOf<List<EpgChannelWrapper>>(emptyList())
        private set
    var uiChannels by mutableStateOf<List<UiChannel>>(emptyList())
        private set
    var baseTime by mutableStateOf(OffsetDateTime.now())
        private set
    var limitTime by mutableStateOf(OffsetDateTime.now())
        private set

    val hasData: Boolean
        get() = uiChannels.isNotEmpty()

    // バックグラウンドでの計算中フラグ
    var isCalculating by mutableStateOf(false)
        private set

    // --- 状態 ---
    var focusedCol by mutableIntStateOf(0)
    var focusedMin by mutableIntStateOf(0)
    var currentFocusedProgram by mutableStateOf<EpgProgram?>(null)

    // --- アニメーションターゲット値 ---
    var targetScrollX by mutableFloatStateOf(0f)
    var targetScrollY by mutableFloatStateOf(0f)
    var targetAnimX by mutableFloatStateOf(0f)
    var targetAnimY by mutableFloatStateOf(0f)
    var targetAnimH by mutableFloatStateOf(config.hhPx)

    // --- レイアウトキャッシュ ---
    val textLayoutCache = mutableMapOf<String, TextLayoutResult>()

    // 画面サイズ
    var screenWidthPx by mutableFloatStateOf(0f)
    var screenHeightPx by mutableFloatStateOf(0f)

    private val maxScrollMinutes = 1440 * 14 // 2週間

    /**
     * バックグラウンドスレッドで重い座標計算と日時パースを一括で行う
     * ★修正: resetFocusフラグを追加し、タブ切り替え時にフォーカスをリセットできるようにしました
     */
    suspend fun updateData(newData: List<EpgChannelWrapper>, resetFocus: Boolean = false) {
        isCalculating = true // 計算開始
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

                    // ★修正: 初回読み込み、またはタブ切り替え(resetFocus)の時に位置をリセット
                    if (targetScrollY == 0f || resetFocus) {
                        val nowMin = getNowMinutes()
                        val justHourMin = (nowMin / 60) * 60

                        targetScrollX = 0f
                        targetScrollY = -(justHourMin / 60f * config.hhPx)

                        // 一番左(0)かつ現在時刻でフォーカスをセット
                        updatePositions(0, nowMin)
                    } else {
                        // データ更新（裏でのポーリングなど）の場合は現在のフォーカスを維持
                        if (uiChannels.isNotEmpty()) {
                            updatePositions(focusedCol, focusedMin)
                        }
                    }
                    isCalculating = false // 計算終了
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

    fun getNowMinutes(): Int {
        val now = OffsetDateTime.now()
        return try {
            Duration.between(baseTime, now).toMinutes().toInt().coerceIn(0, maxScrollMinutes)
        } catch (e: Exception) { 0 }
    }

    fun updatePositions(col: Int, min: Int) {
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

        val visibleW = (screenWidthPx - config.twPx).coerceAtLeast(100f)
        val topOffset = config.hhAreaPx
        val visibleH = (screenHeightPx - topOffset).coerceAtLeast(100f)

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

        focusedCol = safeCol
        focusedMin = safeMin
    }
}