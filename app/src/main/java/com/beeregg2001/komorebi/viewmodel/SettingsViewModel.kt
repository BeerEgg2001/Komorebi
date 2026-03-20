package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// バッチ設定用のデータモデル
data class PostRecordingBatch(
    val name: String,
    val path: String
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncEngine: RecordSyncEngine,
    private val db: AppDatabase
) : ViewModel() {

    private val gson = Gson()

    val totalRecordCount: StateFlow<Int> = db.recordedProgramDao().getTotalCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncedAt: StateFlow<Long> = db.syncMetaDao().getSyncMetaFlow()
        .map { it?.lastSyncedAt ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val mirakurunIp: StateFlow<String> = settingsRepository.mirakurunIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val konomiIp: StateFlow<String> = settingsRepository.konomiIp
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            "https://192-168-xxx-xxx.local.konomi.tv"
        )
    val konomiPort: StateFlow<String> = settingsRepository.konomiPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "7000")

    val commentSpeed: StateFlow<String> = settingsRepository.commentSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")
    val commentFontSize: StateFlow<String> = settingsRepository.commentFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")
    val commentOpacity: StateFlow<String> = settingsRepository.commentOpacity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")
    val commentMaxLines: StateFlow<String> = settingsRepository.commentMaxLines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0")
    val commentDefaultDisplay: StateFlow<String> = settingsRepository.commentDefaultDisplay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ON")

    val liveQuality: StateFlow<String> = settingsRepository.liveQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p-60fps")
    val videoQuality: StateFlow<String> = settingsRepository.videoQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p-60fps")

    val liveSubtitleDefault: StateFlow<String> = settingsRepository.liveSubtitleDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val videoSubtitleDefault: StateFlow<String> = settingsRepository.videoSubtitleDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val subtitleCommentLayer: StateFlow<String> = settingsRepository.subtitleCommentLayer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "CommentOnTop")

    val audioOutputMode: StateFlow<String> = settingsRepository.audioOutputMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DOWNMIX")

    val labAnnictIntegration: StateFlow<String> = settingsRepository.labAnnictIntegration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val labShobocalIntegration: StateFlow<String> = settingsRepository.labShobocalIntegration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    // ★追加: 隠しスイッチの公開
    val labAllowMirakurunDual: StateFlow<String> = settingsRepository.labAllowMirakurunDual
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val defaultPostCommand: StateFlow<String> = settingsRepository.defaultPostCommand
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ★追加: 起動時チャンネルのStateFlow
    val startupChannel: StateFlow<String> = settingsRepository.startupChannel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")

    // [解説: 設定の保存関数]
    // UI(SettingScreen)から選択されたチャンネル情報(IDやOFFなど)をデータストアに書き込みます。
    fun updateStartupChannel(value: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.STARTUP_CHANNEL, value)
    }
    val postRecordingBatchList: StateFlow<List<PostRecordingBatch>> =
        settingsRepository.postRecordingBatchList
            .map { json ->
                try {
                    val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
                    gson.fromJson<List<PostRecordingBatch>>(json, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ★追加: 贔屓球団のリストを Set<String> として Flow で提供
    val favoriteBaseballTeams: StateFlow<Set<String>> = settingsRepository.favoriteBaseballTeams
        .map { json ->
            try {
                val type = object : TypeToken<Set<String>>() {}.type
                gson.fromJson<Set<String>>(json, type) ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val startupTab: StateFlow<String> = settingsRepository.startupTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ホーム")
    val appTheme: StateFlow<String> = settingsRepository.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MONOTONE")

    val isSettingsInitialized: StateFlow<Boolean> = settingsRepository.isInitialized
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun updateMirakurunIp(ip: String) {
        viewModelScope.launch { settingsRepository.saveString(SettingsRepository.MIRAKURUN_IP, ip) }
    }

    fun updateKonomiIp(ip: String) {
        viewModelScope.launch {
            val oldIp = konomiIp.value
            settingsRepository.saveString(SettingsRepository.KONOMI_IP, ip)
            val isDefault = oldIp == "" || oldIp == "https://192-168-xxx-xxx.local.konomi.tv"
            if (oldIp != ip && !isDefault) {
                syncEngine.launchSyncAllRecords(forceFullSync = true)
            }
        }
    }

    fun updateKonomiPort(port: String) {
        viewModelScope.launch {
            val oldPort = konomiPort.value
            settingsRepository.saveString(SettingsRepository.KONOMI_PORT, port)
            val isDefault = oldPort == "" || oldPort == "7000"
            if (oldPort != port && !isDefault) {
                syncEngine.launchSyncAllRecords(forceFullSync = true)
            }
        }
    }

    fun triggerFullSync() {
        viewModelScope.launch {
            syncEngine.launchSyncAllRecords(forceFullSync = true)
        }
    }

    fun addPostRecordingBatch(name: String, path: String) {
        viewModelScope.launch {
            val newList = postRecordingBatchList.value.toMutableList().apply {
                add(PostRecordingBatch(name, path))
            }
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(newList)
            )
        }
    }

    fun deletePostRecordingBatch(batch: PostRecordingBatch) {
        viewModelScope.launch {
            val newList = postRecordingBatchList.value.toMutableList().apply {
                remove(batch)
            }
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(newList)
            )
        }
    }

    // ★追加: 贔屓球団の更新と保存
    fun updateFavoriteBaseballTeams(teams: Set<String>) {
        viewModelScope.launch {
            settingsRepository.saveString(
                SettingsRepository.FAVORITE_BASEBALL_TEAMS,
                gson.toJson(teams)
            )
        }
    }

    fun updateAppTheme(themeName: String) {
        viewModelScope.launch {
            settingsRepository.saveString(
                SettingsRepository.APP_THEME,
                themeName
            )
        }
    }

    fun updateDefaultRecordListView(viewType: String) {
        viewModelScope.launch {
            settingsRepository.saveString(
                SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                viewType
            )
        }
    }

    suspend fun getStartupTabOnce(): String {
        return settingsRepository.getStartupTabOnce()
    }

    // ★追加: 隠しスイッチの更新
    fun updateLabAllowMirakurunDual(value: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.LAB_ALLOW_MIRAKURUN_DUAL, value)
    }
}