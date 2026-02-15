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

    // 接続情報を StateFlow として公開
    val mirakurunIp: StateFlow<String> = settingsRepository.mirakurunIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val konomiIp: StateFlow<String> = settingsRepository.konomiIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "https://192-168-xxx-xxx.local.konomi.tv")

    val konomiPort: StateFlow<String> = settingsRepository.konomiPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "7000")

    // 実況設定の StateFlow
    val commentSpeed: StateFlow<String> = settingsRepository.commentSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")

    val commentFontSize: StateFlow<String> = settingsRepository.commentFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")

    val commentOpacity: StateFlow<String> = settingsRepository.commentOpacity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")

    val commentMaxLines: StateFlow<String> = settingsRepository.commentMaxLines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0")

    // コメント表示のデフォルト設定の StateFlow
    val commentDefaultDisplay: StateFlow<String> = settingsRepository.commentDefaultDisplay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ON")

    // ★追加: 画質設定の StateFlow
    val liveQuality: StateFlow<String> = settingsRepository.liveQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p-60fps")

    val videoQuality: StateFlow<String> = settingsRepository.videoQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p-60fps")

    // 設定が初期化済みかどうか
    val isSettingsInitialized: StateFlow<Boolean> = settingsRepository.isInitialized
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 設定保存用
    fun updateMirakurunIp(ip: String) {
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.MIRAKURUN_IP, ip)
        }
    }
}