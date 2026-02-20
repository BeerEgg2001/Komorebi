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

class EpgConfig(density: Density, colors: KomorebiColors) {
    // サイズ (px)
    val cwPx = with(density) { 130.dp.toPx() }
    val hhPx = with(density) { 75.dp.toPx() }
    val twPx = with(density) { 60.dp.toPx() }
    val hhAreaPx = with(density) { 45.dp.toPx() }
    val tabHeightPx = with(density) { 48.dp.toPx() }
    val minExpHPx = with(density) { 140.dp.toPx() }
    val bPadPx = with(density) { 120.dp.toPx() }
    val sPadPx = with(density) { 32.dp.toPx() }

    // --- 色のテーマ化 ---
    // ★修正: 番組表自体の背景を透明にして、MainRootScreenの光を通す
    val colorBg = Color.Transparent
    val colorHeaderBg = colors.surface.copy(alpha = 0.95f) // ヘッダーは少しだけ透けさせる

    val colorTimeHourEven = if (colors.isDark) Color(0xFF2E2424) else Color(0xFFFDEFEF)
    val colorTimeHourOdd = if (colors.isDark) Color(0xFF242E24) else Color(0xFFEFFDEE)
    val colorTimeHourNight = if (colors.isDark) Color(0xFF24242E) else Color(0xFFEEF1FD)

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
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 14.sp
    )
    val styleDesc = TextStyle(
        fontFamily = NotoSansJP,
        color = colors.textSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 13.sp
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
        fontSize = 10.sp
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