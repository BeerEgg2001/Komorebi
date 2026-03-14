package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AdvancedSettingsDialog(
    initialExcludeKeyword: String,
    initialIsTitleOnly: Boolean,
    initialBroadcastType: String,
    initialIsFuzzySearch: Boolean,
    initialDuplicateScope: String,
    initialPriority: Int,
    initialIsEventRelay: Boolean,
    initialIsExactRecord: Boolean,
    onConfirm: (
        excludeKeyword: String, isTitleOnly: Boolean, broadcastType: String,
        isFuzzySearch: Boolean, duplicateScope: String, priority: Int,
        isEventRelay: Boolean, isExactRecord: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var excludeKeyword by remember { mutableStateOf(initialExcludeKeyword) }
    var isEditingExclude by remember { mutableStateOf(false) }
    var isTitleOnly by remember { mutableStateOf(initialIsTitleOnly) }
    var broadcastType by remember { mutableStateOf(initialBroadcastType) }
    var isFuzzySearch by remember { mutableStateOf(initialIsFuzzySearch) }
    var duplicateScope by remember { mutableStateOf(initialDuplicateScope) }
    var priority by remember { mutableIntStateOf(initialPriority) }
    var isEventRelay by remember { mutableStateOf(initialIsEventRelay) }
    var isExactRecord by remember { mutableStateOf(initialIsExactRecord) }

    val firstItemRequester = remember { FocusRequester() }
    val excludeFieldRequester = remember { FocusRequester() }
    val safeFocusRequester = remember { FocusRequester() }
    val inverseColor = if (colors.isDark) Color.Black else Color.White

    var isFirstEnter by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { firstItemRequester.requestFocus() }
    }

    LaunchedEffect(isEditingExclude) {
        if (!isEditingExclude && !isFirstEnter) {
            delay(150)
            runCatching { firstItemRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .zIndex(200f)
            // ★根本修正: 不用意な focusGroup や複雑な focusProperties の exit ロジックを全削除
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (isEditingExclude) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        isEditingExclude = false
                    } else {
                        onDismiss()
                    }
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        // キーボード消滅時の安全なフォーカス退避場所
        Box(
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(safeFocusRequester)
                .focusable()
        )

        Surface(
            modifier = Modifier
                .width(620.dp)
                .heightIn(max = 780.dp)
                .focusProperties {
                    enter = {
                        if (isFirstEnter) {
                            isFirstEnter = false
                            firstItemRequester
                        } else {
                            FocusRequester.Default
                        }
                    }
                },
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = colors.surface,
                contentColor = colors.textPrimary
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "詳細設定",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Divider(
                    color = colors.textPrimary.copy(alpha = 0.1f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // ==========================================
                // スクロール可能な設定リスト領域
                // ==========================================
                // ★根本修正: focusGroup() や focusProperties を撤去。純粋なスクロールコンテナにする。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "検索条件",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )

                    if (isEditingExclude) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            OutlinedTextField(
                                value = excludeKeyword,
                                onValueChange = { excludeKeyword = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(excludeFieldRequester),
                                textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    isEditingExclude = false
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.accent,
                                    unfocusedBorderColor = colors.textSecondary,
                                    cursorColor = colors.accent
                                )
                            )
                        }
                        LaunchedEffect(Unit) { delay(50); runCatching { excludeFieldRequester.requestFocus() }; keyboardController?.show() }
                    } else {
                        AdvancedSettingRow(
                            label = "除外キーワード",
                            modifier = Modifier.focusRequester(firstItemRequester),
                            onClick = { isEditingExclude = true }
                        ) {
                            Text(
                                text = excludeKeyword.ifEmpty { "(なし)" },
                                color = colors.accent,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    AdvancedSettingRow(label = "番組名のみ検索") {
                        Switch(
                            checked = isTitleOnly,
                            onCheckedChange = { isTitleOnly = it },
                            colors = switchColors()
                        )
                    }

                    val broadcastLabel = when (broadcastType) {
                        "FreeOnly" -> "無料放送のみ"
                        "PaidOnly" -> "有料放送のみ"
                        else -> "すべて (無料・有料)"
                    }
                    AdvancedSettingRow(label = "対象放送", onClick = {
                        broadcastType = when (broadcastType) {
                            "All" -> "FreeOnly"
                            "FreeOnly" -> "PaidOnly"
                            else -> "All"
                        }
                    }) {
                        Text(
                            text = broadcastLabel,
                            color = colors.accent,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    AdvancedSettingRow(label = "あいまい検索") {
                        Switch(
                            checked = isFuzzySearch,
                            onCheckedChange = { isFuzzySearch = it },
                            colors = switchColors()
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "録画制御",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )

                    val scopeLabel = when (duplicateScope) {
                        "None" -> "しない"
                        "SameChannelOnly" -> "同一チャンネルのみ"
                        else -> "全チャンネル"
                    }
                    AdvancedSettingRow(label = "重複録画の回避", onClick = {
                        duplicateScope = when (duplicateScope) {
                            "None" -> "SameChannelOnly"
                            "SameChannelOnly" -> "AllChannels"
                            else -> "None"
                        }
                    }) {
                        Text(
                            text = scopeLabel,
                            color = colors.accent,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    AdvancedSettingRow(label = "優先度 (1-5)") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PriorityCircleButton(
                                icon = Icons.Default.Remove,
                                enabled = priority > 1,
                                onClick = { priority-- }
                            )
                            Text(
                                text = priority.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(30.dp),
                                textAlign = TextAlign.Center
                            )
                            PriorityCircleButton(
                                icon = Icons.Default.Add,
                                enabled = priority < 5,
                                onClick = { priority++ }
                            )
                        }
                    }

                    AdvancedSettingRow(label = "イベントリレー追従") {
                        Switch(
                            checked = isEventRelay,
                            onCheckedChange = { isEventRelay = it },
                            colors = switchColors()
                        )
                    }

                    AdvancedSettingRow(label = "ぴったり録画") {
                        Switch(
                            checked = isExactRecord,
                            onCheckedChange = { isExactRecord = it },
                            colors = switchColors()
                        )
                    }
                } // End Scrollable Column

                Spacer(Modifier.height(16.dp))

                // ==========================================
                // 下部ボタン領域
                // ==========================================
                // ★根本修正: 余計な focusGroup や focusProperties を撤去。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)
                ) {
                    Button(
                        onClick = onDismiss,
                        scale = ButtonDefaults.scale(focusedScale = 1.05f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = colors.textSecondary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = inverseColor
                        )
                    ) { Text("キャンセル", fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            onConfirm(
                                excludeKeyword,
                                isTitleOnly,
                                broadcastType,
                                isFuzzySearch,
                                duplicateScope,
                                priority,
                                isEventRelay,
                                isExactRecord
                            )
                        },
                        scale = ButtonDefaults.scale(focusedScale = 1.05f),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.15f),
                            contentColor = colors.textPrimary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = inverseColor
                        )
                    ) { Text("設定を適用", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/**
 * ＋ーボタンのフォーカス状態を明確にしたTV用ボタン
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PriorityCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.1f),
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        modifier = Modifier.size(32.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                icon,
                null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else colors.textSecondary.copy(
                    alpha = 0.4f
                )
            )
        }
    }
}

/**
 * ReserveSettingsDialog の SettingRow とデザインを完全に合わせたコンポーネント
 */
@Composable
fun AdvancedSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isRowFocused by remember { mutableStateOf(false) }

    val backgroundColor by animateColorAsState(if (isRowFocused) colors.textPrimary.copy(alpha = 0.15f) else Color.Transparent)
    val scale by animateFloatAsState(if (isRowFocused) 1.05f else 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .onFocusChanged { isRowFocused = it.hasFocus }
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isRowFocused) FontWeight.Bold else FontWeight.Normal
        )
        content()
    }
}

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = if (KomorebiTheme.colors.isDark) Color.Black else Color.White,
    checkedTrackColor = KomorebiTheme.colors.accent,
    uncheckedThumbColor = KomorebiTheme.colors.textPrimary,
    uncheckedTrackColor = KomorebiTheme.colors.textPrimary.copy(alpha = 0.2f)
)