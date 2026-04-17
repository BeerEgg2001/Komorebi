package com.beeregg2001.komorebi.ui.reserve

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

enum class NumberSelectType { START_HOUR, START_MINUTE, END_HOUR, END_MINUTE }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TimeSelectButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.05f),
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun DayOfWeekSelectionDialog(
    initialSelection: Set<Int>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var selected by remember { mutableStateOf(initialSelection) }
    val dayOrder = listOf(1, 2, 3, 4, 5, 6, 0)
    val dayLabels = mapOf(
        0 to "日曜日", 1 to "月曜日", 2 to "火曜日", 3 to "水曜日",
        4 to "木曜日", 5 to "金曜日", 6 to "土曜日"
    )

    val firstItemRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { firstItemRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .zIndex(100f)
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown &&
                    it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK
                ) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .background(colors.surface, RoundedCornerShape(12.dp))
                .border(1.dp, colors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "曜日を選択",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dayOrder) { dayValue ->
                        val isSelected = selected.contains(dayValue)
                        val isFirst = dayValue == dayOrder.first()
                        Surface(
                            onClick = {
                                selected =
                                    if (isSelected) selected - dayValue else selected + dayValue
                            },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) colors.accent.copy(alpha = 0.2f) else Color.Transparent,
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isFirst) Modifier.focusRequester(firstItemRequester) else Modifier
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    dayLabels[dayValue] ?: "",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check, contentDescription = null,
                                        tint = LocalContentColor.current,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = onDismiss, modifier = Modifier.weight(1f),
                                scale = ButtonDefaults.scale(focusedScale = 1f),
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                    contentColor = colors.textPrimary
                                )
                            ) { Text("キャンセル") }
                            Button(
                                onClick = { onConfirm(selected) }, modifier = Modifier.weight(1f),
                                scale = ButtonDefaults.scale(focusedScale = 1f),
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.accent,
                                    contentColor = if (colors.isDark) Color.Black else Color.White
                                )
                            ) { Text("決定") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun NumberSelectionDialog(
    title: String,
    range: IntRange,
    initialValue: Int,
    isHour: Boolean = false, // ★ 追加: 時刻を選択しているかのフラグ
    timeFormat: String = "24H", // ★ 追加: フォーマット指定
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = range.indexOf(initialValue).coerceAtLeast(0)
    )

    val initialItemRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { initialItemRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .zIndex(100f)
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown &&
                    it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK
                ) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(200.dp)
                .height(360.dp)
                .background(colors.surface, RoundedCornerShape(12.dp))
                .border(1.dp, colors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(range.toList()) { num ->
                        val isInitial = num == initialValue

                        // ★ 修正: isHour と timeFormat に応じて表示テキストを生成
                        val displayText = if (isHour && timeFormat == "12H") {
                            val amPm = if (num < 12) "午前" else "午後"
                            val h12 = if (num % 12 == 0) 12 else num % 12
                            "$amPm $h12"
                        } else {
                            String.format("%02d", num)
                        }

                        Surface(
                            onClick = { onConfirm(num) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            ),
                            modifier = Modifier
                                .width(120.dp)
                                .then(
                                    if (isInitial) Modifier.focusRequester(initialItemRequester) else Modifier
                                )
                        ) {
                            Text(
                                text = displayText,
                                modifier = Modifier
                                    .padding(vertical = 12.dp)
                                    .fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}