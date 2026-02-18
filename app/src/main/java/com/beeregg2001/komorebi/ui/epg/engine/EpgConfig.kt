package com.beeregg2001.komorebi.ui.epg.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beeregg2001.komorebi.ui.theme.NotoSansJP

class EpgConfig(density: Density) {
    // サイズ (px)
    val cwPx = with(density) { 130.dp.toPx() } // チャンネル幅
    val hhPx = with(density) { 75.dp.toPx() }  // 1時間の高さ
    val twPx = with(density) { 60.dp.toPx() }  // 時刻軸の幅
    val hhAreaPx = with(density) { 45.dp.toPx() } // 日付ヘッダーの高さ
    val tabHeightPx = with(density) { 48.dp.toPx() } // タブの高さ
    val minExpHPx = with(density) { 140.dp.toPx() } // フォーカス時の最小高さ
    val bPadPx = with(density) { 120.dp.toPx() } // 下部余白
    val sPadPx = with(density) { 32.dp.toPx() }  // スクロール余白

    // 色
    val colorBg = Color.Black
    val colorHeaderBg = Color(0xFF111111)
    val colorTimeHourEven = Color(0xFF2E2424)
    val colorTimeHourOdd = Color(0xFF242E24)
    val colorTimeHourNight = Color(0xFF24242E)
    val colorGridLine = Color(0xFF444444)
    val colorFocusBg = Color(0xFF383838)
    val colorFocusBorder = Color.White
    val colorCurrentTimeLine = Color.Red
    val colorProgramNormal = Color(0xFF222222)
    val colorProgramPast = Color(0xFF161616)
    val colorProgramEmpty = Color(0xFF0C0C0C)

    // ★追加: 予約関連の色
    val colorReserveBorder = Color(0xFFFF5252)        // 通常予約（赤）
    val colorReserveBorderPartial = Color(0xFFFFCA28) // 一部のみ（黄）
    val colorReserveBgDuplicated = Color(0xFF4A1818)  // 重複時の背景（薄い赤）

    // テキストスタイル
    val styleTitle = TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
    val styleDesc = TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Normal, lineHeight = 13.sp)
    val styleChNum = TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
    val styleChName = TextStyle(fontFamily = NotoSansJP, color = Color.LightGray, fontSize = 10.sp)
    val styleTime = TextStyle(fontFamily = NotoSansJP, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val styleAmPm = TextStyle(fontFamily = NotoSansJP, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val styleDateLabel = TextStyle(fontFamily = NotoSansJP, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}