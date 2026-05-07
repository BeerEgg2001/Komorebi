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

// ★ 修正: 画面幅、列数、フォントサイズスケールを引数に追加
class EpgConfig(
    density: Density,
    colors: KomorebiColors,
    val screenWidthPx: Float,
    val columnCount: Int = 7,
    val fontSizeScale: Float = 1.0f,
    val hideSubChannels: Boolean = false
) {
    // サイズ (px)
    val twPx = with(density) { 60.dp.toPx() } // 時間軸(縦軸)の幅
    // ★ 修正: 画面幅から左の時刻幅(twPx)を引いた残り幅を、設定された列数(columnCount)で均等割
    val cwPx = (screenWidthPx - twPx) / columnCount

    val hhPx = with(density) { 75.dp.toPx() }
    val hhAreaPx = with(density) { 45.dp.toPx() }
    val tabHeightPx = with(density) { 48.dp.toPx() }
    val minExpHPx = with(density) { 140.dp.toPx() }
    val bPadPx = with(density) { 120.dp.toPx() }
    val sPadPx = with(density) { 32.dp.toPx() }

    // --- 色のテーマ化 ---
    val colorBg = Color.Transparent
    val colorHeaderBg = colors.surface.copy(alpha = 0.95f)

    // 時間連動テーマへの最適化合成カラー
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

    // --- テキストスタイル (★ 修正: fontSizeScale を適用) ---
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