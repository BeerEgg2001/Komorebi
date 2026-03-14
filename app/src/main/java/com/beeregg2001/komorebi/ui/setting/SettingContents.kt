package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.PostRecordingBatch

@Composable
fun GeneralSettingsContent(
    totalRecordCount: Int, // ★追加
    lastSyncedAt: Long, // ★追加
    onForceSync: () -> Unit, // ★追加
    onClearChannel: () -> Unit,
    onClearHistory: () -> Unit,
    dbInfoR: FocusRequester, // ★追加
    forceSyncR: FocusRequester, // ★追加
    clearChannelR: FocusRequester,
    clearHistoryR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    // ★追加: 日時のフォーマット
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

        // ★追加: データベース情報セクション
        SettingsSection("データベース情報") {
            SettingItem(
                title = "ローカル保存件数",
                value = "$totalRecordCount 件",
                icon = Icons.Default.Storage,
                modifier = Modifier
                    .focusRequester(dbInfoR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(dbInfoR) }
            )
            SettingItem(
                title = "手動でフル同期を実行",
                value = "最終同期: $lastSyncStr",
                icon = Icons.Default.CloudSync,
                modifier = Modifier
                    .focusRequester(forceSyncR)
                    .focusProperties { left = sidebarR },
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
                    .focusProperties { left = sidebarR },
                onClick = { onClick(clearChannelR); onClearChannel() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_CLEAR_WATCH_HISTORY,
                "",
                Icons.Default.DeleteSweep,
                modifier = Modifier
                    .focusRequester(clearHistoryR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(clearHistoryR); onClearHistory() })
        }
    }
}

