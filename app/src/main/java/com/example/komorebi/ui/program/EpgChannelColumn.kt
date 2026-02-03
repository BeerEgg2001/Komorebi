package com.example.komorebi.ui.program

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.example.komorebi.data.model.EpgChannelWrapper
import com.example.komorebi.data.model.EpgProgram
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgChannelColumn(
    channelWrapper: EpgChannelWrapper,
    baseTime: OffsetDateTime,
    channelIndex: Int,
    tabFocusRequester: FocusRequester,
    firstCellFocusRequester: FocusRequester,
    onProgramClick: (EpgProgram) -> Unit,
    vScrollPos: Int,
    screenHeightPx: Float,
    now: OffsetDateTime = OffsetDateTime.now(),
    lastFocusedId: String?,
    onFocusProgram: (String) -> Unit
) {
    // CompositionLocal から設定を取得
    val config = LocalEpgConfig.current

    Box(
        modifier = Modifier
            .width(config.channelWidth)
            .height(config.hourHeight * 24)
    ) {
        channelWrapper.programs.forEach { program ->
            val startTime = remember(program.start_time) { OffsetDateTime.parse(program.start_time) }

            // ViewModel 側で計算ロジックを共通化している想定ですが、
            // ここでは描画範囲内（画面内）にあるかどうかを判定します
            val startMinutes = java.time.Duration.between(baseTime, startTime).toMinutes()
            val topDp = (startMinutes * config.dpPerMinute).dp
            val heightDp = ((program.duration / 60) * config.dpPerMinute).dp

            // 画面外のセルは描画しない（パフォーマンス最適化）
            // 判定用に少しバッファ（上下 100dp）を持たせる
            val topPx = topDp.value * 2.75f // 概算。正確には density が必要ですが判定用なので
            if (topDp.value + heightDp.value > (vScrollPos / 3) - 100 &&
                topDp.value < (vScrollPos / 3) + (screenHeightPx / 3) + 100) {

                // --- フォーカスロジックの判定 ---

                // 1. 初回フォーカス対象（1番目のチャンネルかつ現在放送中付近）
                val isInitialFocusTarget = channelIndex == 0 &&
                        now.isAfter(startTime.minusMinutes(5)) &&
                        now.isBefore(startTime.plusSeconds(program.duration.toLong()))

                // 2. 上キーでタブに戻れるか（画面上部に位置しているか）
                // vScrollPos（現在のスクロール位置）とセルの上端を比較
                val isAtTopVisibleArea = (topDp.value * 3) <= vScrollPos + 30

                ProgramCell(
                    program = program,
                    baseTime = baseTime,
                    canGoUpToTab = isAtTopVisibleArea,
                    // 必要に応じて FocusRequester を割り当て
                    focusRequester = if (isInitialFocusTarget) firstCellFocusRequester else null,
                    tabFocusRequester = tabFocusRequester,
                    onProgramClick = onProgramClick,
                    vScrollPos = vScrollPos,
                    // ★ 自分が前回フォーカスされていた番組かどうかを判定して渡す
                    isLastFocused = (program.id == lastFocusedId),
                    // ★ フォーカスが当たったことを親（Grid）に報告する
                    onFocused = { onFocusProgram(it) }
                )
            }
        }
    }
}