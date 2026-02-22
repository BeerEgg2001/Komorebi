package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
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

@Composable
fun GeneralSettingsContent(
    onClearChannel: () -> Unit, onClearHistory: () -> Unit,
    clearChannelR: FocusRequester, clearHistoryR: FocusRequester,
    sidebarR: FocusRequester, // ★追加
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_GENERAL,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_DATA_MANAGEMENT) {
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_CLEAR_CHANNEL_HISTORY,
                value = AppStrings.SETTINGS_VALUE_DELETE,
                icon = Icons.Default.Delete,
                modifier = Modifier
                    .focusRequester(clearChannelR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(clearChannelR); onClearChannel() })
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_CLEAR_WATCH_HISTORY,
                value = AppStrings.SETTINGS_VALUE_DELETE,
                icon = Icons.Default.Delete,
                modifier = Modifier
                    .focusRequester(clearHistoryR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(clearHistoryR); onClearHistory() })
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
    sidebarR: FocusRequester, // ★追加
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
                kIp,
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
                mIp,
                Icons.Default.Dns,
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
        SettingsSection(AppStrings.SETTINGS_SECTION_STREAM_SOURCE) {
            val isMirAvailable = mIp.isNotBlank() && mPort.isNotBlank()
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                value = if (!isMirAvailable) AppStrings.SETTINGS_VALUE_SOURCE_KONOMITV_FIXED else if (prefSrc == "MIRAKURUN") AppStrings.SETTINGS_VALUE_SOURCE_MIRAKURUN_PREFERRED else AppStrings.SETTINGS_VALUE_SOURCE_KONOMITV_PREFERRED,
                icon = Icons.Default.PriorityHigh,
                enabled = isMirAvailable,
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
    liveR: FocusRequester,
    videoR: FocusRequester,
    liveSubR: FocusRequester,
    videoSubR: FocusRequester,
    layerR: FocusRequester,
    sidebarR: FocusRequester, // ★追加
    onL: () -> Unit,
    onV: () -> Unit,
    onLiveSub: () -> Unit,
    onVideoSub: () -> Unit,
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
                Icons.Default.HighQuality,
                modifier = Modifier
                    .focusRequester(liveR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(liveR); onL() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_QUALITY,
                StreamQuality.fromValue(videoQ).label,
                modifier = Modifier
                    .focusRequester(videoR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(videoR); onV() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_SUBTITLE) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_LIVE_SUBTITLE,
                if (liveSub == "ON") AppStrings.SETTINGS_VALUE_SHOW else AppStrings.SETTINGS_VALUE_HIDE,
                Icons.Default.ClosedCaption,
                modifier = Modifier
                    .focusRequester(liveSubR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(liveSubR); onLiveSub() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_SUBTITLE,
                if (videoSub == "ON") AppStrings.SETTINGS_VALUE_SHOW else AppStrings.SETTINGS_VALUE_HIDE,
                modifier = Modifier
                    .focusRequester(videoSubR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(videoSubR); onVideoSub() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_LAYER) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_LAYER_ORDER,
                if (layerOrder == "CommentOnTop") AppStrings.SETTINGS_VALUE_LAYER_COMMENT_TOP else AppStrings.SETTINGS_VALUE_LAYER_SUBTITLE_TOP,
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
    sidebarR: FocusRequester, // ★追加
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
            AppStrings.SETTINGS_CATEGORY_DISPLAY,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_GENERAL) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_BASE_THEME,
                if (isDarkMode) AppStrings.SETTINGS_VALUE_THEME_DARK else AppStrings.SETTINGS_VALUE_THEME_LIGHT,
                Icons.Default.Brightness4,
                modifier = Modifier
                    .focusRequester(modeR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(modeR); onMode() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_THEME_COLOR,
                when (themeSeason) {
                    "SPRING" -> AppStrings.SETTINGS_VALUE_SEASON_SPRING; "SUMMER" -> AppStrings.SETTINGS_VALUE_SEASON_SUMMER; "AUTUMN" -> AppStrings.SETTINGS_VALUE_SEASON_AUTUMN; "WINTER" -> AppStrings.SETTINGS_VALUE_SEASON_WINTER; else -> AppStrings.SETTINGS_VALUE_SEASON_DEFAULT
                },
                Icons.Default.Palette,
                modifier = Modifier
                    .focusRequester(colorR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(colorR); onColor() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                startupTab,
                Icons.Default.Home,
                modifier = Modifier
                    .focusRequester(startR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(startR); onStart() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_HOME_PICKUP) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_PICKUP_GENRE,
                genre,
                Icons.Default.Category,
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
                if (excludePaid == "ON") AppStrings.SETTINGS_VALUE_EXCLUDE_ON else AppStrings.SETTINGS_VALUE_EXCLUDE_OFF,
                Icons.Default.Block,
                modifier = Modifier
                    .focusRequester(exPaidR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(exPaidR); onExPaid() })
        }
    }
}

@Composable
fun DisplaySettingsContent(
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
    sidebarR: FocusRequester, // ★追加
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
                if (def == "ON") AppStrings.SETTINGS_VALUE_SHOW else AppStrings.SETTINGS_VALUE_HIDE,
                Icons.Default.Chat,
                modifier = Modifier
                    .focusRequester(defR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(defR); onT() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SPEED,
                speed,
                modifier = Modifier
                    .focusRequester(spR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(spR); onEdit(AppStrings.SETTINGS_INPUT_COMMENT_SPEED, speed) })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SIZE,
                size,
                modifier = Modifier
                    .focusRequester(szR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(szR); onEdit(AppStrings.SETTINGS_INPUT_COMMENT_SIZE, size) })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_OPACITY,
                opacity,
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
    annictR: FocusRequester, shobocalR: FocusRequester, cmdR: FocusRequester,
    sidebarR: FocusRequester, // ★追加
    onAnnict: () -> Unit, onShobocal: () -> Unit, onEditCmd: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_LAB,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_EXTERNAL_INTEGRATION) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_ANNICT,
                if (annict == "ON") AppStrings.SETTINGS_VALUE_ENABLE else AppStrings.SETTINGS_VALUE_DISABLE,
                Icons.Default.Sync,
                modifier = Modifier
                    .focusRequester(annictR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(annictR); onAnnict() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_SHOBOCAL,
                if (shobocal == "ON") AppStrings.SETTINGS_VALUE_ENABLE else AppStrings.SETTINGS_VALUE_DISABLE,
                Icons.Default.CalendarMonth,
                modifier = Modifier
                    .focusRequester(shobocalR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(shobocalR); onShobocal() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_RECORD_DETAIL) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_POST_COMMAND,
                postCmd.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                Icons.Default.Terminal,
                modifier = Modifier
                    .focusRequester(cmdR)
                    .focusProperties { left = sidebarR },
                onClick = { onClick(cmdR); onEditCmd() })
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
            "Version 0.4.0 beta",
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