package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EpgReserveDialog(
    initialKeyword: String,
    initialStartTime: OffsetDateTime,
    initialEndTime: OffsetDateTime,
    onConfirm: (keyword: String, daysOfWeek: Set<Int>, startH: Int, startM: Int, endH: Int, endM: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var keyword by remember { mutableStateOf(initialKeyword) }
    var isEditingKeyword by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val startWindow = initialStartTime.minusHours(1)
    val endWindow = initialEndTime.plusHours(1)

    // 0:日, 1:月 ... 6:土 (EDCB仕様)
    var selectedDaysOfWeek by remember { mutableStateOf(setOf(startWindow.dayOfWeek.value % 7)) }
    var startHour by remember { mutableStateOf(startWindow.hour) }
    var startMinute by remember { mutableStateOf(startWindow.minute) }
    var endHour by remember { mutableStateOf(endWindow.hour) }
    var endMinute by remember { mutableStateOf(endWindow.minute) }

    var showDayOfWeekDialog by remember { mutableStateOf(false) }
    var numberSelectTarget by remember { mutableStateOf<NumberSelectType?>(null) }

    val firstItemFocusRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    val dayOfWeekBtnRequester = remember { FocusRequester() }
    val startHourBtnRequester = remember { FocusRequester() }
    val startMinuteBtnRequester = remember { FocusRequester() }
    val endHourBtnRequester = remember { FocusRequester() }
    val endMinuteBtnRequester = remember { FocusRequester() }

    val keyboardController = LocalSoftwareKeyboardController.current
    var isFirstEnter by remember { mutableStateOf(true) }

    val dayStrings = listOf("日", "月", "火", "水", "木", "金", "土")
    val dayText = if (selectedDaysOfWeek.size == 1) {
        "毎週(${dayStrings[selectedDaysOfWeek.first()]})"
    } else {
        val sortedDays = selectedDaysOfWeek.sortedBy { if (it == 0) 7 else it } // 表示は月から
        "毎週(${sortedDays.joinToString("・") { dayStrings[it] }})"
    }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstItemFocusRequester.requestFocus() }
    }

    fun restoreNumberSelectFocus(target: NumberSelectType) {
        scope.launch {
            delay(50)
            runCatching {
                when (target) {
                    NumberSelectType.START_HOUR -> startHourBtnRequester.requestFocus()
                    NumberSelectType.START_MINUTE -> startMinuteBtnRequester.requestFocus()
                    NumberSelectType.END_HOUR -> endHourBtnRequester.requestFocus()
                    NumberSelectType.END_MINUTE -> endMinuteBtnRequester.requestFocus()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusRestorer()
            .focusGroup()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (isEditingKeyword) {
                        isEditingKeyword = false
                        keyboardController?.hide()
                        runCatching { firstItemFocusRequester.requestFocus() }
                    } else if (showDayOfWeekDialog) {
                        showDayOfWeekDialog = false
                        scope.launch { delay(50); runCatching { dayOfWeekBtnRequester.requestFocus() } }
                    } else if (numberSelectTarget != null) {
                        val target = numberSelectTarget!!
                        numberSelectTarget = null
                        restoreNumberSelectFocus(target)
                    } else {
                        onDismiss()
                    }
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(700.dp)
                .focusProperties {
                    enter = {
                        if (isFirstEnter) {
                            isFirstEnter = false
                            firstItemFocusRequester
                        } else FocusRequester.Default
                    }
                }
                .focusRestorer()
                .focusGroup(),
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = colors.surface,
                contentColor = colors.textPrimary
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "連ドラ予約 (キーワード自動予約)",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = NotoSansJP
                )

                Divider(color = colors.textPrimary.copy(alpha = 0.1f))

                // --- 追跡キーワード ---
                Column {
                    Text(
                        text = "追跡キーワード",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (isEditingKeyword) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(textFieldFocusRequester),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = NotoSansJP,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                                color = colors.textPrimary
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    isEditingKeyword = false
                                    keyboardController?.hide()
                                    runCatching { firstItemFocusRequester.requestFocus() }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.textSecondary,
                                cursorColor = colors.accent
                            )
                        )
                        LaunchedEffect(Unit) {
                            delay(50)
                            runCatching { textFieldFocusRequester.requestFocus() }
                            keyboardController?.show()
                        }
                    } else {
                        Surface(
                            onClick = { isEditingKeyword = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .focusRequester(firstItemFocusRequester),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                            // ★修正: フォーカス時の拡大を無効化
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.05f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = keyword,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = NotoSansJP
                                )
                            }
                        }
                    }
                }

                // --- 追跡基準 ---
                Column {
                    Text(
                        text = "追跡基準 (時間絞り込み)",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { showDayOfWeekDialog = true },
                            modifier = Modifier.focusRequester(dayOfWeekBtnRequester),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.05f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) {
                            Text(
                                text = dayText,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))

                        TimeSelectButton(
                            text = String.format("%02d", startHour),
                            modifier = Modifier.focusRequester(startHourBtnRequester)
                        ) { numberSelectTarget = NumberSelectType.START_HOUR }
                        Text(
                            " : ",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        TimeSelectButton(
                            text = String.format("%02d", startMinute),
                            modifier = Modifier.focusRequester(startMinuteBtnRequester)
                        ) { numberSelectTarget = NumberSelectType.START_MINUTE }
                        Text(
                            "  〜  ",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        TimeSelectButton(
                            text = String.format("%02d", endHour),
                            modifier = Modifier.focusRequester(endHourBtnRequester)
                        ) { numberSelectTarget = NumberSelectType.END_HOUR }
                        Text(
                            " : ",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        TimeSelectButton(
                            text = String.format("%02d", endMinute),
                            modifier = Modifier.focusRequester(endMinuteBtnRequester)
                        ) { numberSelectTarget = NumberSelectType.END_MINUTE }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) { Text("キャンセル", fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            onConfirm(
                                keyword,
                                selectedDaysOfWeek,
                                startHour,
                                startMinute,
                                endHour,
                                endMinute
                            )
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) { Text("この条件で予約", fontWeight = FontWeight.Bold) }
                }
            }
        }

        if (showDayOfWeekDialog) {
            DayOfWeekSelectionDialog(
                initialSelection = selectedDaysOfWeek,
                onConfirm = {
                    if (it.isNotEmpty()) selectedDaysOfWeek = it
                    showDayOfWeekDialog = false
                    scope.launch { delay(50); runCatching { dayOfWeekBtnRequester.requestFocus() } }
                },
                onDismiss = {
                    showDayOfWeekDialog = false
                    scope.launch { delay(50); runCatching { dayOfWeekBtnRequester.requestFocus() } }
                }
            )
        }

        if (numberSelectTarget != null) {
            val range =
                if (numberSelectTarget == NumberSelectType.START_HOUR || numberSelectTarget == NumberSelectType.END_HOUR) 0..23 else 0..59
            val initVal = when (numberSelectTarget) {
                NumberSelectType.START_HOUR -> startHour
                NumberSelectType.START_MINUTE -> startMinute
                NumberSelectType.END_HOUR -> endHour
                NumberSelectType.END_MINUTE -> endMinute
                else -> 0
            }
            NumberSelectionDialog(
                title = if (range.last == 23) "時を選択" else "分を選択",
                range = range,
                initialValue = initVal,
                onConfirm = { selected ->
                    val target = numberSelectTarget!!
                    when (target) {
                        NumberSelectType.START_HOUR -> startHour = selected
                        NumberSelectType.START_MINUTE -> startMinute = selected
                        NumberSelectType.END_HOUR -> endHour = selected
                        NumberSelectType.END_MINUTE -> endMinute = selected
                    }
                    numberSelectTarget = null
                    restoreNumberSelectFocus(target)
                },
                onDismiss = {
                    val target = numberSelectTarget!!
                    numberSelectTarget = null
                    restoreNumberSelectFocus(target)
                }
            )
        }
    }
}