package com.beeregg2001.komorebi.ui.epg.engine

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextLayoutResult
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.epg.EpgDataConverter
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@RequiresApi(Build.VERSION_CODES.O)
@Stable
class EpgState(
    private val config: EpgConfig
) {
    // --- データ ---
    var filledChannelWrappers by mutableStateOf<List<EpgChannelWrapper>>(emptyList())
        private set
    var baseTime by mutableStateOf(OffsetDateTime.now())
        private set
    var limitTime by mutableStateOf(OffsetDateTime.now())
        private set

    val hasData: Boolean
        get() = filledChannelWrappers.isNotEmpty()

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
     * データを更新し、初期位置を計算する
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun updateData(newData: List<EpgChannelWrapper>) {
        try {
            val now = OffsetDateTime.now()
            baseTime = now.minusHours(2).truncatedTo(ChronoUnit.HOURS)
            limitTime = baseTime.plusMinutes(maxScrollMinutes.toLong())

            filledChannelWrappers = newData.map { wrapper ->
                wrapper.copy(programs = EpgDataConverter.getFilledPrograms(wrapper.channel.id, wrapper.programs, baseTime, limitTime))
            }
            textLayoutCache.clear()

            // 初回ロード時（スクロール位置が未設定の場合）のみ位置合わせを行う
            if (targetScrollY == 0f) {
                val nowMin = getNowMinutes()

                // ★修正: 現在時刻の「00分」を基準にスクロール位置を決定する
                // 例: 14:25 の場合、14:00 の位置が画面最上部に来るようにする
                val justHourMin = (nowMin / 60) * 60

                // フォーカス計算用は「現在時刻」を維持（現在放送中の番組を選択するため）
                focusedMin = nowMin

                // スクロールY座標を00分基準で設定
                targetScrollY = -(justHourMin / 60f * config.hhPx)

                // フォーカス枠のアニメーション初期値は現在時刻ベース（後で updatePositions で補正される）
                targetAnimY = (nowMin / 60f * config.hhPx)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // エラー時は空リストで安全に動作させる
            filledChannelWrappers = emptyList()
        }
    }

    /**
     * 画面サイズを更新する
     * 0以下の不正な値が来た場合は無視するガードを追加
     */
    fun updateScreenSize(width: Float, height: Float) {
        if (width > 0 && height > 0) {
            screenWidthPx = width
            screenHeightPx = height
        }
    }

    fun getNowMinutes(): Int {
        val now = OffsetDateTime.now()
        // Duration計算時の例外ハンドリング
        return try {
            Duration.between(baseTime, now).toMinutes().toInt().coerceIn(0, maxScrollMinutes)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 指定位置へフォーカスを移動し、スクロール位置を計算する
     */
    fun updatePositions(col: Int, min: Int) {
        if (filledChannelWrappers.isEmpty()) return

        // 範囲外アクセス防止のためのガードを強化
        val columns = filledChannelWrappers.size
        val safeCol = col.coerceIn(0, (columns - 1).coerceAtLeast(0))
        val safeMin = min.coerceIn(0, maxScrollMinutes)

        // チャンネル取得時の安全策
        val channel = filledChannelWrappers.getOrNull(safeCol) ?: return

        // 時間計算時の安全策
        val focusTime = try {
            baseTime.plusMinutes(safeMin.toLong())
        } catch (e: Exception) {
            baseTime
        }

        // 番組検索
        val prog = channel.programs.find { p ->
            val s = EpgDataConverter.safeParseTime(p.start_time, baseTime)
            val e = EpgDataConverter.safeParseTime(p.end_time, s.plusMinutes(1))
            !focusTime.isBefore(s) && focusTime.isBefore(e)
        }
        currentFocusedProgram = prog

        val (sOff, dur) = prog?.let { EpgDataConverter.calculateSafeOffsets(it, baseTime) }
            ?: (safeMin.toFloat() to 30f)

        targetAnimX = safeCol * config.cwPx
        targetAnimY = (sOff / 60f) * config.hhPx
        targetAnimH = if (prog?.title == "（番組情報なし）") {
            (dur / 60f * config.hhPx)
        } else {
            (dur / 60f * config.hhPx).coerceAtLeast(config.minExpHPx)
        }

        // スクロール位置計算
        val visibleW = (screenWidthPx - config.twPx).coerceAtLeast(100f) // 最小幅保証
        val topOffset = config.hhAreaPx
        val visibleH = (screenHeightPx - topOffset).coerceAtLeast(100f) // 最小高さ保証

        var nextTargetX = targetScrollX
        if (targetAnimX < -targetScrollX) nextTargetX = -targetAnimX
        else if (targetAnimX + config.cwPx > -targetScrollX + visibleW) nextTargetX = -(targetAnimX + config.cwPx - visibleW)

        var nextTargetY = targetScrollY
        if (targetAnimY + targetAnimH > -targetScrollY + visibleH) nextTargetY = -(targetAnimY + targetAnimH - visibleH + config.sPadPx)
        if (targetAnimY < -targetScrollY) nextTargetY = -targetAnimY

        // スクロール範囲の制限
        val maxScrollX = -(columns * config.cwPx - visibleW).coerceAtLeast(0f)
        val maxScrollY = -((maxScrollMinutes / 60f) * config.hhPx + config.bPadPx - visibleH).coerceAtLeast(0f)

        targetScrollX = nextTargetX.coerceIn(maxScrollX, 0f)
        targetScrollY = nextTargetY.coerceIn(maxScrollY, 0f)

        focusedCol = safeCol
        focusedMin = safeMin
    }
}