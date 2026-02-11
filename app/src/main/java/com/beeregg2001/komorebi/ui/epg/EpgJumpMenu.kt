package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.tv.material3.MaterialTheme.typography
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

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

    val gridData = remember(dates, now) {
        dates.mapIndexed { dIdx, date ->
            fullTimeSlots.map { hour ->
                val slotTime = date.withHour(hour).truncatedTo(ChronoUnit.HOURS)
                EpgSlotState(
                    time = slotTime,
                    isSelectable = !slotTime.isBefore(now),
                    baseColor = getTimeSlotColor(hour),
                    globalIndex = (dIdx * 24) + hour
                )
            }
        }
    }

    var globalFocusedIndex by remember { mutableIntStateOf(-1) }

    val slotHeight = 13.dp
    val columnWidth = 85.dp

    val focusRequesters = remember(dates.size) {
        List(dates.size) { List(24) { FocusRequester() } }
    }

    BackHandler(enabled = true) { onDismiss() }

    // ★修正: delay(50) を削除し、直ちにフォーカスを要求して「一瞬間が空く」問題を解消
    LaunchedEffect(Unit) {
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
                    Column(horizontalAlignment = Alignment.End) {
                        Box(modifier = Modifier.width(60.dp).height(35.dp))
                        fullTimeSlots.forEach { hour ->
                            TimeLabelCell(hour, slotHeight)
                        }
                    }

                    gridData.forEachIndexed { dIdx, daySlots ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HeaderCell(dates[dIdx], columnWidth)

                            daySlots.forEachIndexed { tIdx, slot ->
                                val isHighlighted = globalFocusedIndex != -1 &&
                                        slot.globalIndex >= globalFocusedIndex &&
                                        slot.globalIndex < globalFocusedIndex + 3

                                var isFocused by remember { mutableStateOf(false) }

                                // ★修正: 重いSurfaceを最軽量のBoxに置き換え（見た目は完全に同一に再現）
                                Box(
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
                                            isFocused = it.isFocused
                                            if (it.isFocused) {
                                                globalFocusedIndex = slot.globalIndex
                                            }
                                        }
                                        .focusable(enabled = slot.isSelectable)
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                                onSelect(slot.time)
                                                true
                                            } else false
                                        }
                                        .background(
                                            if (isHighlighted || isFocused) Color(0xFFFFFF00)
                                            else if (!slot.isSelectable) slot.baseColor.copy(alpha = 0.1f)
                                            else slot.baseColor
                                        )
                                        .border(
                                            width = if (isFocused) 2.dp else 0.5.dp,
                                            color = if (isFocused) Color.White
                                            else if (!slot.isSelectable) Color.Transparent
                                            else Color.Black.copy(0.3f)
                                        )
                                )
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

fun getTimeSlotColor(hour: Int): Color {
    return when (hour) {
        in 4..10 -> Color(0xFF422B2B)  // 朝：淡赤
        in 11..16 -> Color(0xFF2B422B) // 昼：淡緑
        in 17..22 -> Color(0xFF2B2B42) // 夜：淡青
        else -> Color(0xFF1A1A1A)      // 深夜：黒に近いグレー
    }
}