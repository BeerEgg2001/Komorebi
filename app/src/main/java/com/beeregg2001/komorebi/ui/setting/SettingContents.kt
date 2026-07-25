@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.PostRecordingBatch
import com.beeregg2001.komorebi.viewmodel.SmbServer
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
private fun ValidationErrorText(message: String) {
    Row(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Error",
            tint = Color(0xFFE53935),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE53935),
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GeneralSettingsContent(
    totalRecordCount: Int,
    lastSyncedAt: Long,
    receiveBetaUpdates: Boolean,
    onToggleBetaUpdates: (Boolean) -> Unit,
    betaUpdateR: FocusRequester,
    onForceSync: () -> Unit,
    onClearChannel: () -> Unit,
    onClearHistory: () -> Unit,
    dbInfoR: FocusRequester,
    forceSyncR: FocusRequester,
    clearChannelR: FocusRequester,
    clearHistoryR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    val dateFormat =
        remember { java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()) }
    val lastSyncStr =
        if (lastSyncedAt > 0L) dateFormat.format(java.util.Date(lastSyncedAt)) else "未同期"

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_GENERAL,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("システム設定") {
            SettingItem(
                title = "ベータ版のアップデートを受け取る",
                value = if (receiveBetaUpdates) "ON" else "OFF",
                icon = Icons.Default.SystemUpdate,
                modifier = Modifier
                    .focusRequester(betaUpdateR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = dbInfoR
                    },
                onClick = { onClick(betaUpdateR); onToggleBetaUpdates(!receiveBetaUpdates) }
            )
        }

        SettingsSection("データベース情報") {
            SettingItem(
                title = "ローカル保存件数",
                value = "$totalRecordCount 件",
                icon = Icons.Default.Storage,
                modifier = Modifier
                    .focusRequester(dbInfoR)
                    .focusProperties {
                        left = sidebarR
                        up = betaUpdateR
                        down = forceSyncR
                    },
                onClick = { onClick(dbInfoR) }
            )
            SettingItem(
                title = "手動でフル同期を実行",
                value = "最終同期: $lastSyncStr",
                icon = Icons.Default.CloudSync,
                modifier = Modifier
                    .focusRequester(forceSyncR)
                    .focusProperties {
                        left = sidebarR
                        up = dbInfoR
                        down = clearChannelR
                    },
                onClick = { onClick(forceSyncR); onForceSync() }
            )
        }

        SettingsSection(AppStrings.SETTINGS_SECTION_DATA_MANAGEMENT) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_CLEAR_CHANNEL_HISTORY,
                "",
                Icons.Default.History,
                modifier = Modifier
                    .focusRequester(clearChannelR)
                    .focusProperties {
                        left = sidebarR
                        up = forceSyncR
                        down = clearHistoryR
                    },
                onClick = { onClick(clearChannelR); onClearChannel() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_CLEAR_WATCH_HISTORY,
                "",
                Icons.Default.DeleteSweep,
                modifier = Modifier
                    .focusRequester(clearHistoryR)
                    .focusProperties {
                        left = sidebarR
                        up = clearChannelR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(clearHistoryR); onClearHistory() })
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecordingSettingsContent(
    batchList: List<PostRecordingBatch>,
    onAdd: () -> Unit,
    onDelete: (PostRecordingBatch) -> Unit,
    addR: FocusRequester,
    itemRs: List<FocusRequester>,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            "録画設定",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("録画後実行バッチの設定") {
            SettingItem(
                title = "新しいバッチを追加",
                value = "",
                icon = Icons.Default.Add,
                modifier = Modifier
                    .focusRequester(addR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down =
                            if (batchList.isEmpty()) FocusRequester.Cancel else itemRs.firstOrNull()
                                ?: FocusRequester.Cancel
                    },
                onClick = { onClick(addR); onAdd() }
            )

            if (batchList.isEmpty()) {
                Text(
                    "登録されたバッチはありません",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary.copy(0.6f)
                )
            } else {
                batchList.forEachIndexed { index, batch ->
                    val requester = itemRs.getOrNull(index) ?: remember { FocusRequester() }
                    val isLast = index == batchList.lastIndex
                    SettingItem(
                        title = batch.name,
                        value = "削除",
                        icon = Icons.Default.Terminal,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                left = sidebarR
                                up = if (index == 0) addR else itemRs[index - 1]
                                down = if (isLast) FocusRequester.Cancel else itemRs[index + 1]
                            },
                        onClick = { onClick(requester); onDelete(batch) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConnectionSettingsContent(
    backendType: String,
    edcbIp: String,
    edcbPort: String,
    edcbHttpPort: String,
    epgStationIp: String,
    epgStationPort: String,
    kIp: String,
    kPort: String,
    mIp: String,
    mPort: String,
    prefSrc: String,
    edcbPlayMethod: String,
    smbServerList: List<SmbServer>,
    onAddSmbServer: () -> Unit,
    onSmbServerClick: (SmbServer) -> Unit,
    addSmbR: FocusRequester,
    smbItemRs: List<FocusRequester>,
    onSelectEdcbPlayMethod: () -> Unit,
    edcbPlayMethodR: FocusRequester,
    cfClientId: String,
    cfClientSecret: String,
    onEdit: (String, String) -> Unit,
    onSelectBackend: () -> Unit,
    onSelectSrc: () -> Unit,
    backendTypeR: FocusRequester,
    backendIpR: FocusRequester,
    backendPortR: FocusRequester,
    edcbHttpPortR: FocusRequester,
    prefSrcR: FocusRequester,
    overrideIpR: FocusRequester,
    overridePortR: FocusRequester,
    cfIdR: FocusRequester,
    cfSecretR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    val colors = KomorebiTheme.colors

    // ★ 修正: ループによるフォーカスの点滅（奪い合い）を防ぐため、
    // レイアウトの安定（ダイアログの退出アニメーション等）を350msだけ静かに待ち、
    // その直後に「1発だけ」確実なフォーカス要求を行うスマートな方式に変更。
    var previousBackendType by remember { mutableStateOf(backendType) }
    LaunchedEffect(backendType) {
        if (previousBackendType != backendType) {
            previousBackendType = backendType
            delay(350)
            try {
                backendTypeR.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    var previousPrefSrc by remember { mutableStateOf(prefSrc) }
    LaunchedEffect(prefSrc) {
        if (previousPrefSrc != prefSrc) {
            previousPrefSrc = prefSrc
            delay(350)
            try {
                prefSrcR.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_CONNECTION,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("メインシステム設定") {
            val backendLabel = when (backendType) {
                "EDCB" -> "EDCB (EpgTimerSrv)"
                "MIRAKURUN_ONLY" -> "Mirakurun (録画なし)"
                else -> "KonomiTV"
            }

            SettingItem(
                title = "利用するシステム",
                value = backendLabel,
                icon = Icons.Default.Dns,
                modifier = Modifier
                    .focusRequester(backendTypeR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = backendIpR
                    },
                onClick = { onClick(backendTypeR); onSelectBackend() }
            )

            val currentIp = when (backendType) {
                "EDCB" -> edcbIp
                "MIRAKURUN_ONLY" -> mIp
                else -> kIp
            }
            val currentPort = when (backendType) {
                "EDCB" -> edcbPort
                "MIRAKURUN_ONLY" -> mPort
                else -> kPort
            }
            val ipTitle = when (backendType) {
                "EDCB" -> "EDCB (IPアドレス)"
                "MIRAKURUN_ONLY" -> "Mirakurun (IPアドレス)"
                else -> "KonomiTV (IPアドレス)"
            }
            val portTitle = when (backendType) {
                "EDCB" -> "EDCB (TCPポート)"
                "MIRAKURUN_ONLY" -> "Mirakurun (ポート)"
                else -> "KonomiTV (ポート)"
            }
            val edcbHttpPortTitle = "EDCB (HTTP/HTTPSポート)"

            SettingItem(
                title = ipTitle,
                value = currentIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                icon = Icons.Default.Link,
                modifier = Modifier
                    .focusRequester(backendIpR)
                    .focusProperties { left = sidebarR; up = backendTypeR; down = backendPortR },
                onClick = { onClick(backendIpR); onEdit(ipTitle, currentIp) }
            )
            SettingItem(
                title = portTitle,
                value = currentPort,
                icon = Icons.Default.Numbers,
                modifier = Modifier
                    .focusRequester(backendPortR)
                    .focusProperties {
                        left = sidebarR; up = backendIpR;
                        down =
                            if (backendType == "EDCB") edcbHttpPortR else if (backendType != "MIRAKURUN_ONLY") prefSrcR else addSmbR
                    },
                onClick = { onClick(backendPortR); onEdit(portTitle, currentPort) }
            )
            if (backendType == "EDCB") {
                SettingItem(
                    title = edcbHttpPortTitle,
                    value = edcbHttpPort,
                    icon = Icons.Default.Numbers,
                    modifier = Modifier
                        .focusRequester(edcbHttpPortR)
                        .focusProperties {
                            left = sidebarR
                            up = backendPortR
                            down =
                                if (backendType == "EDCB") edcbPlayMethodR else if (backendType != "MIRAKURUN_ONLY") prefSrcR else addSmbR
                        },
                    onClick = { onClick(edcbHttpPortR); onEdit(edcbHttpPortTitle, edcbHttpPort) }
                )
            }

            if (currentIp.isBlank() || currentPort.isBlank()) {
                ValidationErrorText("メインシステムのIPアドレスまたはポート番号が未設定です。\n番組情報の取得や録画機能が正常に動作しません。")
            }
        }

        if (backendType == "EDCB") {
            SettingsSection("EDCB 録画再生設定") {
                SettingItem(
                    title = "録画ファイルの再生方式",
                    value = if (edcbPlayMethod == "DIRECT") "直接アクセス (高速シーク可)" else "API経由 (api/xcode)",
                    icon = Icons.Default.PlayCircleOutline,
                    modifier = Modifier
                        .focusRequester(edcbPlayMethodR)
                        .focusProperties {
                            left = sidebarR
                            up = edcbHttpPortR
                            down = prefSrcR
                        },
                    onClick = { onClick(edcbPlayMethodR); onSelectEdcbPlayMethod() }
                )
            }
        }

        val hasOverride = prefSrc == "MIRAKURUN" || (prefSrc == "EDCB" && backendType != "EDCB")
        if (backendType != "MIRAKURUN_ONLY") {
            SettingsSection("ライブ視聴ソースの優先設定") {
                val srcLabel = when {
                    prefSrc == "MIRAKURUN" -> "Mirakurun を優先"
                    prefSrc == "EDCB" && backendType != "EDCB" -> "EDCB (TCP) を優先"
                    prefSrc == "EDCB" && backendType == "EDCB" -> "EDCB (ダイレクトストリーミング)"
                    else -> "メインシステムに従う（トランスコード）"
                }

                SettingItem(
                    title = "優先ソース",
                    value = srcLabel,
                    icon = Icons.Default.PriorityHigh,
                    modifier = Modifier
                        .focusRequester(prefSrcR)
                        .focusProperties {
                            left = sidebarR
                            up = if (backendType == "EDCB") edcbPlayMethodR else backendPortR
                            down = if (hasOverride) overrideIpR else addSmbR
                        },
                    onClick = { onClick(prefSrcR); onSelectSrc() }
                )
            }

            if (prefSrc == "MIRAKURUN") {
                SettingsSection("Mirakurun 接続設定") {
                    SettingItem(
                        title = "IPアドレス (Mirakurun)",
                        value = mIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                        icon = Icons.Default.Router,
                        modifier = Modifier
                            .focusRequester(overrideIpR)
                            .focusProperties {
                                left = sidebarR; up = prefSrcR; down = overridePortR
                            },
                        onClick = { onClick(overrideIpR); onEdit("Mirakurun (IPアドレス)", mIp) }
                    )
                    SettingItem(
                        title = "ポート番号 (Mirakurun)",
                        value = mPort,
                        icon = Icons.Default.Numbers,
                        modifier = Modifier
                            .focusRequester(overridePortR)
                            .focusProperties {
                                left = sidebarR; up = overrideIpR; down = addSmbR
                            },
                        onClick = { onClick(overridePortR); onEdit("Mirakurun (ポート)", mPort) }
                    )

                    if (mIp.isBlank() || mPort.isBlank()) {
                        ValidationErrorText("優先ソース（Mirakurun）のIPアドレスまたはポート番号が未設定です。\nこのままではライブ視聴機能が正常に動作しません。")
                    }
                }
            } else if (prefSrc == "EDCB" && backendType != "EDCB") {
                SettingsSection("EDCB 接続設定") {
                    SettingItem(
                        title = "IPアドレス (EDCB)",
                        value = edcbIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                        icon = Icons.Default.Router,
                        modifier = Modifier
                            .focusRequester(overrideIpR)
                            .focusProperties {
                                left = sidebarR; up = prefSrcR; down = overridePortR
                            },
                        onClick = { onClick(overrideIpR); onEdit("EDCB (IPアドレス)", edcbIp) }
                    )
                    SettingItem(
                        title = "ポート番号 (EDCB)",
                        value = edcbPort,
                        icon = Icons.Default.Numbers,
                        modifier = Modifier
                            .focusRequester(overridePortR)
                            .focusProperties {
                                left = sidebarR; up = overrideIpR; down = addSmbR
                            },
                        onClick = { onClick(overridePortR); onEdit("EDCB (ポート)", edcbPort) }
                    )

                    if (edcbIp.isBlank() || edcbPort.isBlank()) {
                        ValidationErrorText("優先ソース（EDCB）のIPアドレスまたはポート番号が未設定です。\nこのままではライブ視聴機能が正常に動作しません。")
                    }
                }
            }
        }

        SettingsSection("ファイルライブラリ (SMB) 接続設定") {
            SettingItem(
                title = "新しいSMBサーバーを追加",
                value = "",
                icon = Icons.Default.Add,
                modifier = Modifier
                    .focusRequester(addSmbR)
                    .focusProperties {
                        left = sidebarR
                        up = if (hasOverride) overridePortR else prefSrcR
                        down =
                            if (smbServerList.isEmpty()) cfIdR else smbItemRs.firstOrNull()
                                ?: cfIdR
                    },
                onClick = { onClick(addSmbR); onAddSmbServer() }
            )

            if (smbServerList.isEmpty()) {
                Text(
                    "登録されたSMBサーバーはありません",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary.copy(0.6f)
                )
            } else {
                smbServerList.forEachIndexed { index, server ->
                    val requester = smbItemRs.getOrNull(index) ?: remember { FocusRequester() }
                    val isLast = index == smbServerList.lastIndex
                    SettingItem(
                        title = server.name,
                        value = server.ip,
                        icon = Icons.Default.Storage, // ドライブ風のアイコン
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                left = sidebarR
                                up = if (index == 0) addSmbR else smbItemRs[index - 1]
                                down = if (isLast) cfIdR else smbItemRs[index + 1]
                            },
                        onClick = { onClick(requester); onSmbServerClick(server) }
                    )
                }
            }
        }

        SettingsSection(AppStrings.SETTINGS_SECTION_CLOUDFLARE) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_CF_CLIENT_ID,
                cfClientId.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                Icons.Default.Cloud,
                modifier = Modifier
                    .focusRequester(cfIdR)
                    .focusProperties {
                        left = sidebarR
                        up = if (smbServerList.isEmpty()) addSmbR else smbItemRs.getOrNull(
                            smbServerList.lastIndex
                        ) ?: addSmbR
                        down = cfSecretR
                    },
                onClick = {
                    onClick(cfIdR); onEdit(
                    AppStrings.SETTINGS_INPUT_CF_CLIENT_ID,
                    cfClientId
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_CF_CLIENT_SECRET,
                if (cfClientSecret.isEmpty()) AppStrings.SETTINGS_VALUE_UNSET else AppStrings.SETTINGS_VALUE_CF_SET,
                Icons.Default.Key,
                modifier = Modifier
                    .focusRequester(cfSecretR)
                    .focusProperties {
                        left = sidebarR
                        up = cfIdR
                    },
                onClick = {
                    onClick(cfSecretR); onEdit(
                    AppStrings.SETTINGS_INPUT_CF_CLIENT_SECRET,
                    cfClientSecret
                )
                })
        }
    }
}

@Composable
fun PlaybackSettingsContent(
    liveQ: String,
    videoQ: String,
    liveSub: String,
    videoSub: String,
    layerOrder: String,
    audioMode: String,
    uiMode: String,
    autoCmSkip: String,
    availableQualities: List<StreamQuality>,
    liveR: FocusRequester,
    videoR: FocusRequester,
    liveSubR: FocusRequester,
    videoSubR: FocusRequester,
    audioR: FocusRequester,
    layerR: FocusRequester,
    uiModeR: FocusRequester,
    autoCmSkipR: FocusRequester,
    sidebarR: FocusRequester,
    onL: () -> Unit,
    onV: () -> Unit,
    onLiveSub: () -> Unit,
    onVideoSub: () -> Unit,
    onAudioMode: () -> Unit,
    onLayer: () -> Unit,
    onUiMode: () -> Unit,
    onAutoCmSkip: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_PLAYBACK,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_QUALITY) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_LIVE_QUALITY,
                availableQualities.find { it.value == liveQ }?.label
                    ?: availableQualities.firstOrNull()?.label ?: "Unknown",
                Icons.Default.LiveTv,
                modifier = Modifier
                    .focusRequester(liveR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = videoR
                    },
                onClick = { onClick(liveR); onL() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_QUALITY,
                availableQualities.find { it.value == videoQ }?.label
                    ?: availableQualities.firstOrNull()?.label ?: "Unknown",
                Icons.Default.VideoFile,
                modifier = Modifier
                    .focusRequester(videoR)
                    .focusProperties {
                        left = sidebarR
                        up = liveR
                        down = liveSubR
                    },
                onClick = { onClick(videoR); onV() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_SUBTITLE_AUDIO) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_LIVE_SUBTITLE_DEFAULT,
                liveSub,
                Icons.Default.Subtitles,
                modifier = Modifier
                    .focusRequester(liveSubR)
                    .focusProperties {
                        left = sidebarR
                        up = videoR
                        down = videoSubR
                    },
                onClick = { onClick(liveSubR); onLiveSub() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_SUBTITLE_DEFAULT,
                videoSub,
                Icons.Default.ClosedCaption,
                modifier = Modifier
                    .focusRequester(videoSubR)
                    .focusProperties {
                        left = sidebarR
                        up = liveSubR
                        down = audioR
                    },
                onClick = { onClick(videoSubR); onVideoSub() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_AUDIO_OUTPUT_MODE,
                if (audioMode == "DOWNMIX") AppStrings.SETTINGS_VALUE_AUDIO_DOWNMIX else AppStrings.SETTINGS_VALUE_AUDIO_PASSTHROUGH,
                Icons.Default.AudioFile,
                modifier = Modifier
                    .focusRequester(audioR)
                    .focusProperties {
                        left = sidebarR
                        up = videoSubR
                        down = layerR
                    },
                onClick = { onClick(audioR); onAudioMode() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_COMMENT_LAYER) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_SUBTITLE_COMMENT_LAYER,
                if (layerOrder == "CommentOnTop") AppStrings.DIALOG_LAYER_COMMENT_TOP else AppStrings.DIALOG_LAYER_SUBTITLE_TOP,
                Icons.Default.Layers,
                modifier = Modifier
                    .focusRequester(layerR)
                    .focusProperties {
                        left = sidebarR
                        up = audioR
                        down = uiModeR
                    },
                onClick = { onClick(layerR); onLayer() })
        }

        SettingsSection("プレイヤー操作・UI設定") {
            SettingItem(
                title = "プレイヤーUIモード",
                value = if (uiMode == "CLASSIC") "クラシック (D-Pad完結)" else "モダン (オンスクリーン操作)",
                icon = Icons.Default.SettingsRemote,
                modifier = Modifier
                    .focusRequester(uiModeR)
                    .focusProperties {
                        left = sidebarR
                        up = layerR
                        down = autoCmSkipR
                    },
                onClick = { onClick(uiModeR); onUiMode() }
            )

            SettingItem(
                title = "自動CMスキップ",
                value = if (autoCmSkip == "ON") "有効" else "無効",
                icon = Icons.Default.FastForward,
                modifier = Modifier
                    .focusRequester(autoCmSkipR)
                    .focusProperties {
                        left = sidebarR
                        up = uiModeR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(autoCmSkipR); onAutoCmSkip() }
            )
        }
    }
}

@Composable
fun EpgSettingsContent(
    pref: SettingPreferences,
    onEditColumn: () -> Unit,
    onEditHour: () -> Unit,
    onEditFontSize: () -> Unit,
    colR: FocusRequester,
    hourR: FocusRequester,
    fontR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            "番組表設定",
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection("番組表表示設定") {
            SettingItem(
                "表示チャンネル数 (横軸)",
                "${pref.epgColumnCount} チャンネル",
                Icons.Default.ViewColumn,
                modifier = Modifier
                    .focusRequester(colR)
                    .focusProperties {
                        left = sidebarR; down = hourR; up = FocusRequester.Cancel
                    },
                onClick = { onClick(colR); onEditColumn() }
            )
            SettingItem(
                "表示時間数 (縦幅)",
                "${pref.epgVisibleHours} 時間",
                Icons.Default.Height,
                modifier = Modifier
                    .focusRequester(hourR)
                    .focusProperties {
                        left = sidebarR; up = colR; down = fontR
                    },
                onClick = { onClick(hourR); onEditHour() }
            )
            SettingItem(
                "文字サイズ",
                "${(pref.epgFontSizeScale.toFloat() * 100).toInt()}%",
                Icons.Default.FormatSize,
                modifier = Modifier
                    .focusRequester(fontR)
                    .focusProperties {
                        left = sidebarR; up = hourR; down = FocusRequester.Cancel
                    },
                onClick = { onClick(fontR); onEditFontSize() }
            )
        }
    }
}


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeDisplaySettingsContent(
    isDarkMode: Boolean,
    themeSeason: String,
    genre: String,
    excludePaid: String,
    pickupTime: String,
    startupTab: String,
    modeR: FocusRequester,
    colorR: FocusRequester,
    startR: FocusRequester,
    genreR: FocusRequester,
    timeR: FocusRequester,
    exPaidR: FocusRequester,
    sidebarR: FocusRequester,
    onMode: () -> Unit,
    onColor: () -> Unit,
    onStart: () -> Unit,
    onG: () -> Unit,
    onTime: () -> Unit,
    onExPaid: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    val isTimeLinked = themeSeason == "KOMOREBI" || themeSeason == "KYLE"
    val baseThemeLabel =
        if (isTimeLinked) "時間連動テーマ" else if (!isDarkMode) AppStrings.SETTINGS_VALUE_THEME_DARK else AppStrings.SETTINGS_VALUE_THEME_LIGHT

    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60000)
            currentTime = LocalTime.now()
        }
    }

    val hour = currentTime.hour
    val timeZoneName = when {
        hour in 5..8 -> "MORNING"
        hour in 9..15 -> "DAY"
        hour in 16..18 -> "EVENING"
        else -> "NIGHT"
    }

    val colorSettingTitle =
        if (isTimeLinked) "時間連動セット" else AppStrings.SETTINGS_ITEM_THEME_COLOR
    val detailThemeLabel = if (isTimeLinked) {
        when (themeSeason) {
            "KOMOREBI" -> when (timeZoneName) {
                "MORNING" -> "朝焼け"
                "DAY" -> "木漏れ日"
                "EVENING" -> "夕焼け"
                else -> "月光"
            }

            "KYLE" -> when (timeZoneName) {
                "MORNING" -> "朝凪のカイル"
                "DAY" -> "海辺のカイル"
                "EVENING" -> "夕凪のカイル"
                else -> "深海の夜のカイル"
            }

            else -> themeSeason
        }
    } else {
        when (themeSeason) {
            "SPRING" -> AppStrings.SETTINGS_VALUE_SEASON_SPRING
            "SUMMER" -> AppStrings.SETTINGS_VALUE_SEASON_SUMMER
            "AUTUMN" -> AppStrings.SETTINGS_VALUE_SEASON_AUTUMN
            "WINTER" -> AppStrings.SETTINGS_VALUE_SEASON_WINTER
            "KOMOREBI_DAY" -> "木漏れ日"
            "KOMOREBI_NIGHT" -> "月光"
            "KYLE_DAY" -> "海辺のカイル"
            "KYLE_NIGHT" -> "深海のカイル"
            else -> AppStrings.SETTINGS_VALUE_SEASON_DEFAULT
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_HOME,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_UI_CUSTOM) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_BASE_THEME,
                baseThemeLabel,
                Icons.Default.Brightness4,
                modifier = Modifier
                    .focusRequester(modeR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = colorR
                    },
                onClick = { onClick(modeR); onMode() })

            SettingItem(
                colorSettingTitle,
                detailThemeLabel,
                Icons.Default.ColorLens,
                modifier = Modifier
                    .focusRequester(colorR)
                    .focusProperties {
                        left = sidebarR
                        up = modeR
                        down = genreR
                    },
                onClick = { onClick(colorR); onColor() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_HOME_PICKUP) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_PICKUP_GENRE,
                genre,
                Icons.Default.AutoAwesome,
                modifier = Modifier
                    .focusRequester(genreR)
                    .focusProperties {
                        left = sidebarR
                        up = colorR
                        down = timeR
                    },
                onClick = { onClick(genreR); onG() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_PICKUP_TIME,
                pickupTime,
                Icons.Default.Schedule,
                modifier = Modifier
                    .focusRequester(timeR)
                    .focusProperties {
                        left = sidebarR
                        up = genreR
                        down = exPaidR
                    },
                onClick = { onClick(timeR); onTime() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_EXCLUDE_PAID,
                excludePaid,
                Icons.Default.Lock,
                modifier = Modifier
                    .focusRequester(exPaidR)
                    .focusProperties {
                        left = sidebarR
                        up = timeR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(exPaidR); onExPaid() })
        }
    }
}

@Composable
fun DisplaySettingsContent(
    preferences: SettingPreferences,
    startupChannelName: String,
    sidebarR: FocusRequester,
    onEditTab: () -> Unit,
    onEditStartupChannel: () -> Unit,
    onEditDefaultView: () -> Unit,
    onEditTimeFormat: () -> Unit,
    onToggleHideSubChannels: () -> Unit,
    itemRs: List<FocusRequester>,
    hideSubChannelsR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_DISPLAY,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_UI_CUSTOM) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                preferences.startupTab,
                Icons.Default.Launch,
                modifier = Modifier
                    .focusRequester(itemRs[0])
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = itemRs[1]
                    },
                onClick = { onClick(itemRs[0]); onEditTab() })

            SettingItem(
                AppStrings.SETTINGS_ITEM_STARTUP_CHANNEL,
                startupChannelName,
                Icons.Default.LiveTv,
                modifier = Modifier
                    .focusRequester(itemRs[1])
                    .focusProperties {
                        left = sidebarR
                        up = itemRs[0]
                        down = itemRs[2]
                    },
                onClick = { onClick(itemRs[1]); onEditStartupChannel() })

            SettingItem(
                AppStrings.SETTINGS_ITEM_DEFAULT_RECORD_VIEW,
                if (preferences.defaultRecordListView == "LIST") AppStrings.SETTINGS_VALUE_VIEW_LIST else AppStrings.SETTINGS_VALUE_VIEW_GRID,
                Icons.Default.GridView,
                modifier = Modifier
                    .focusRequester(itemRs[2])
                    .focusProperties {
                        left = sidebarR
                        up = itemRs[1]
                        down = itemRs[3]
                    },
                onClick = { onClick(itemRs[2]); onEditDefaultView() })

            SettingItem(
                "時刻の表示形式",
                if (preferences.timeFormat == "12H") "12時間表記 (AM/PM)" else "24時間表記",
                Icons.Default.Schedule,
                modifier = Modifier
                    .focusRequester(itemRs[3])
                    .focusProperties {
                        left = sidebarR
                        up = itemRs[2]
                        down = hideSubChannelsR
                    },
                onClick = { onClick(itemRs[3]); onEditTimeFormat() })

            SettingItem(
                title = "サブチャンネルを非表示にする",
                value = if (preferences.hideSubChannels) "ON" else "OFF",
                icon = Icons.Default.FilterListOff,
                modifier = Modifier
                    .focusRequester(hideSubChannelsR)
                    .focusProperties {
                        left = sidebarR
                        up = itemRs[3]
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(hideSubChannelsR); onToggleHideSubChannels() }
            )
        }
    }
}

