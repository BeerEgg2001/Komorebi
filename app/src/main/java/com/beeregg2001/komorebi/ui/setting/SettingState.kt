package com.beeregg2001.komorebi.ui.setting

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.viewmodel.PostRecordingBatch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    val audioOutputMode: String,
    val labAnnict: String,
    val labShobocal: String,
    val defaultPostCommand: String,
    val postRecordingBatchList: List<PostRecordingBatch>,
    val favoriteBaseballTeams: Set<String>, // ★追加
    val geminiApiKey: String,
    val enableAiNormalization: String,
    val pickupGenre: String,
    val excludePaid: String,
    val pickupTime: String,
    val startupTab: String,
    val currentThemeName: String,
    val defaultRecordListView: String
)

@Composable
fun rememberSettingPreferences(repository: SettingsRepository): SettingPreferences {
    val gson = remember { Gson() }
    val batchListJson = repository.postRecordingBatchList.collectAsState(initial = "[]").value
    val batchList = remember(batchListJson) {
        try {
            val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
            gson.fromJson<List<PostRecordingBatch>>(batchListJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ★追加: 贔屓球団のデコード
    val baseballTeamsJson = repository.favoriteBaseballTeams.collectAsState(initial = "[]").value
    val favoriteTeams = remember(baseballTeamsJson) {
        try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(baseballTeamsJson, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

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
        audioOutputMode = repository.audioOutputMode.collectAsState(initial = "DOWNMIX").value,
        labAnnict = repository.labAnnictIntegration.collectAsState(initial = "OFF").value,
        labShobocal = repository.labShobocalIntegration.collectAsState(initial = "OFF").value,
        defaultPostCommand = repository.defaultPostCommand.collectAsState(initial = "").value,
        postRecordingBatchList = batchList,
        favoriteBaseballTeams = favoriteTeams, // ★追加
        geminiApiKey = repository.geminiApiKey.collectAsState(initial = "").value,
        enableAiNormalization = repository.enableAiNormalization.collectAsState(initial = "OFF").value,
        pickupGenre = repository.homePickupGenre.collectAsState(initial = "アニメ").value,
        excludePaid = repository.excludePaidBroadcasts.collectAsState(initial = "ON").value,
        pickupTime = repository.homePickupTime.collectAsState(initial = "自動").value,
        startupTab = repository.startupTab.collectAsState(initial = "ホーム").value,
        currentThemeName = repository.appTheme.collectAsState(initial = "MONOTONE").value,
        defaultRecordListView = repository.defaultRecordListView.collectAsState(initial = "LIST").value
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