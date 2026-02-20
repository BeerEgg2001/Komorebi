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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加
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
    // ★修正: 初期値を "SpecifiedService" に固定
    var recordingMode by remember { mutableStateOf("SpecifiedService") }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(550.dp),
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(
                // ★修正: Color(0xFF1A1A1A) -> colors.surface
                containerColor = colors.surface,
                contentColor = colors.textPrimary
            )
        ) {
            Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(text = if (isNewReservation) "詳細予約設定" else "予約設定の変更", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontFamily = NotoSansJP)
                Text(text = programTitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1)
                // ★修正: Dividerの色
                Divider(color = colors.textPrimary.copy(alpha = 0.1f))

                SettingRow(label = "予約を有効にする") {
                    Switch(checked = isEnabled,
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
                        IconButton(onClick = { if (priority > 1) priority-- }, enabled = priority > 1) { Icon(Icons.Default.Remove, null, tint = if (priority > 1) colors.textPrimary else colors.textSecondary) }
                        Text(text = priority.toString(), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                        IconButton(onClick = { if (priority < 5) priority++ }, enabled = priority < 5) { Icon(Icons.Default.Add, null, tint = if (priority < 5) colors.textPrimary else colors.textSecondary) }
                    }
                }

                SettingRow(label = "録画モード") {
                    // ★修正: 指定サービス以外選べないようにし、表示のみとする
                    Button(
                        onClick = { /* 固定 */ },
                        enabled = false, // 変更不可にする
                        colors = ButtonDefaults.colors(containerColor = colors.textPrimary.copy(alpha = 0.1f), contentColor = Color.Gray)
                    ) {
                        Text(text = "指定サービス")
                    }
                }

                SettingRow(label = "イベントリレー追従") {
                    Switch(checked = isEventRelay, onCheckedChange = { isEventRelay = it }, colors = SwitchDefaults.colors(
                        checkedThumbColor = if (colors.isDark) Color.Black else Color.White,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.textPrimary,
                        uncheckedTrackColor = colors.textPrimary.copy(alpha = 0.2f)
                    ))
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White, focusedContainerColor = Color(0xFF333333), focusedContentColor = Color.White)) { Text("キャンセル") }
                    Button(
                        onClick = {
                            // ★修正: copy 時も recordingMode を強制上書き
                            val newSettings = initialSettings.copy(
                                isEnabled = isEnabled,
                                priority = priority,
                                recordingMode = "SpecifiedService",
                                isEventRelayFollowEnabled = isEventRelay
                            )
                            onConfirm(newSettings)
                        },
                        colors = ButtonDefaults.colors(containerColor = Color.White, contentColor = Color.Black, focusedContainerColor = Color(0xFFCCCCCC), focusedContentColor = Color.Black)
                    ) {
                        Text(if (isNewReservation) "録画予約" else "設定を更新")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}