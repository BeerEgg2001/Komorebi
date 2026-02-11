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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "192.168.11.100")

    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "40772")

    val konomiIp: StateFlow<String> = settingsRepository.konomiIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "https://192-168-11-100.local.konomi.tv")

    val konomiPort: StateFlow<String> = settingsRepository.konomiPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "7000")

    // 設定が初期化済みかどうか
    // 初期値を true にすることで、起動直後の未読み込み状態でダイアログが一瞬表示されるのを防ぐ
    val isSettingsInitialized: StateFlow<Boolean> = settingsRepository.isInitialized
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 設定保存用
    fun updateMirakurunIp(ip: String) {
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.MIRAKURUN_IP, ip)
        }
    }
}