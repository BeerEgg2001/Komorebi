package com.beeregg2001.komorebi.ui.epg.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.ui.theme.KomorebiColors

// ★ 修正: hideSubChannels をコンストラクタの引数に追加
class EpgConfig(
    density: Density,
    colors: KomorebiColors,
    val screenWidthPx: Float,
    val screenHeightPx: Float, // ★ 画面の高さ
    val columnCount: Int = 7,
    val fontSizeScale: Float = 1.0f,
    val visibleHours: Int = 6, // ★ 表示時間数
    val hideSubChannels: Boolean = false
) {
    // サイズ (px)
    val twPx = with(density) { 60.dp.toPx() } // 左端の時刻カラム幅
    val cwPx = (screenWidthPx - twPx) / columnCount // 動的チャンネル幅

    val hhAreaPx = with(density) { 45.dp.toPx() }
    val tabHeightPx = with(density) { 48.dp.toPx() }
    val minExpHPx = with(density) { 140.dp.toPx() }
    val bPadPx = with(density) { 120.dp.toPx() }
    val sPadPx = with(density) { 32.dp.toPx() }

    // ★ 追加: 画面の利用可能な高さから1時間あたりの高さを逆算
    val availableHeightPx = screenHeightPx - tabHeightPx - hhAreaPx
    val hhPx = availableHeightPx / visibleHours

    // --- 色のテーマ化 ---
    val colorBg = Color.Transparent
    val colorHeaderBg = colors.surface.copy(alpha = 0.95f)

    // ★ 修正: ハードコードされた色を廃止し、テーマの surface 色にほんのり色味を乗せて合成する
    val colorTimeHourEven = Color(0xFFFF5252).copy(alpha = if (colors.isDark) 0.05f else 0.08f)
        .compositeOver(colors.surface.copy(alpha = 0.85f))
    val colorTimeHourOdd = Color(0xFF4CAF50).copy(alpha = if (colors.isDark) 0.05f else 0.08f)
        .compositeOver(colors.surface.copy(alpha = 0.85f))
    val colorTimeHourNight = Color(0xFF448AFF).copy(alpha = if (colors.isDark) 0.05f else 0.08f)
        .compositeOver(colors.surface.copy(alpha = 0.85f))

    val colorGridLine = colors.textPrimary.copy(alpha = 0.1f)
    val colorFocusBg = colors.textPrimary.copy(alpha = 0.2f).compositeOver(colors.background)
    val colorFocusBorder = colors.accent

    val colorCurrentTimeLine = Color.Red

    val colorProgramNormal = colors.surface
    val colorProgramPast = colors.background
    val colorProgramEmpty = colors.background.copy(alpha = 0.8f)

    val colorReserveBorder = Color(0xFFFF5252)
    val colorReserveBorderPartial = Color(0xFFFFCA28)
    val colorReserveBgDuplicated = if (colors.isDark) Color(0xFF4A1818) else Color(0xFFFFEBEE)

    val colorTextPrimary = colors.textPrimary
    val colorTextSecondary = colors.textSecondary
    val colorTextPast = colors.textSecondary.copy(alpha = 0.5f)

    // --- テキストスタイル ---
    val styleTitle = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textPrimary,
        fontSize = (11 * fontSizeScale).sp,
        fontWeight = FontWeight.Bold,
        lineHeight = (14 * fontSizeScale).sp
    )
    val styleDesc = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textSecondary,
        fontSize = (10 * fontSizeScale).sp,
        fontWeight = FontWeight.Normal,
        lineHeight = (13 * fontSizeScale).sp
    )
    val styleChNum = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Black
    )
    val styleChName = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textSecondary,
        fontSize = (10 * fontSizeScale).sp
    )
    val styleTime = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
    val styleAmPm = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textSecondary.copy(alpha = 0.8f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
    val styleDateLabel = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
}