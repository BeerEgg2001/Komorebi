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
    var isEnabled by remember { mutableStateOf(initialSettings.isEnabled) }
    var priority by remember { mutableIntStateOf(initialSettings.priority) }
    var isEventRelay by remember { mutableStateOf(initialSettings.isEventRelayFollowEnabled) }
    var recordingMode by remember { mutableStateOf(initialSettings.recordingMode) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)) // 少し濃く
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(550.dp),
            shape = RoundedCornerShape(8.dp), // 少し角を立たせてシャープに
            colors = SurfaceDefaults.colors(
                containerColor = Color(0xFF1A1A1A), // 深いグレー
                contentColor = Color.White
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = if (isNewReservation) "詳細予約設定" else "予約設定の変更",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = NotoSansJP
                )

                Text(
                    text = programTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFAAAAAA),
                    maxLines = 1
                )

                Divider(color = Color.White.copy(alpha = 0.1f))

                // 1. 予約有効/無効
                SettingRow(label = "予約を有効にする") {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it },
                        modifier = Modifier.focusRequester(focusRequester),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White, // モノトーン：ONは白
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }

                // 2. 優先度 (1-5)
                SettingRow(label = "優先度 (1-5)") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { if (priority > 1) priority-- },
                            enabled = priority > 1
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "下げる", tint = if (priority > 1) Color.White else Color.DarkGray)
                        }

                        Text(
                            text = priority.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(30.dp),
                            textAlign = TextAlign.Center
                        )

                        IconButton(
                            onClick = { if (priority < 5) priority++ },
                            enabled = priority < 5
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "上げる", tint = if (priority < 5) Color.White else Color.DarkGray)
                        }
                    }
                }

                // 3. 録画モード
                SettingRow(label = "録画モード") {
                    val modeLabel = when(recordingMode) {
                        "SpecifiedService" -> "指定サービス"
                        "AllService" -> "全サービス"
                        "Service1Seg" -> "ワンセグのみ"
                        else -> recordingMode
                    }

                    Button(
                        onClick = {
                            recordingMode = when(recordingMode) {
                                "SpecifiedService" -> "AllService"
                                "AllService" -> "Service1Seg"
                                else -> "SpecifiedService"
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(text = modeLabel)
                    }
                }

                // 4. イベントリレー
                SettingRow(label = "イベントリレー追従") {
                    Switch(
                        checked = isEventRelay,
                        onCheckedChange = { isEventRelay = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                // アクションボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFF333333),
                            focusedContentColor = Color.White
                        )
                    ) {
                        Text("キャンセル")
                    }

                    Button(
                        onClick = {
                            val newSettings = initialSettings.copy(
                                isEnabled = isEnabled,
                                priority = priority,
                                recordingMode = recordingMode,
                                isEventRelayFollowEnabled = isEventRelay
                            )
                            onConfirm(newSettings)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            focusedContainerColor = Color(0xFFCCCCCC),
                            focusedContentColor = Color.Black
                        )
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}