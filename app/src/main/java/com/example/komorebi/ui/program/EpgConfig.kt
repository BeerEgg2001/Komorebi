package com.example.komorebi.ui.program

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class EpgConfig(
    val dpPerMinute: Float = 1.3f,
    val hourHeight: Dp = (60 * 1.3f).dp,
    val channelWidth: Dp = 180.dp,
    val timeColumnWidth: Dp = 55.dp,
    val headerHeight: Dp = 48.dp
)

// 全体で参照できるように CompositionLocal を作成
val LocalEpgConfig = staticCompositionLocalOf { EpgConfig() }