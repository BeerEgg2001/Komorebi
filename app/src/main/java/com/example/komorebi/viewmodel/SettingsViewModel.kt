package com.example.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.komorebi.data.SettingsRepository
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

    // 接続情報を StateFlow として公開（初期値はリポジトリのデフォルトに合わせる）
    val mirakurunIp: StateFlow<String> = settingsRepository.mirakurunIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "192.168.100.60")

    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "40772")

    val konomiIp: StateFlow<String> = settingsRepository.konomiIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "https://192-168-100-60.local.konomi.tv")

    val konomiPort: StateFlow<String> = settingsRepository.konomiPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "7000")

    // 設定保存用（将来設定画面を作るときに重宝します）
    fun updateMirakurunIp(ip: String) {
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.MIRAKURUN_IP, ip)
        }
    }
}