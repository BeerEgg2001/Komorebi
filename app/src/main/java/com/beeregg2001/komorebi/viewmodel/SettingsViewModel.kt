package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val mirakurunIp: StateFlow<String> = settingsRepository.mirakurunIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val konomiIp: StateFlow<String> = settingsRepository.konomiIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "https://192-168-xxx-xxx.local.konomi.tv")
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

    // ★追加: アドオン・ラボ用StateFlow
    val labAnnictIntegration: StateFlow<String> = settingsRepository.labAnnictIntegration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val labShobocalIntegration: StateFlow<String> = settingsRepository.labShobocalIntegration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val defaultPostCommand: StateFlow<String> = settingsRepository.defaultPostCommand
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val startupTab: StateFlow<String> = settingsRepository.startupTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ホーム")
    val appTheme: StateFlow<String> = settingsRepository.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MONOTONE")

    val isSettingsInitialized: StateFlow<Boolean> = settingsRepository.isInitialized
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun updateMirakurunIp(ip: String) {
        viewModelScope.launch { settingsRepository.saveString(SettingsRepository.MIRAKURUN_IP, ip) }
    }

    fun updateAppTheme(themeName: String) {
        viewModelScope.launch { settingsRepository.saveString(SettingsRepository.APP_THEME, themeName) }
    }

    suspend fun getStartupTabOnce(): String {
        return settingsRepository.getStartupTabOnce()
    }
}