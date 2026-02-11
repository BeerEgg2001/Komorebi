package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.toEntity
import com.beeregg2001.komorebi.data.local.entity.toKonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: KonomiRepository
) : ViewModel() {

    // 読み込み状態の管理
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    /**
     * 単一の真実のソース (Single Source of Truth)
     * ローカルDBを監視し、常に最新の履歴（API由来 + ローカル由来）をUIへ流します。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    val watchHistory: StateFlow<List<KonomiHistoryProgram>> = repository.getLocalWatchHistory()
        .map { entities ->
            entities.map { it.toKonomiHistoryProgram() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val lastWatchedChannelFlow: StateFlow<List<Channel>> = repository.getLastChannels()
        .map { entities: List<LastChannelEntity> -> // ★ ここが「List」であることを明示
            // リスト全体を map して、個々の Entity を Channel に変換する
            entities.map { entity ->
                Channel(
                    id = entity.channelId,
                    name = entity.name,
                    type = entity.type,
                    channelNumber = entity.channelNumber?: "",
                    // 以下、Channelクラスの必須パラメータを埋める
                    displayChannelId = entity.channelId,
                    networkId = entity.networkId,
                    serviceId = entity.serviceId,
                    isWatchable = true,
                    isDisplay = true,
                    programPresent = null,
                    programFollowing = null,
                    remocon_Id = 0
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList() // ★ 初期値も空リストに変更
        )

    init {
        // 初回起動時にデータを同期
        refreshHomeData()
    }

    /**
     * サーバーから最新の履歴を取得し、ローカルDBを更新する
     * DBが更新されると、上記の watchHistory Flow が自動的に発火してUIが変わります。
     */
    fun refreshHomeData() {
        viewModelScope.launch {
            _isLoading.value = true

            // APIから履歴を取得
            repository.getWatchHistory().onSuccess { apiHistoryList ->
                // APIから取得できた場合、それらをローカルDBに保存（API優先の同期）
                apiHistoryList.forEach { history ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        repository.saveToLocalHistory(history.toEntity())
                    }
                }
            }.onFailure {
                // APIエラー時はDBにある既存のデータがそのまま使われるため、何もしなくてOK
            }

            // ユーザー設定の更新
            repository.refreshUser()

            _isLoading.value = false
        }
    }

    /**
     * 番組視聴時にローカルDBへ保存する
     */
    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            repository.saveToLocalHistory(program.toEntity())
        }
    }

    fun saveLastChannel(channel: Channel) {
        viewModelScope.launch {
            repository.saveLastChannel(
                LastChannelEntity(
                    channelId = channel.id,
                    name = channel.name,
                    type = channel.type,
                    channelNumber = channel.channelNumber,
                    networkId = channel.networkId,
                    serviceId = channel.serviceId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
}