// 録画設定コンテンツ
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
                    .focusProperties { left = sidebarR },
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
                    SettingItem(
                        title = batch.name,
                        value = "削除",
                        icon = Icons.Default.Terminal,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties { left = sidebarR },
                        onClick = { onClick(requester); onDelete(batch) }
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionSettingsContent(
    kIp: String,
    kPort: String,
    mIp: String,
    mPort: String,
    prefSrc: String,
    onEdit: (String, String) -> Unit,
    onSelectSrc: () -> Unit,
    kIpR: FocusRequester,
    kPortR: FocusRequester,
    mIpR: FocusRequester,
    mPortR: FocusRequester,
    prefSrcR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_CONNECTION,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection(AppStrings.SETTINGS_SECTION_KONOMITV) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_ADDRESS,
                kIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                Icons.Default.Dns,
                modifier = Modifier
                    .focusRequester(kIpR)
                    .focusProperties { left = sidebarR },
                onClick = {
                    onClick(kIpR); onEdit(
                    AppStrings.SETTINGS_INPUT_KONOMITV_ADDRESS,
                    kIp
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_PORT,
                kPort,
                Icons.Default.Numbers,
                modifier = Modifier
                    .focusRequester(kPortR)
                    .focusProperties { left = sidebarR },
                onClick = {
                    onClick(kPortR); onEdit(
                    AppStrings.SETTINGS_INPUT_KONOMITV_PORT,
                    kPort
                )
                })
        }

        SettingsSection(AppStrings.SETTINGS_SECTION_MIRAKURUN) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_ADDRESS,
                mIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                Icons.Default.Router,
                modifier = Modifier
                    .focusRequester(mIpR)
                    .focusProperties { left = sidebarR },
                onClick = {
                    onClick(mIpR); onEdit(
                    AppStrings.SETTINGS_INPUT_MIRAKURUN_ADDRESS,
                    mIp
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_PORT,
                mPort,
                Icons.Default.Numbers,
                modifier = Modifier
                    .focusRequester(mPortR)
                    .focusProperties { left = sidebarR },
                onClick = {
                    onClick(mPortR); onEdit(
                    AppStrings.SETTINGS_INPUT_MIRAKURUN_PORT,
                    mPort
                )
                })
        }

        SettingsSection(AppStrings.SETTINGS_SECTION_STREAM_PRIORITY) {
            val label = if (mIp.isBlank()) {
                AppStrings.SETTINGS_VALUE_SOURCE_KONOMITV_FIXED
            } else {
                if (prefSrc == "KONOMITV") AppStrings.SETTINGS_VALUE_SOURCE_KONOMITV_PREFERRED
                else AppStrings.SETTINGS_VALUE_SOURCE_MIRAKURUN_PREFERRED
            }
            SettingItem(
                AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                label,
                Icons.Default.PriorityHigh,
                modifier = Modifier
                    .focusRequester(prefSrcR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(prefSrcR); onSelectSrc() })
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
    liveR: FocusRequester,
    videoR: FocusRequester,
    liveSubR: FocusRequester,
    videoSubR: FocusRequester,
    audioR: FocusRequester,
    layerR: FocusRequester,
    sidebarR: FocusRequester,
    onL: () -> Unit,
    onV: () -> Unit,
    onLiveSub: () -> Unit,
    onVideoSub: () -> Unit,
    onAudioMode: () -> Unit,
    onLayer: () -> Unit,
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
                StreamQuality.fromValue(liveQ).label,
                Icons.Default.LiveTv,
                modifier = Modifier
                    .focusRequester(liveR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(liveR); onL() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_QUALITY,
                StreamQuality.fromValue(videoQ).label,
                Icons.Default.VideoFile,
                modifier = Modifier
                    .focusRequester(videoR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(videoR); onV() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_SUBTITLE_AUDIO) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_LIVE_SUBTITLE_DEFAULT,
                liveSub,
                Icons.Default.Subtitles,
                modifier = Modifier
                    .focusRequester(liveSubR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(liveSubR); onLiveSub() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_SUBTITLE_DEFAULT,
                videoSub,
                Icons.Default.ClosedCaption,
                modifier = Modifier
                    .focusRequester(videoSubR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(videoSubR); onVideoSub() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_AUDIO_OUTPUT_MODE,
                if (audioMode == "DOWNMIX") AppStrings.SETTINGS_VALUE_AUDIO_DOWNMIX else AppStrings.SETTINGS_VALUE_AUDIO_PASSTHROUGH,
                Icons.Default.AudioFile,
                modifier = Modifier
                    .focusRequester(audioR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(audioR); onAudioMode() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_COMMENT_LAYER) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_SUBTITLE_COMMENT_LAYER,
                if (layerOrder == "CommentOnTop") AppStrings.DIALOG_LAYER_COMMENT_TOP else AppStrings.DIALOG_LAYER_SUBTITLE_TOP,
                Icons.Default.Layers,
                modifier = Modifier
                    .focusRequester(layerR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(layerR); onLayer() })
        }
    }
}

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
                if (isDarkMode) AppStrings.SETTINGS_VALUE_THEME_DARK else AppStrings.SETTINGS_VALUE_THEME_LIGHT,
                Icons.Default.Brightness4,
                modifier = Modifier
                    .focusRequester(modeR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(modeR); onMode() })
            val seasonLabel = when (themeSeason) {
                "SPRING" -> AppStrings.SETTINGS_VALUE_SEASON_SPRING; "SUMMER" -> AppStrings.SETTINGS_VALUE_SEASON_SUMMER; "AUTUMN" -> AppStrings.SETTINGS_VALUE_SEASON_AUTUMN; "WINTER" -> AppStrings.SETTINGS_VALUE_SEASON_WINTER; else -> AppStrings.SETTINGS_VALUE_SEASON_DEFAULT
            }
            SettingItem(
                AppStrings.SETTINGS_ITEM_THEME_COLOR,
                seasonLabel,
                Icons.Default.ColorLens,
                modifier = Modifier
                    .focusRequester(colorR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(colorR); onColor() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_HOME_PICKUP) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_PICKUP_GENRE,
                genre,
                Icons.Default.AutoAwesome,
                modifier = Modifier
                    .focusRequester(genreR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(genreR); onG() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_PICKUP_TIME,
                pickupTime,
                Icons.Default.Schedule,
                modifier = Modifier
                    .focusRequester(timeR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(timeR); onTime() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_EXCLUDE_PAID,
                excludePaid,
                Icons.Default.Lock,
                modifier = Modifier
                    .focusRequester(exPaidR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(exPaidR); onExPaid() })
        }
    }
}

