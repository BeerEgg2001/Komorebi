package com.beeregg2001.komorebi.ui.setting

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.viewmodel.PostRecordingBatch
import com.beeregg2001.komorebi.viewmodel.SmbServer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Stable
class SettingPreferences(
    val backendType: String,
    val edcbIp: String,
    val edcbPort: String,
    val edcbHttpPort: String,
    val epgStationIp: String,
    val epgStationPort: String,

    val konomiIp: String,
    val konomiPort: String,
    val mirakurunIp: String,
    val mirakurunPort: String,
    val preferredSource: String,
    val cfAccessClientId: String,
    val cfAccessClientSecret: String,
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
    val labAllowMirakurunDual: String,
    val defaultPostCommand: String,
    val postRecordingBatchList: List<PostRecordingBatch>,
    val favoriteBaseballTeams: Set<String>,
    val geminiApiKey: String,
    val geminiApiKeyStatus: String,
    val enableAiNormalization: String,
    val pickupGenre: String,
    val excludePaid: String,
    val pickupTime: String,
    val startupTab: String,
    val startupChannel: String,
    val timeFormat: String,
    val currentThemeName: String,
    val defaultRecordListView: String,
    val hideSubChannels: Boolean,
    val edcbRecordPlayMethod: String,
    val smbServerList: List<SmbServer>,
// ★ 追加: 番組表設定
    val epgColumnCount: String,
    val epgFontSizeScale: String,
    val epgVisibleHours: String
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

    val favoriteTeamsJson = repository.favoriteBaseballTeams.collectAsState(initial = "[]").value
    val favoriteTeams = remember(favoriteTeamsJson) {
        try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(favoriteTeamsJson, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    val smbListJson = repository.smbServerList.collectAsState(initial = "[]").value
    val smbList = remember(smbListJson) {
        try {
            val type = object : TypeToken<List<SmbServer>>() {}.type
            gson.fromJson<List<SmbServer>>(smbListJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    return SettingPreferences(
        backendType = repository.backendType.collectAsState(initial = "KONOMITV").value,
        edcbIp = repository.edcbIp.collectAsState(initial = "").value,
        edcbPort = repository.edcbPort.collectAsState(initial = "5510").value,
        edcbHttpPort = repository.edcbHttpPort.collectAsState(initial = "5510").value,
        epgStationIp = repository.epgStationIp.collectAsState(initial = "").value,
        epgStationPort = repository.epgStationPort.collectAsState(initial = "8888").value,
        konomiIp = repository.konomiIp.collectAsState(initial = "").value,
        konomiPort = repository.konomiPort.collectAsState(initial = "7000").value,
        mirakurunIp = repository.mirakurunIp.collectAsState(initial = "").value,
        mirakurunPort = repository.mirakurunPort.collectAsState(initial = "40772").value,
        preferredSource = repository.preferredStreamSource.collectAsState(initial = "KONOMITV").value,
        cfAccessClientId = repository.cfAccessClientId.collectAsState(initial = "").value,
        cfAccessClientSecret = repository.cfAccessClientSecret.collectAsState(initial = "").value,
        commentSpeed = repository.commentSpeed.collectAsState(initial = "1.0").value,
        commentFontSize = repository.commentFontSize.collectAsState(initial = "1.0").value,
        commentOpacity = repository.commentOpacity.collectAsState(initial = "1.0").value,
        commentMaxLines = repository.commentMaxLines.collectAsState(initial = "0").value,
        commentDefaultDisplay = repository.commentDefaultDisplay.collectAsState(initial = "ON").value,
        liveQuality = repository.liveQuality.collectAsState(initial = "1080p-60fps").value,
        videoQuality = repository.videoQuality.collectAsState(initial = "1080p-60fps").value,
        liveSubtitleDefault = repository.liveSubtitleDefault.collectAsState(initial = "ON").value,
        videoSubtitleDefault = repository.videoSubtitleDefault.collectAsState(initial = "ON").value,
        subtitleCommentLayer = repository.subtitleCommentLayer.collectAsState(initial = "COMMENT_TOP").value,
        audioOutputMode = repository.audioOutputMode.collectAsState(initial = "DOWNMIX").value,
        labAnnict = repository.labAnnictIntegration.collectAsState(initial = "OFF").value,
        labShobocal = repository.labShobocalIntegration.collectAsState(initial = "OFF").value,
        labAllowMirakurunDual = repository.labAllowMirakurunDual.collectAsState(initial = "OFF").value,
        defaultPostCommand = repository.defaultPostCommand.collectAsState(initial = "").value,
        postRecordingBatchList = batchList,
        favoriteBaseballTeams = favoriteTeams,
        geminiApiKey = repository.geminiApiKey.collectAsState(initial = "").value,
        geminiApiKeyStatus = repository.geminiApiKeyStatus.collectAsState(initial = "").value,
        enableAiNormalization = repository.enableAiNormalization.collectAsState(initial = "OFF").value,
        pickupGenre = repository.homePickupGenre.collectAsState(initial = "アニメ").value,
        excludePaid = repository.excludePaidBroadcasts.collectAsState(initial = "ON").value,
        pickupTime = repository.homePickupTime.collectAsState(initial = "自動").value,
        startupTab = repository.startupTab.collectAsState(initial = "ホーム").value,
        startupChannel = repository.startupChannel.collectAsState(initial = "OFF").value,
        timeFormat = repository.timeFormat.collectAsState(initial = "24H").value,
        currentThemeName = repository.appTheme.collectAsState(initial = "MONOTONE").value,
        defaultRecordListView = repository.defaultRecordListView.collectAsState(initial = "LIST").value,
        hideSubChannels = repository.hideSubChannels.collectAsState(initial = false).value,
        edcbRecordPlayMethod = repository.edcbRecordPlayMethod.collectAsState(initial = "API").value,
        smbServerList = smbList,
        epgColumnCount = repository.epgColumnCount.collectAsState(initial = "7").value,
        epgFontSizeScale = repository.epgFontSizeScale.collectAsState(initial = "1.0").value,
        epgVisibleHours = repository.epgVisibleHours.collectAsState(initial = "6").value
    )
}

// ★ 修正: initialCategoryIndex を受け取り、初期カテゴリを指定できるようにする
@Stable
class SettingUiState(initialCategoryIndex: Int = 0) {
    var activeDialog by mutableStateOf<SettingDialogState>(SettingDialogState.None)
    var selectedCategoryIndex by mutableIntStateOf(initialCategoryIndex)
    var restoreFocusRequester by mutableStateOf<FocusRequester?>(null)
    var restoreCategoryIndex by mutableIntStateOf(-1)
    var isSidebarFocused by mutableStateOf(true)
    var isRestoringFocus by mutableStateOf(false)
}

@Composable
fun rememberSettingUiState(initialCategoryIndex: Int = 0): SettingUiState {
    return remember(initialCategoryIndex) { SettingUiState(initialCategoryIndex) }
}