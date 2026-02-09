package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.tv.material3.MaterialTheme.typography
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// 最適化のためのデータ構造
@Immutable
data class EpgSlotState(
    val time: OffsetDateTime,
    val isSelectable: Boolean,
    val baseColor: Color,
    val globalIndex: Int
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EpgJumpMenu(
    dates: List<OffsetDateTime>,
    onSelect: (OffsetDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS) }
    val fullTimeSlots = remember { (0..23).toList() }

    // 全スロットの情報を事前に計算（getTimeSlotColorから@Composableを外したのでここで呼べるようになります）
    val gridData = remember(dates, now) {
        dates.mapIndexed { dIdx, date ->
            fullTimeSlots.map { hour ->
                val slotTime = date.withHour(hour).truncatedTo(ChronoUnit.HOURS)
                EpgSlotState(
                    time = slotTime,
                    isSelectable = !slotTime.isBefore(now),
                    baseColor = getTimeSlotColor(hour), // エラー解消箇所
                    globalIndex = (dIdx * 24) + hour
                )
            }
        }
    }

    // フォーカスされているインデックスを管理
    var globalFocusedIndex by remember { mutableIntStateOf(-1) }

    val slotHeight = 13.dp
    val columnWidth = 85.dp

    // 2次元FocusRequester
    val focusRequesters = remember(dates.size) {
        List(dates.size) { List(24) { FocusRequester() } }
    }

    BackHandler(enabled = true) { onDismiss() }

    // 初期フォーカス
    LaunchedEffect(Unit) {
        delay(50)
        var focused = false
        for (dIdx in gridData.indices) {
            for (tIdx in 0..23) {
                if (gridData[dIdx][tIdx].isSelectable) {
                    focusRequesters[dIdx][tIdx].requestFocus()
                    focused = true
                    break
                }
            }
            if (focused) break
        }
        if (!focused && dates.isNotEmpty()) {
            focusRequesters[0][0].requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .focusGroup(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .wrapContentHeight()
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF111111)),
            border = Border(BorderStroke(1.dp, Color(0xFF444444)))
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "日時指定ジャンプ",
                    style = typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row {
                    // --- 1. 時刻ラベル列 ---
                    Column(horizontalAlignment = Alignment.End) {
                        Box(modifier = Modifier.width(60.dp).height(35.dp))
                        fullTimeSlots.forEach { hour ->
                            TimeLabelCell(hour, slotHeight)
                        }
                    }

                    // --- 2. 日付・スロット列 ---
                    gridData.forEachIndexed { dIdx, daySlots ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HeaderCell(dates[dIdx], columnWidth)

                            daySlots.forEachIndexed { tIdx, slot ->
                                // ハイライト判定
                                val isHighlighted = globalFocusedIndex != -1 &&
                                        slot.globalIndex >= globalFocusedIndex &&
                                        slot.globalIndex < globalFocusedIndex + 3

                                Surface(
                                    enabled = slot.isSelectable,
                                    onClick = { onSelect(slot.time) },
                                    modifier = Modifier
                                        .width(columnWidth)
                                        .height(slotHeight)
                                        .focusRequester(focusRequesters[dIdx][tIdx])
                                        .focusProperties {
                                            if (tIdx == 23 && dIdx < dates.size - 1) {
                                                down = focusRequesters[dIdx + 1][0]
                                            }
                                            if (tIdx == 0 && dIdx > 0) {
                                                up = focusRequesters[dIdx - 1][23]
                                            }
                                        }
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                globalFocusedIndex = slot.globalIndex
                                            }
                                        },
                                    shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (isHighlighted) Color(0xFFFFFF00) else slot.baseColor,
                                        focusedContainerColor = Color(0xFFFFFF00),
                                        disabledContainerColor = slot.baseColor.copy(alpha = 0.1f)
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        border = Border(BorderStroke(0.5.dp, Color.Black.copy(0.3f))),
                                        focusedBorder = Border(BorderStroke(2.dp, Color.White)),
                                        disabledBorder = Border(BorderStroke(0.5.dp, Color.Transparent))
                                    )
                                ) {
                                    Box(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeLabelCell(hour: Int, height: Dp) {
    Box(
        modifier = Modifier
            .height(height)
            .width(60.dp)
            .padding(end = 8.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        val label = when {
            hour == 0 -> "AM 0"
            hour == 12 -> "PM 0"
            hour % 3 == 0 -> "${hour % 12}"
            else -> ""
        }
        if (label.isNotEmpty()) {
            Text(
                label,
                fontSize = 10.sp,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HeaderCell(date: OffsetDateTime, width: Dp) {
    val isSunday = date.dayOfWeek.value == 7
    val isSaturday = date.dayOfWeek.value == 6
    Column(
        modifier = Modifier
            .width(width)
            .height(35.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = date.format(DateTimeFormatter.ofPattern("M/d", Locale.JAPANESE)),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = date.format(DateTimeFormatter.ofPattern("(E)", Locale.JAPANESE)),
            fontSize = 10.sp,
            color = when {
                isSunday -> Color(0xFFFF5252)
                isSaturday -> Color(0xFF448AFF)
                else -> Color.LightGray
            }
        )
    }
}

// 修正：@Composableを外し、通常の関数にしました
fun getTimeSlotColor(hour: Int): Color {
    return when (hour) {
        in 4..10 -> Color(0xFF422B2B)  // 朝：淡赤
        in 11..16 -> Color(0xFF2B422B) // 昼：淡緑
        in 17..22 -> Color(0xFF2B2B42) // 夜：淡青
        else -> Color(0xFF1A1A1A)      // 深夜：黒に近いグレー
    }
}