@Composable
fun CommentSettingsContent(
    def: String,
    speed: String,
    size: String,
    opacity: String,
    max: String,
    onEdit: (String, String) -> Unit,
    onT: () -> Unit,
    defR: FocusRequester,
    spR: FocusRequester,
    szR: FocusRequester,
    opR: FocusRequester,
    mxR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_COMMENT,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_COMMENT_DISPLAY) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_DEFAULT_DISPLAY,
                def,
                Icons.Default.Visibility,
                modifier = Modifier
                    .focusRequester(defR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = spR
                    },
                onClick = { onClick(defR); onT() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SPEED,
                "${speed}x",
                Icons.Default.Speed,
                modifier = Modifier
                    .focusRequester(spR)
                    .focusProperties {
                        left = sidebarR
                        up = defR
                        down = szR
                    },
                onClick = {
                    onClick(spR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_SPEED,
                    speed
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SIZE,
                "${size}x",
                Icons.Default.TextFormat,
                modifier = Modifier
                    .focusRequester(szR)
                    .focusProperties {
                        left = sidebarR
                        up = spR
                        down = opR
                    },
                onClick = {
                    onClick(szR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_SIZE,
                    size
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_OPACITY,
                opacity,
                Icons.Default.Opacity,
                modifier = Modifier
                    .focusRequester(opR)
                    .focusProperties {
                        left = sidebarR
                        up = szR
                        down = mxR
                    },
                onClick = {
                    onClick(opR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_OPACITY,
                    opacity
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_MAX_LINES,
                max,
                Icons.Default.VerticalAlignTop,
                modifier = Modifier
                    .focusRequester(mxR)
                    .focusProperties {
                        left = sidebarR
                        up = opR
                        down = FocusRequester.Cancel
                    },
                onClick = {
                    onClick(mxR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_MAX_LINES,
                    max
                )
                })
        }
    }
}

@Composable
fun LabSettingsContent(
    apiKey: String,
    apiKeyStatus: String,
    isValidatingApiKey: Boolean,
    baseball: Set<String>,
    mirakurunDual: String,
    dualR: FocusRequester,
    baseballR: FocusRequester,
    apiKeyR: FocusRequester,
    sidebarR: FocusRequester,
    onEditApiKey: () -> Unit,
    onBaseball: () -> Unit,
    onToggleMirakurunDual: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_LAB,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("プレイヤー (実験的)") {
            SettingItem(
                title = "Mirakurunソースの2画面同時再生・PiPモードを許可",
                value = mirakurunDual,
                icon = Icons.Default.VerticalSplit,
                modifier = Modifier
                    .focusRequester(dualR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = baseballR
                    },
                onClick = { onClick(dualR); onToggleMirakurunDual() }
            )
        }

        SettingsSection("プロ野球モード (アルファ版)") {
            val baseballText =
                if (baseball.isEmpty()) "未設定" else "${baseball.size}球団選択中"
            SettingItem(
                title = "フォロー球団の設定",
                value = baseballText,
                icon = Icons.Default.SportsBaseball,
                modifier = Modifier
                    .focusRequester(baseballR)
                    .focusProperties {
                        left = sidebarR
                        up = dualR
                        down = apiKeyR
                    },
                onClick = { onClick(baseballR); onBaseball() }
            )
        }

        SettingsSection("AIコンシェルジュ (Gemini)") {
            val statusText = when {
                apiKey.isBlank() -> "未設定"
                isValidatingApiKey -> "確認中..."
                apiKeyStatus == "VALID" -> "設定済み"
                apiKeyStatus == "INVALID" -> "キーが無効です"
                apiKeyStatus == "UNVERIFIED" -> "設定済み (未確認)"
                else -> "未確認"
            }
            SettingItem(
                title = "APIキー連携 (スマホで簡単設定)",
                value = statusText,
                icon = Icons.Default.AutoAwesome,
                modifier = Modifier
                    .focusRequester(apiKeyR)
                    .focusProperties {
                        left = sidebarR
                        up = baseballR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(apiKeyR); onEditApiKey() }
            )
        }
    }
}

@Composable
fun AppInfoContent(
    onShow: () -> Unit,
    licR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Komorebi",
            style = MaterialTheme.typography.displayMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Version ${com.beeregg2001.komorebi.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
            color = KomorebiTheme.colors.textSecondary
        )
        Spacer(Modifier.height(48.dp))
        SettingItem(
            AppStrings.SETTINGS_ITEM_OSS_LICENSES,
            "",
            Icons.Default.Info,
            modifier = Modifier
                .width(400.dp)
                .focusRequester(licR)
                .focusProperties {
                    left = sidebarR
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                },
            onClick = { onClick(licR); onShow() })
    }
}