@Composable
fun DisplaySettingsContent(
    preferences: SettingPreferences,
    sidebarR: FocusRequester,
    onEditTab: () -> Unit,
    onEditDefaultView: () -> Unit,
    itemRs: List<FocusRequester>,
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
                    .focusProperties { left = sidebarR },
                onClick = { onClick(itemRs[0]); onEditTab() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_DEFAULT_RECORD_VIEW,
                if (preferences.defaultRecordListView == "LIST") AppStrings.SETTINGS_VALUE_VIEW_LIST else AppStrings.SETTINGS_VALUE_VIEW_GRID,
                Icons.Default.GridView,
                modifier = Modifier
                    .focusRequester(itemRs[1])
                    .focusProperties { left = sidebarR },
                onClick = { onClick(itemRs[1]); onEditDefaultView() })
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
                    .focusProperties { left = sidebarR },
                onClick = { onClick(defR); onT() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SPEED,
                "${speed}x",
                Icons.Default.Speed,
                modifier = Modifier
                    .focusRequester(spR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(spR); onEdit(AppStrings.SETTINGS_INPUT_COMMENT_SPEED, speed) })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SIZE,
                "${size}x",
                Icons.Default.TextFormat,
                modifier = Modifier
                    .focusRequester(szR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(szR); onEdit(AppStrings.SETTINGS_INPUT_COMMENT_SIZE, size) })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_OPACITY,
                opacity,
                Icons.Default.Opacity,
                modifier = Modifier
                    .focusRequester(opR)
                    .focusProperties { left = sidebarR },
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
                    .focusProperties { left = sidebarR },
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
    annict: String, shobocal: String, postCmd: String,
    enableAi: String, apiKey: String,
    annictR: FocusRequester, shobocalR: FocusRequester, cmdR: FocusRequester,
    enableAiR: FocusRequester, apiKeyR: FocusRequester,
    sidebarR: FocusRequester,
    onAnnict: () -> Unit, onShobocal: () -> Unit, onEditCmd: () -> Unit,
    onToggleAi: () -> Unit, onEditApiKey: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_LAB,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

//        SettingsSection("AI強化正規化 (アルファ版)") {
//            SettingItem(
//                "AIによるシリーズ名正規化",
//                enableAi,
//                Icons.Default.AutoAwesome,
//                modifier = Modifier
//                    .focusRequester(enableAiR)
//                    .focusProperties { left = sidebarR },
//                onClick = { onClick(enableAiR); onToggleAi() }
//            )
//            SettingItem(
//                "Gemini API Key",
//                if (apiKey.isEmpty()) "未設定" else "設定済み",
//                Icons.Default.VpnKey,
//                enabled = enableAi == "ON",
//                modifier = Modifier
//                    .focusRequester(apiKeyR)
//                    .focusProperties { left = sidebarR },
//                onClick = { onClick(apiKeyR); onEditApiKey() }
//            )
//        }

        SettingsSection(AppStrings.SETTINGS_SECTION_EXTERNAL_INTEGRATION) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_ANNICT,
                annict,
                Icons.Default.Link,
                modifier = Modifier
                    .focusRequester(annictR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(annictR); onAnnict() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_SHOBOCAL,
                shobocal,
                Icons.Default.EventNote,
                modifier = Modifier
                    .focusRequester(shobocalR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(shobocalR); onShobocal() })
        }
//        SettingsSection(AppStrings.SETTINGS_SECTION_RECORD_DETAIL) {
//            SettingItem(
//                AppStrings.SETTINGS_ITEM_POST_COMMAND,
//                postCmd.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
//                Icons.Default.Terminal,
//                modifier = Modifier
//                    .focusRequester(cmdR)
//                    .focusProperties { left = sidebarR },
//                onClick = { onClick(cmdR); onEditCmd() })
//        }
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
            "Version 0.8.0 beta",
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
                .focusProperties { left = sidebarR },
            onClick = { onClick(licR); onShow() })
    }
}