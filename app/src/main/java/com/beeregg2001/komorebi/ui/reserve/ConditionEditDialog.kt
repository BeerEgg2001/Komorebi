package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConditionEditDialog(
    condition: ReservationCondition,
    onConfirmUpdate: (keyword: String, daysOfWeek: Set<Int>, startH: Int, startM: Int, endH: Int, endM: Int) -> Unit,
    onConfirmDelete: (deleteRelated: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf(condition.programSearchCondition.keyword) }
    var isEditingKeyword by remember { mutableStateOf(false) }

    val dateRange = condition.programSearchCondition.dateRanges?.firstOrNull()
    var selectedDaysOfWeek by remember {
        mutableStateOf(condition.programSearchCondition.dateRanges?.map { it.startDayOfWeek }
            ?.toSet() ?: setOf(0))
    }
    var startHour by remember { mutableIntStateOf(dateRange?.startHour ?: 0) }
    var startMinute by remember { mutableIntStateOf(dateRange?.startMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(dateRange?.endHour ?: 0) }
    var endMinute by remember { mutableIntStateOf(dateRange?.endMinute ?: 0) }

    var showDayOfWeekDialog by remember { mutableStateOf(false) }
    var numberSelectTarget by remember { mutableStateOf<NumberSelectType?>(null) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteRelatedReserves by remember { mutableStateOf(true) }

    val keywordBtnRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    val dayOfWeekBtnRequester = remember { FocusRequester() }
    val startHourBtnRequester = remember { FocusRequester() }
    val startMinuteBtnRequester = remember { FocusRequester() }
    val endHourBtnRequester = remember { FocusRequester() }
    val endMinuteBtnRequester = remember { FocusRequester() }
    val deleteConfirmRequester = remember { FocusRequester() }

    val keyboardController = LocalSoftwareKeyboardController.current
    var isFirstEnter by remember { mutableStateOf(true) }

    val dayStrings = listOf("日", "月", "火", "水", "木", "金", "土")
    val dayText = if (selectedDaysOfWeek.size == 1) {
        "毎週(${dayStrings[selectedDaysOfWeek.first()]})"
    } else {
        val sortedDays = selectedDaysOfWeek.sortedBy { if (it == 0) 7 else it }
        "毎週(${sortedDays.joinToString("・") { dayStrings[it] }})"
    }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { keywordBtnRequester.requestFocus() }
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
            .zIndex(100f)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusRestorer()
            .focusGroup()
            .focusable()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (isEditingKeyword) {
                        isEditingKeyword = false
                        keyboardController?.hide()
                        scope.launch { delay(50); runCatching { keywordBtnRequester.requestFocus() } }
                    } else if (showDeleteConfirm) {
                        showDeleteConfirm = false
                        scope.launch { delay(50); runCatching { keywordBtnRequester.requestFocus() } }
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
        if (!showDeleteConfirm) {
            Box(
                modifier = Modifier
                    .width(700.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .focusProperties {
                        enter = {
                            if (isFirstEnter) {
                                isFirstEnter = false; keywordBtnRequester
                            } else FocusRequester.Default
                        }
                    }
                    .focusRestorer()
                    .focusGroup()
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        "自動予約条件の編集",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Divider(color = colors.textPrimary.copy(alpha = 0.1f))

                    Column {
                        Text(
                            "追跡キーワード",
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
                                        runCatching { keywordBtnRequester.requestFocus() }
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
                                    .focusRequester(keywordBtnRequester),
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

                    Column {
                        Text(
                            "追跡基準 (時間絞り込み)",
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
                                    dayText,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 10.dp
                                    ),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            TimeSelectButton(
                                String.format("%02d", startHour),
                                modifier = Modifier.focusRequester(startHourBtnRequester)
                            ) { numberSelectTarget = NumberSelectType.START_HOUR }
                            Text(
                                " : ",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            TimeSelectButton(
                                String.format("%02d", startMinute),
                                modifier = Modifier.focusRequester(startMinuteBtnRequester)
                            ) { numberSelectTarget = NumberSelectType.START_MINUTE }
                            Text(
                                "  〜  ",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            TimeSelectButton(
                                String.format("%02d", endHour),
                                modifier = Modifier.focusRequester(endHourBtnRequester)
                            ) { numberSelectTarget = NumberSelectType.END_HOUR }
                            Text(
                                " : ",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            TimeSelectButton(
                                String.format("%02d", endMinute),
                                modifier = Modifier.focusRequester(endMinuteBtnRequester)
                            ) { numberSelectTarget = NumberSelectType.END_MINUTE }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFFC62828),
                                contentColor = Color.White
                            )
                        ) { Text("この条件を削除", fontWeight = FontWeight.Bold) }

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                    contentColor = colors.textPrimary
                                )
                            ) { Text("キャンセル", fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = {
                                    onConfirmUpdate(
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
                            ) { Text("更新を保存", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        } else {
            LaunchedEffect(Unit) { delay(50); runCatching { deleteConfirmRequester.requestFocus() } }

            Box(
                modifier = Modifier
                    .width(500.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .focusProperties { exit = { FocusRequester.Cancel } }
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "条件の削除",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "自動予約条件「${condition.programSearchCondition.keyword}」を削除しますか？",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        onClick = { deleteRelatedReserves = !deleteRelatedReserves },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.05f),
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("関連する録画予約も削除する", fontWeight = FontWeight.Bold)
                                Text(
                                    "既にリストに登録されている${condition.reservationCount}件の予約も一括で取り消します。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            }
                            if (deleteRelatedReserves) {
                                Icon(Icons.Default.Check, null, tint = colors.accent)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                showDeleteConfirm = false
                                scope.launch { delay(50); runCatching { keywordBtnRequester.requestFocus() } }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                contentColor = colors.textPrimary
                            )
                        ) { Text("キャンセル") }

                        Button(
                            onClick = { onConfirmDelete(deleteRelatedReserves) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(deleteConfirmRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFFC62828),
                                contentColor = Color.White
                            )
                        ) { Text("完全に削除する", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (showDayOfWeekDialog) {
            DayOfWeekSelectionDialog(
                initialSelection = selectedDaysOfWeek,
                onConfirm = {
                    if (it.isNotEmpty()) selectedDaysOfWeek = it; showDayOfWeekDialog = false
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
                NumberSelectType.START_HOUR -> startHour; NumberSelectType.START_MINUTE -> startMinute
                NumberSelectType.END_HOUR -> endHour; NumberSelectType.END_MINUTE -> endMinute
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
                    numberSelectTarget = null; restoreNumberSelectFocus(target)
                },
                onDismiss = {
                    val target = numberSelectTarget!!; numberSelectTarget =
                    null; restoreNumberSelectFocus(target)
                }
            )
        }
    }
}