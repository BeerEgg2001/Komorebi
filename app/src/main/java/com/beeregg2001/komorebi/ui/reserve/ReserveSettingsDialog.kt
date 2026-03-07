package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.ui.setting.SelectionDialog
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.PostRecordingBatch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ReserveSettingsDialog(
    programTitle: String,
    initialSettings: ReserveRecordSettings,
    isNewReservation: Boolean,
    onConfirm: (ReserveRecordSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val colors = KomorebiTheme.colors
    val gson = remember { Gson() }

    var isEnabled by remember { mutableStateOf(initialSettings.isEnabled) }
    var priority by remember { mutableIntStateOf(initialSettings.priority) }
    var isEventRelay by remember { mutableStateOf(initialSettings.isEventRelayFollowEnabled) }

    var selectedBatPath by remember { mutableStateOf(initialSettings.postRecordingBatFilePath) }
    var isBatSelectionOpen by remember { mutableStateOf(false) }

    val batchListJson = repository.postRecordingBatchList.collectAsState(initial = "[]").value
    val batchList = remember(batchListJson) {
        try {
            val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
            gson.fromJson<List<PostRecordingBatch>>(batchListJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val firstItemFocusRequester = remember { FocusRequester() }
    val batSelectionFocusRequester = remember { FocusRequester() }
    val inverseColor = if (colors.isDark) Color.Black else Color.White

    // 初回起動時の判定フラグ
    var isFirstEnter by remember { mutableStateOf(true) }

    // 起動時の初期フォーカス設定
    LaunchedEffect(Unit) {
        delay(50)
        firstItemFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusRestorer()
            .focusGroup()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (isBatSelectionOpen) {
                        isBatSelectionOpen = false
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
                .width(600.dp)
                .focusProperties {
                    enter = {
                        if (isFirstEnter) {
                            isFirstEnter = false
                            firstItemFocusRequester
                        } else {
                            FocusRequester.Default
                        }
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isNewReservation) "詳細予約設定" else "予約設定の変更",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = NotoSansJP,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(
                    text = programTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                )

                Divider(
                    color = colors.textPrimary.copy(alpha = 0.1f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                SettingRow(label = "予約を有効にする") {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it },
                        modifier = Modifier.focusRequester(firstItemFocusRequester),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (colors.isDark) Color.Black else Color.White,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.textPrimary.copy(alpha = 0.2f)
                        )
                    )
                }

                SettingRow(label = "優先度 (1-5)") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { if (priority > 1) priority-- },
                            enabled = priority > 1
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                null,
                                tint = if (priority > 1) colors.textPrimary else colors.textSecondary
                            )
                        }
                        Text(
                            text = priority.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(30.dp),
                            textAlign = TextAlign.Center
                        )
                        IconButton(
                            onClick = { if (priority < 5) priority++ },
                            enabled = priority < 5
                        ) {
                            Icon(
                                Icons.Default.Add,
                                null,
                                tint = if (priority < 5) colors.textPrimary else colors.textSecondary
                            )
                        }
                    }
                }

                SettingRow(label = "録画後実行バッチ") {
                    val currentBatName =
                        batchList.find { it.path == selectedBatPath }?.name ?: "なし"
                    Button(
                        onClick = { isBatSelectionOpen = true },
                        modifier = Modifier.focusRequester(batSelectionFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary
                        )
                    ) {
                        Text(text = currentBatName)
                    }
                }

                SettingRow(label = "イベントリレー追従") {
                    Switch(
                        checked = isEventRelay,
                        onCheckedChange = { isEventRelay = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (colors.isDark) Color.Black else Color.White,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.textPrimary.copy(alpha = 0.2f)
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = colors.textSecondary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = inverseColor
                        )
                    ) {
                        Text("キャンセル", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val newSettings = initialSettings.copy(
                                isEnabled = isEnabled,
                                priority = priority,
                                recordingMode = "SpecifiedService",
                                postRecordingBatFilePath = selectedBatPath,
                                isEventRelayFollowEnabled = isEventRelay
                            )
                            onConfirm(newSettings)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.15f),
                            contentColor = colors.textPrimary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = inverseColor
                        )
                    ) {
                        Text(
                            if (isNewReservation) "録画予約" else "設定を更新",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // バッチ選択用ダイアログ
        if (isBatSelectionOpen) {
            val options = listOf("なし" to null) + batchList.map { it.name to it.path }
            SelectionDialog(
                title = "実行するバッチを選択",
                options = options.map { it.first to (it.second ?: "") },
                current = selectedBatPath ?: "",
                onDismiss = {
                    // ★トラップが解除されているので、今度は確実にボタンへフォーカスが移ります
                    batSelectionFocusRequester.requestFocus()
                    isBatSelectionOpen = false
                },
                onSelect = {
                    selectedBatPath = if (it.isEmpty()) null else it
                    // ★選択完了時も同様です
                    batSelectionFocusRequester.requestFocus()
                    isBatSelectionOpen = false
                }
            )
        }
    }
}

@Composable
fun SettingRow(label: String, content: @Composable () -> Unit) {
    val colors = KomorebiTheme.colors
    var isRowFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isRowFocused) colors.textPrimary.copy(alpha = 0.15f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { isRowFocused = it.hasFocus }
            .padding(horizontal = 16.dp, vertical = 12.dp),
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