package com.example.komorebi.ui.epg

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.PivotOffsets
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.*
import androidx.tv.material3.MaterialTheme.typography
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EpgJumpMenu(
    dates: List<OffsetDateTime>,
    onSelect: (OffsetDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val now = OffsetDateTime.now()
    val fullTimeSlots = remember { (0..23).toList() }

    // パフォーマンスのため、インデックス管理を最適化
    var globalFocusedIndex by remember { mutableIntStateOf(now.hour) }

    val slotHeight = 13.dp
    val columnWidth = 85.dp

    // FocusRequesterを2次元で保持
    val focusRequesters = remember(dates.size) {
        List(dates.size) { List(24) { FocusRequester() } }
    }

    BackHandler(enabled = true) { onDismiss() }

    LaunchedEffect(Unit) {
        delay(50) // 少し短くしてレスポンス改善
        focusRequesters[0][now.hour].requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).focusGroup(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(IntrinsicSize.Min).wrapContentHeight()
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
                    style = typography.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 4.sp),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row {
                    // --- 1. 時刻ラベル列 ---
                    Column(horizontalAlignment = Alignment.End) {
                        Box(modifier = Modifier.width(60.dp).height(35.dp))
                        fullTimeSlots.forEach { hour ->
                            Box(modifier = Modifier.height(slotHeight).width(60.dp).padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
                                val label = when {
                                    hour == 0 -> "AM 0"
                                    hour == 12 -> "PM 0"
                                    hour % 3 == 0 -> "${hour % 12}"
                                    else -> ""
                                }
                                if (label.isNotEmpty()) Text(label, fontSize = 10.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // --- 2. 日付・スロット列 ---
                    dates.forEachIndexed { dIdx, date ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HeaderCell(date, columnWidth)

                            fullTimeSlots.forEachIndexed { tIdx, hour ->
                                val currentGlobalIndex = (dIdx * 24) + tIdx

                                // ★再構成を抑制: globalFocusedIndex が変わった時だけ計算されるようにする
                                val isInSelectionRange by remember(currentGlobalIndex) {
                                    derivedStateOf {
                                        currentGlobalIndex >= globalFocusedIndex &&
                                                currentGlobalIndex < globalFocusedIndex + 3
                                    }
                                }

                                Surface(
                                    onClick = {
                                        val slotTime = date.withHour(hour).withMinute(0).withSecond(0).withNano(0)
                                        onSelect(slotTime)
                                    },
                                    modifier = Modifier
                                        .width(columnWidth)
                                        .height(slotHeight)
                                        .focusRequester(focusRequesters[dIdx][tIdx])
                                        .focusProperties {
                                            // ★ 修正1: 23時で下を押したら翌日0時へ
                                            if (tIdx == 23 && dIdx < dates.size - 1) {
                                                down = focusRequesters[dIdx + 1][0]
                                            }
                                            // ★ 修正2: 0時で上を押したら前日23時へ
                                            if (tIdx == 0 && dIdx > 0) {
                                                up = focusRequesters[dIdx - 1][23]
                                            }
                                        }
                                        .onFocusChanged {
                                            if (it.isFocused) globalFocusedIndex = currentGlobalIndex
                                        },
                                    shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (isInSelectionRange) Color(0xFFFFFF00) else getTimeSlotColor(hour),
                                        focusedContainerColor = Color(0xFFFFFF00)
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        border = Border(BorderStroke(0.5.dp, Color.Black.copy(0.3f))),
                                        focusedBorder = Border(BorderStroke(2.dp, Color.White))
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

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HeaderCell(date: OffsetDateTime, width: Dp) {
    val isSunday = date.dayOfWeek.value == 7
    val isSaturday = date.dayOfWeek.value == 6
    Column(
        modifier = Modifier.width(width).height(35.dp), // 高さを少し削る
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

@Composable
fun getTimeSlotColor(hour: Int): Color {
    return when (hour) {
        in 4..10 -> Color(0xFF422B2B)  // 朝：淡赤
        in 11..16 -> Color(0xFF2B422B) // 昼：淡緑
        in 17..22 -> Color(0xFF2B2B42) // 夜：淡青
        else -> Color(0xFF1A1A1A)      // 深夜：黒に近いグレー
    }
}