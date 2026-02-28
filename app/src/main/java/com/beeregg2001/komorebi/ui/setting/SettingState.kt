package com.beeregg2001.komorebi.ui.setting

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.data.SettingsRepository

@Stable
class SettingPreferences(
    val konomiIp: String,
    val konomiPort: String,
    val mirakurunIp: String,
    val mirakurunPort: String,
    val preferredSource: String,
    val commentSpeed: String,
    val commentFontSize: String,
    val commentOpacity: String,
    val commentMaxLines: String,
    val commentDefaultDisplay: String,
    val liveQuality: String,
    val videoQuality: String,
    val liveSubtitleDefault: String,
    val videoSubtitleDefault: String,
    val subtitleCommentLayer: String,
    val audioOutputMode: String, // ★追加
    val labAnnict: String,
    val labShobocal: String,
    val defaultPostCommand: String,
    val pickupGenre: String,
    val excludePaid: String,
    val pickupTime: String,
    val startupTab: String,
    val currentThemeName: String,
    val defaultRecordListView: String // ★追加
)

@Composable
fun rememberSettingPreferences(repository: SettingsRepository): SettingPreferences {
    return SettingPreferences(
        konomiIp = repository.konomiIp.collectAsState(initial = "").value,
        konomiPort = repository.konomiPort.collectAsState(initial = "").value,
        mirakurunIp = repository.mirakurunIp.collectAsState(initial = "").value,
        mirakurunPort = repository.mirakurunPort.collectAsState(initial = "").value,
        preferredSource = repository.preferredStreamSource.collectAsState(initial = "KONOMITV").value,
        commentSpeed = repository.commentSpeed.collectAsState(initial = "1.0").value,
        commentFontSize = repository.commentFontSize.collectAsState(initial = "1.0").value,
        commentOpacity = repository.commentOpacity.collectAsState(initial = "1.0").value,
        commentMaxLines = repository.commentMaxLines.collectAsState(initial = "0").value,
        commentDefaultDisplay = repository.commentDefaultDisplay.collectAsState(initial = "ON").value,
        liveQuality = repository.liveQuality.collectAsState(initial = "1080p-60fps").value,
        videoQuality = repository.videoQuality.collectAsState(initial = "1080p-60fps").value,
        liveSubtitleDefault = repository.liveSubtitleDefault.collectAsState(initial = "OFF").value,
        videoSubtitleDefault = repository.videoSubtitleDefault.collectAsState(initial = "OFF").value,
        subtitleCommentLayer = repository.subtitleCommentLayer.collectAsState(initial = "CommentOnTop").value,
        audioOutputMode = repository.audioOutputMode.collectAsState(initial = "DOWNMIX").value, // ★追加
        labAnnict = repository.labAnnictIntegration.collectAsState(initial = "OFF").value,
        labShobocal = repository.labShobocalIntegration.collectAsState(initial = "OFF").value,
        defaultPostCommand = repository.defaultPostCommand.collectAsState(initial = "").value,
        pickupGenre = repository.homePickupGenre.collectAsState(initial = "アニメ").value,
        excludePaid = repository.excludePaidBroadcasts.collectAsState(initial = "ON").value,
        pickupTime = repository.homePickupTime.collectAsState(initial = "自動").value,
        startupTab = repository.startupTab.collectAsState(initial = "ホーム").value,
        currentThemeName = repository.appTheme.collectAsState(initial = "MONOTONE").value,
        defaultRecordListView = repository.defaultRecordListView.collectAsState(initial = "LIST").value // ★追加
    )
}

@Stable
class SettingUiState {
    var activeDialog by mutableStateOf<SettingDialogState>(SettingDialogState.None)
    var selectedCategoryIndex by mutableIntStateOf(0)
    var restoreFocusRequester by mutableStateOf<FocusRequester?>(null)
    var restoreCategoryIndex by mutableIntStateOf(-1)
    var isSidebarFocused by mutableStateOf(true)
    var isRestoringFocus by mutableStateOf(false)
}

@Composable
fun rememberSettingUiState(): SettingUiState {
    return remember { SettingUiState() }
}