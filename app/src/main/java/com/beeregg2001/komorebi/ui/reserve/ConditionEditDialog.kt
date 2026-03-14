package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConditionEditDialog(
    condition: ReservationCondition,
    relatedReserves: List<ReserveItem> = emptyList(),
    onConfirmUpdate: (
        isEnabled: Boolean,
        keyword: String, daysOfWeek: Set<Int>, startH: Int, startM: Int, endH: Int, endM: Int,
        excludeKeyword: String, isTitleOnly: Boolean, broadcastType: String,
        isFuzzySearch: Boolean, duplicateScope: String, priority: Int,
        isEventRelay: Boolean, isExactRecord: Boolean
    ) -> Unit,
    onConfirmDelete: (deleteRelated: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onReserveItemClick: (ReserveItem) -> Unit
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var isEnabled by remember { mutableStateOf(condition.programSearchCondition.isEnabled) }
    var keyword by remember { mutableStateOf(condition.programSearchCondition.keyword) }
    var isEditingKeyword by remember { mutableStateOf(false) }

    val dateRange = condition.programSearchCondition.dateRanges?.firstOrNull()
    var selectedDaysOfWeek by remember {
        mutableStateOf(
            condition.programSearchCondition.dateRanges?.map { it.startDayOfWeek }
                ?.toSet() ?: setOf(0)
        )
    }
    var startHour by remember { mutableIntStateOf(dateRange?.startHour ?: 0) }
    var startMinute by remember { mutableIntStateOf(dateRange?.startMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(dateRange?.endHour ?: 0) }
    var endMinute by remember { mutableIntStateOf(dateRange?.endMinute ?: 0) }

    var showAdvancedSettings by remember { mutableStateOf(false) }
    var advExcludeKeyword by remember { mutableStateOf(condition.programSearchCondition.excludeKeyword) }
    var advIsTitleOnly by remember { mutableStateOf(condition.programSearchCondition.isTitleOnly) }
    var advBroadcastType by remember { mutableStateOf(condition.programSearchCondition.broadcastType) }
    var advIsFuzzySearch by remember { mutableStateOf(condition.programSearchCondition.isFuzzySearchEnabled) }
    var advDuplicateScope by remember { mutableStateOf(condition.programSearchCondition.duplicateTitleCheckScope) }
    var advPriority by remember { mutableIntStateOf(condition.recordSettings.priority) }
    var advIsEventRelay by remember { mutableStateOf(condition.recordSettings.isEventRelayFollowEnabled) }
    var advIsExactRecord by remember { mutableStateOf(condition.recordSettings.isExactRecordingEnabled) }

    var showDayOfWeekDialog by remember { mutableStateOf(false) }
    var numberSelectTarget by remember { mutableStateOf<NumberSelectType?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteRelatedReserves by remember { mutableStateOf(true) }

    // ===== FocusRequester 群 =====
    val enableSwitchRequester = remember { FocusRequester() }
    val enableInteractionSource = remember { MutableInteractionSource() }
    val isEnableRowFocused by enableInteractionSource.collectIsFocusedAsState()

    val keywordBtnRequester = remember { FocusRequester() }
    val advancedBtnRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    val dayOfWeekBtnRequester = remember { FocusRequester() }
    val startHourBtnRequester = remember { FocusRequester() }
    val startMinuteBtnRequester = remember { FocusRequester() }
    val endHourBtnRequester = remember { FocusRequester() }
    val endMinuteBtnRequester = remember { FocusRequester() }
    val deleteConfirmRequester = remember { FocusRequester() }
    val saveBtnRequester = remember { FocusRequester() }
    val deleteBtnRequester = remember { FocusRequester() }
    val firstRightItemRequester = remember { FocusRequester() }

    val keyboardController = LocalSoftwareKeyboardController.current

    val dayStrings = listOf("日", "月", "火", "水", "木", "金", "土")
    val dayText = if (selectedDaysOfWeek.size == 1) {
        "毎週(${dayStrings[selectedDaysOfWeek.first()]})"
    } else {
        val sortedDays = selectedDaysOfWeek.sortedBy { if (it == 0) 7 else it }
        "毎週(${sortedDays.joinToString("・") { dayStrings[it] }})"
    }

    var isFirstEnter by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { enableSwitchRequester.requestFocus() }
    }

    LaunchedEffect(isEditingKeyword) {
        if (!isEditingKeyword && !isFirstEnter) {
            delay(100)
            runCatching { keywordBtnRequester.requestFocus() }
        }
    }

    fun restoreNumberSelectFocus(target: NumberSelectType) {
        scope.launch {
            delay(100)
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

    fun openDayOfWeekDialog() {
        focusManager.clearFocus(force = true)
        showDayOfWeekDialog = true
    }

    fun openNumberSelect(type: NumberSelectType) {
        focusManager.clearFocus(force = true)
        numberSelectTarget = type
    }

    fun openAdvancedSettings() {
        focusManager.clearFocus(force = true)
        showAdvancedSettings = true
    }

    fun openDeleteConfirm() {
        focusManager.clearFocus(force = true)
        showDeleteConfirm = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.97f))
            .focusGroup()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown &&
                    it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK
                ) {
                    when {
                        isEditingKeyword -> {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            isEditingKeyword = false
                        }
                        showAdvancedSettings -> {
                            showAdvancedSettings = false
                            scope.launch { delay(100); runCatching { advancedBtnRequester.requestFocus() } }
                        }
                        showDeleteConfirm -> {
                            showDeleteConfirm = false
                            scope.launch { delay(100); runCatching { keywordBtnRequester.requestFocus() } }
                        }
                        showDayOfWeekDialog -> {
                            showDayOfWeekDialog = false
                            scope.launch { delay(100); runCatching { dayOfWeekBtnRequester.requestFocus() } }
                        }
                        numberSelectTarget != null -> {
                            val target = numberSelectTarget!!
                            numberSelectTarget = null
                            restoreNumberSelectFocus(target)
                        }
                        else -> onDismiss()
                    }
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        if (!showDeleteConfirm && !showAdvancedSettings) {
            Box(
                modifier = Modifier
                    .width(1000.dp)
                    .heightIn(min = 500.dp, max = 680.dp) // maxを少し広げて安全マージンを確保
                    .background(colors.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .focusProperties {
                        enter = {
                            if (isFirstEnter) {
                                isFirstEnter = false
                                enableSwitchRequester
                            } else FocusRequester.Default
                        }
                    }
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // =========================================================
                    // 左ペイン
                    // =========================================================
                    Column(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .focusGroup()
                            .focusProperties {
                                exit = { direction ->
                                    when (direction) {
                                        FocusDirection.Up,
                                        FocusDirection.Down,
                                        FocusDirection.Left -> FocusRequester.Cancel
                                        else -> FocusRequester.Default
                                    }
                                }
                            }
                            // ★修正: 上下のパディングを32dpから24dpに減らして空間を稼ぐ
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        Text(
                            "自動予約条件の編集",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Divider(
                            color = colors.textPrimary.copy(alpha = 0.1f),
                            modifier = Modifier.padding(vertical = 12.dp) // ★修正: 20dp -> 12dp
                        )

                        Surface(
                            onClick = { isEnabled = !isEnabled },
                            interactionSource = enableInteractionSource,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp) // ★修正: 16dp -> 12dp
                                .focusRequester(enableSwitchRequester),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.05f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) {
                            val inverseColor = if (colors.isDark) Color.Black else Color.White

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp), // ★修正: vertical 12dp -> 8dp
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "この条件を有効にする",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = null,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = inverseColor,
                                        checkedTrackColor = colors.accent,
                                        uncheckedThumbColor = if (isEnableRowFocused) inverseColor else colors.textPrimary,
                                        uncheckedTrackColor = if (isEnableRowFocused) inverseColor.copy(alpha = 0.4f) else colors.textPrimary.copy(alpha = 0.2f)
                                    )
                                )
                            }
                        }

                        // --- キーワード入力 ---
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
                                    value = keyword, onValueChange = { keyword = it },
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
                                    keyboardActions = KeyboardActions(onDone = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        isEditingKeyword = false
                                    }),
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
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
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
                                            fontFamily = NotoSansJP,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp)) // ★修正: 20dp -> 16dp

                        // --- 時間絞り込みエリア ---
                        Column {
                            Text(
                                "追跡基準 (時間絞り込み)",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textSecondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { // ★修正: 12dp -> 8dp
                                Surface(
                                    onClick = { openDayOfWeekDialog() },
                                    modifier = Modifier.focusRequester(dayOfWeekBtnRequester),
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
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

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TimeSelectButton(
                                        String.format("%02d", startHour),
                                        modifier = Modifier.focusRequester(startHourBtnRequester)
                                    ) { openNumberSelect(NumberSelectType.START_HOUR) }
                                    Text(
                                        " : ", color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    TimeSelectButton(
                                        String.format("%02d", startMinute),
                                        modifier = Modifier.focusRequester(startMinuteBtnRequester)
                                    ) { openNumberSelect(NumberSelectType.START_MINUTE) }
                                    Text(
                                        "  〜  ", color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    TimeSelectButton(
                                        String.format("%02d", endHour),
                                        modifier = Modifier.focusRequester(endHourBtnRequester)
                                    ) { openNumberSelect(NumberSelectType.END_HOUR) }
                                    Text(
                                        " : ", color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    TimeSelectButton(
                                        String.format("%02d", endMinute),
                                        modifier = Modifier.focusRequester(endMinuteBtnRequester)
                                    ) { openNumberSelect(NumberSelectType.END_MINUTE) }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp)) // ★修正: 24dp -> 16dp

                        Surface(
                            onClick = { openAdvancedSettings() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp) // ★修正: 48dp -> 44dp
                                .focusRequester(advancedBtnRequester),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.05f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) { Text("詳細設定を開く", fontWeight = FontWeight.Bold) }
                        }

                        // ★上部がスッキリしたため、ここの Spacer(weight) がしっかり隙間を作ります
                        Spacer(Modifier.weight(1f))

                        // --- 下部ボタン行 ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(
                                onClick = { openDeleteConfirm() },
                                modifier = Modifier.focusRequester(deleteBtnRequester),
                                scale = ButtonDefaults.scale(focusedScale = 1.05f),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFFC62828),
                                    contentColor = Color.White,
                                    focusedContainerColor = Color(0xFFFF5252),
                                    focusedContentColor = Color.White
                                )
                            ) { Text("削除", fontWeight = FontWeight.Bold) }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onDismiss,
                                    scale = ButtonDefaults.scale(focusedScale = 1.05f),
                                    colors = ButtonDefaults.colors(
                                        containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                        contentColor = colors.textPrimary,
                                        focusedContainerColor = colors.textPrimary,
                                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                                    )
                                ) { Text("キャンセル", fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        onConfirmUpdate(
                                            isEnabled,
                                            keyword, selectedDaysOfWeek,
                                            startHour, startMinute, endHour, endMinute,
                                            advExcludeKeyword, advIsTitleOnly, advBroadcastType,
                                            advIsFuzzySearch, advDuplicateScope, advPriority,
                                            advIsEventRelay, advIsExactRecord
                                        )
                                    },
                                    modifier = Modifier.focusRequester(saveBtnRequester),
                                    scale = ButtonDefaults.scale(focusedScale = 1.05f),
                                    colors = ButtonDefaults.colors(
                                        containerColor = colors.accent,
                                        contentColor = if (colors.isDark) Color.Black else Color.White,
                                        focusedContainerColor = colors.textPrimary,
                                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                                    )
                                ) { Text("保存", fontWeight = FontWeight.Bold) }
                            }
                        }
                    }

                    // 縦区切り線
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .padding(vertical = 24.dp) // ★修正: 32dp -> 24dp
                            .background(colors.textPrimary.copy(alpha = 0.1f))
                    )

                    // =========================================================
                    // 右ペイン
                    // =========================================================
                    Column(
                        modifier = Modifier
                            .weight(0.9f)
                            .fillMaxHeight()
                            .focusGroup()
                            .focusRestorer()
                            .focusProperties {
                                exit = { direction ->
                                    when (direction) {
                                        FocusDirection.Up,
                                        FocusDirection.Down,
                                        FocusDirection.Right -> FocusRequester.Cancel
                                        else -> FocusRequester.Default
                                    }
                                }
                                enter = {
                                    if (relatedReserves.isNotEmpty()) firstRightItemRequester
                                    else FocusRequester.Default
                                }
                            }
                            // ★修正: 右側も合わせてパディングを減らす
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        Text(
                            "この条件で予約される番組 (${relatedReserves.size}件)",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Divider(
                            color = colors.textPrimary.copy(alpha = 0.1f),
                            modifier = Modifier.padding(vertical = 12.dp) // ★修正: 16dp -> 12dp
                        )

                        if (relatedReserves.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "予約対象の番組が見つかりません",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                            }
                        } else {
                            TvLazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(relatedReserves.size) { index ->
                                    val reserve = relatedReserves[index]
                                    val start = runCatching {
                                        OffsetDateTime.parse(reserve.program.startTime)
                                    }.getOrNull()
                                    val end = runCatching {
                                        OffsetDateTime.parse(reserve.program.endTime)
                                    }.getOrNull()
                                    val formatter = DateTimeFormatter.ofPattern("MM/dd(E) HH:mm")
                                    val endFormatter = DateTimeFormatter.ofPattern("HH:mm")
                                    val timeStr = if (start != null && end != null)
                                        "${start.format(formatter)} ～ ${end.format(endFormatter)}"
                                    else "時間不明"

                                    Surface(
                                        onClick = { onReserveItemClick(reserve) },
                                        modifier = if (index == 0)
                                            Modifier
                                                .fillMaxWidth()
                                                .focusRequester(firstRightItemRequester)
                                        else
                                            Modifier.fillMaxWidth(),
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = colors.textPrimary.copy(alpha = 0.05f),
                                            contentColor = colors.textPrimary,
                                            focusedContainerColor = colors.textPrimary,
                                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                        ) {
                                            Text(
                                                text = reserve.program.title,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = timeStr,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = LocalContentColor.current.copy(alpha = 0.7f)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = reserve.channel.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = LocalContentColor.current.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showDeleteConfirm) {
            LaunchedEffect(Unit) {
                delay(100)
                runCatching { deleteConfirmRequester.requestFocus() }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(500.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            colors.textPrimary.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp)
                        )
                        .focusGroup()
                        .focusProperties { exit = { FocusRequester.Cancel } }
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "条件の削除",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFFFF5252), fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "自動予約条件「${condition.programSearchCondition.keyword}」を削除しますか？",
                            style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        Surface(
                            onClick = { deleteRelatedReserves = !deleteRelatedReserves },
                            interactionSource = interactionSource,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.05f),
                                contentColor = colors.textPrimary,
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
                                        color = LocalContentColor.current.copy(alpha = 0.7f)
                                    )
                                }
                                if (deleteRelatedReserves) {
                                    Icon(
                                        Icons.Default.Check, null,
                                        tint = if (isFocused) LocalContentColor.current else colors.accent
                                    )
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
                                    scope.launch { delay(100); runCatching { keywordBtnRequester.requestFocus() } }
                                },
                                modifier = Modifier.weight(1f),
                                scale = ButtonDefaults.scale(focusedScale = 1.05f),
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                    contentColor = colors.textPrimary,
                                    focusedContainerColor = colors.textPrimary,
                                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                                )
                            ) { Text("キャンセル", fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = { onConfirmDelete(deleteRelatedReserves) },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(deleteConfirmRequester),
                                scale = ButtonDefaults.scale(focusedScale = 1.05f),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFFC62828),
                                    contentColor = Color.White,
                                    focusedContainerColor = Color(0xFFFF5252),
                                    focusedContentColor = Color.White
                                )
                            ) { Text("完全に削除する", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }

        if (showAdvancedSettings) {
            AdvancedSettingsDialog(
                initialExcludeKeyword = advExcludeKeyword,
                initialIsTitleOnly = advIsTitleOnly,
                initialBroadcastType = advBroadcastType,
                initialIsFuzzySearch = advIsFuzzySearch,
                initialDuplicateScope = advDuplicateScope,
                initialPriority = advPriority,
                initialIsEventRelay = advIsEventRelay,
                initialIsExactRecord = advIsExactRecord,
                onConfirm = { exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->
                    advExcludeKeyword = exc; advIsTitleOnly = tOnly; advBroadcastType = bType
                    advIsFuzzySearch = fuzzy; advDuplicateScope = dup; advPriority = pri
                    advIsEventRelay = relay; advIsExactRecord = exact
                    showAdvancedSettings = false
                    scope.launch { delay(100); runCatching { advancedBtnRequester.requestFocus() } }
                },
                onDismiss = {
                    showAdvancedSettings = false
                    scope.launch { delay(100); runCatching { advancedBtnRequester.requestFocus() } }
                }
            )
        }

        if (showDayOfWeekDialog) {
            DayOfWeekSelectionDialog(
                initialSelection = selectedDaysOfWeek,
                onConfirm = {
                    if (it.isNotEmpty()) selectedDaysOfWeek = it
                    showDayOfWeekDialog = false
                    scope.launch { delay(100); runCatching { dayOfWeekBtnRequester.requestFocus() } }
                },
                onDismiss = {
                    showDayOfWeekDialog = false
                    scope.launch { delay(100); runCatching { dayOfWeekBtnRequester.requestFocus() } }
                }
            )
        }

        if (numberSelectTarget != null) {
            val range =
                if (numberSelectTarget == NumberSelectType.START_HOUR ||
                    numberSelectTarget == NumberSelectType.END_HOUR
                ) 0..23 else 0..59
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