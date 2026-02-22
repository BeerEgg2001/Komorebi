package com.beeregg2001.komorebi.ui.reserve

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ReserveSettingsDialog(
    programTitle: String,
    initialSettings: ReserveRecordSettings,
    isNewReservation: Boolean,
    onConfirm: (ReserveRecordSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isEnabled by remember { mutableStateOf(initialSettings.isEnabled) }
    var priority by remember { mutableIntStateOf(initialSettings.priority) }
    var isEventRelay by remember { mutableStateOf(initialSettings.isEventRelayFollowEnabled) }
    var recordingMode by remember { mutableStateOf("SpecifiedService") }

    val focusRequester = remember { FocusRequester() }

    // ★追加: ダークモード・ライトモードで文字色と背景色を確実に反転させるための色
    val inverseColor = if (colors.isDark) Color.Black else Color.White

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(600.dp),
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = colors.surface,
                contentColor = colors.textPrimary
            )
        ) {
            Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                Divider(color = colors.textPrimary.copy(alpha = 0.1f), modifier = Modifier.padding(bottom = 8.dp))

                SettingRow(label = "予約を有効にする") {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it },
                        modifier = Modifier.focusRequester(focusRequester),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (colors.isDark) Color.Black else Color.White,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.textPrimary.copy(alpha = 0.2f)
                        )
                    )
                }

                SettingRow(label = "優先度 (1-5)") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { if (priority > 1) priority-- }, enabled = priority > 1) {
                            Icon(Icons.Default.Remove, null, tint = if (priority > 1) colors.textPrimary else colors.textSecondary)
                        }
                        Text(
                            text = priority.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(30.dp),
                            textAlign = TextAlign.Center
                        )
                        IconButton(onClick = { if (priority < 5) priority++ }, enabled = priority < 5) {
                            Icon(Icons.Default.Add, null, tint = if (priority < 5) colors.textPrimary else colors.textSecondary)
                        }
                    }
                }

                SettingRow(label = "録画モード") {
                    Button(
                        onClick = { /* 固定 */ },
                        enabled = false,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textSecondary
                        )
                    ) {
                        Text(text = "指定サービス")
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

                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                    // ★修正: キャンセルボタン。フォーカス時に明確に反転する
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

                    // ★修正: 確定ボタン。通常時から少し目立たせ、フォーカス時は完全に反転させて同化を防ぐ
                    Button(
                        onClick = {
                            val newSettings = initialSettings.copy(
                                isEnabled = isEnabled,
                                priority = priority,
                                recordingMode = "SpecifiedService",
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
                        Text(if (isNewReservation) "録画予約" else "設定を更新", fontWeight = FontWeight.Bold)
                    }
                }
            }
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