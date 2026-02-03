package com.example.komorebi.ui.program

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.tv.material3.MaterialTheme.typography
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale // ★ 追加

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpgJumpMenu(
    dates: List<OffsetDateTime>,
    timeSlots: List<Int>,
    onSelect: (OffsetDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    // 戻るボタンをインターセプト
    BackHandler(enabled = true) {
        onDismiss()
    }
    val focusRequesters = remember(dates.size, timeSlots.size) {
        List(dates.size) { List(timeSlots.size) { FocusRequester() } }
    }

    LaunchedEffect(Unit) {
        delay(100) // 描画完了を待つ
        focusRequesters[0][0].requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .pointerInput(Unit) {}
            .focusGroup(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .wrapContentSize()
                .focusGroup(),
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(
                containerColor = Color(0xFF111111),
                contentColor = Color.White
            ),
            border = Border(BorderStroke(1.dp, Color(0xFF333333)))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "表示日時の指定",
                    style = typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                    color = Color.Cyan
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.focusGroup()
                ) {
                    // --- 1. 時間軸専用の列 (フォーカス不可) ---
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Box(modifier = Modifier.height(52.dp))

                        timeSlots.forEach { hour ->
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .padding(end = 8.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = "%d:00".format(hour),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }

                    // --- 2. 日付と選択セルの列 ---
                    dates.forEachIndexed { dateIndex, date ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            val isSunday = date.dayOfWeek.value == 7
                            val isSaturday = date.dayOfWeek.value == 6

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(bottom = 8.dp).height(44.dp)
                            ) {
                                Text(
                                    text = date.format(DateTimeFormatter.ofPattern("M/d")),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                // ★ 曜日の日本語化: Locale.JAPANESE を指定
                                Text(
                                    text = date.format(DateTimeFormatter.ofPattern("(E)", Locale.JAPANESE)),
                                    fontSize = 12.sp,
                                    color = when {
                                        isSunday -> Color(0xFFFF5252)
                                        isSaturday -> Color(0xFF448AFF)
                                        else -> Color.LightGray
                                    }
                                )
                            }

                            timeSlots.forEachIndexed { timeIndex, hour ->
                                val slotBaseColor = getTimeSlotColor(hour)

                                Surface(
                                    onClick = {
                                        val selectedTime = date.withHour(hour).withMinute(0).withSecond(0).withNano(0)
                                        onSelect(selectedTime)
                                    },
                                    modifier = Modifier
                                        .width(75.dp)
                                        .height(34.dp)
                                        .focusRequester(focusRequesters[dateIndex][timeIndex]),
                                    shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = slotBaseColor,
                                        focusedContainerColor = Color.White,
                                        contentColor = Color.Transparent, // 文字を消すので透明でもOK
                                        focusedContentColor = Color.Black
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        border = Border(BorderStroke(0.5.dp, Color(0xFF333333))),
                                        focusedBorder = Border(BorderStroke(2.dp, Color(0xFFCCCCCC)))
                                    )
                                ) {
                                    // ★ 修正ポイント：セル内のTextを削除し、Boxのみにする
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
fun getTimeSlotColor(hour: Int): Color {
    return when (hour) {
        in 5..10 -> Color(0xFF1A237E) // 朝: 濃い青
        in 11..17 -> Color(0xFF37474F) // 昼: チャコールグレー
        else -> Color(0xFF000000)      // 夜: 漆黒
    }
}