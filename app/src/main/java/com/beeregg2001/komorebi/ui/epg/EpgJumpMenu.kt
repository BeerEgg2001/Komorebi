package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import android.util.Log
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
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
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
    val colors = KomorebiTheme.colors
    val now = remember { OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS) }
    val fullTimeSlots = remember { (0..23).toList() }

    val gridData = remember(dates, now, colors) {
        dates.mapIndexed { dIdx, date ->
            fullTimeSlots.map { hour ->
                val slotTime = date.withHour(hour).truncatedTo(ChronoUnit.HOURS)
                EpgSlotState(
                    time = slotTime,
                    isSelectable = !slotTime.isBefore(now),
                    baseColor = getTimeSlotColor(hour, colors),
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

    LaunchedEffect(Unit) {
        delay(100)
        var focused = false
        for (dIdx in gridData.indices) {
            for (tIdx in 0..23) {
                if (gridData[dIdx][tIdx].isSelectable) {
                    focusRequesters[dIdx][tIdx].safeRequestFocus("EpgJumpMenu")
                    focused = true
                    break
                }
            }
            if (focused) break
        }
        if (!focused && dates.isNotEmpty()) {
            focusRequesters[0][0].safeRequestFocus("EpgJumpMenuFallback")
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
            .onKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyDown) {
                        return@onKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyUp) {
                        onDismiss()
                        return@onKeyEvent true
                    }
                }
                false
            }
            .focusGroup(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            // ★修正: 絶対隔離トラップ (exit = Cancel) を削除し、プログラムからの脱出を許可
            modifier = Modifier.width(IntrinsicSize.Min).wrapContentHeight().focusGroup(),
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.2f)))
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "日時指定ジャンプ",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 4.sp),
                    color = colors.textPrimary, modifier = Modifier.padding(bottom = 8.dp)
                )

                Row {
                    Column(horizontalAlignment = Alignment.End) {
                        Box(modifier = Modifier.width(60.dp).height(35.dp))
                        fullTimeSlots.forEach { hour -> TimeLabelCell(hour, slotHeight) }
                    }

                    gridData.forEachIndexed { dIdx, daySlots ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HeaderCell(dates[dIdx], columnWidth)
                            daySlots.forEachIndexed { tIdx, slot ->
                                val isHighlighted = globalFocusedIndex != -1 && slot.globalIndex >= globalFocusedIndex && slot.globalIndex < globalFocusedIndex + 3
                                var isFocused by remember { mutableStateOf(false) }

                                Box(
                                    modifier = Modifier.width(columnWidth).height(slotHeight)
                                        .focusRequester(focusRequesters[dIdx][tIdx])
                                        .focusProperties {
                                            // ★追加: 十字キーがメニュー外に逃げないように個別にバウンドを設定
                                            if (tIdx == 23 && dIdx < dates.size - 1) { down = focusRequesters[dIdx + 1][0] }
                                            else if (tIdx == 23) { down = FocusRequester.Cancel }

                                            if (tIdx == 0 && dIdx > 0) { up = focusRequesters[dIdx - 1][23] }
                                            else if (tIdx == 0) { up = FocusRequester.Cancel }

                                            if (dIdx == 0) { left = FocusRequester.Cancel }
                                            if (dIdx == dates.size - 1) { right = FocusRequester.Cancel }
                                        }
                                        .onFocusChanged {
                                            isFocused = it.isFocused
                                            if (it.isFocused) { globalFocusedIndex = slot.globalIndex }
                                        }
                                        .focusable(enabled = slot.isSelectable)
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                                onSelect(slot.time); true
                                            } else false
                                        }
                                        .background(if (isHighlighted || isFocused) colors.accent else if (!slot.isSelectable) slot.baseColor.copy(alpha = 0.1f) else slot.baseColor)
                                        .border(width = if (isFocused) 2.dp else 0.5.dp, color = if (isFocused) colors.textPrimary else if (!slot.isSelectable) Color.Transparent else colors.background.copy(0.3f))
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
    val colors = KomorebiTheme.colors
    Box(modifier = Modifier.height(height).width(60.dp).padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
        val label = when {
            hour == 0 -> "AM 0"
            hour == 12 -> "PM 0"
            hour % 3 == 0 -> "${hour % 12}"
            else -> ""
        }
        if (label.isNotEmpty()) { Text(label, fontSize = 10.sp, color = colors.textSecondary, fontWeight = FontWeight.Bold) }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HeaderCell(date: OffsetDateTime, width: Dp) {
    val colors = KomorebiTheme.colors
    val isSunday = date.dayOfWeek.value == 7
    val isSaturday = date.dayOfWeek.value == 6
    Column(modifier = Modifier.width(width).height(35.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = date.format(DateTimeFormatter.ofPattern("M/d", Locale.JAPANESE)), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text(text = date.format(DateTimeFormatter.ofPattern("(E)", Locale.JAPANESE)), fontSize = 10.sp, color = when { isSunday -> Color(0xFFFF5252); isSaturday -> Color(0xFF448AFF); else -> colors.textSecondary })
    }
}

fun getTimeSlotColor(hour: Int, colors: com.beeregg2001.komorebi.ui.theme.KomorebiColors): Color {
    return when (hour) {
        in 4..10 -> Color(0xFF422B2B)
        in 11..16 -> Color(0xFF2B422B)
        in 17..22 -> Color(0xFF2B2B42)
        else -> colors.surface
    }
}