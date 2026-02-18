package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.data.model.ReserveRequest
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ReserveViewModel"

@HiltViewModel
class ReserveViewModel @Inject constructor(
    private val repository: KonomiRepository
) : ViewModel() {

    private val _reserves = MutableStateFlow<List<ReserveItem>>(emptyList())
    val reserves: StateFlow<List<ReserveItem>> = _reserves.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        fetchReserves()
    }

    fun fetchReserves() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getReserves()
                .onSuccess { list ->
                    _reserves.value = list.sortedBy { it.program.startTime }
                }
                .onFailure {
                    it.printStackTrace()
                }
            _isLoading.value = false
        }
    }

    // 予約追加機能（簡易版：デフォルト設定を使用）
    fun addReserve(programId: String, onSuccess: () -> Unit) {
        // デフォルト設定を作成して詳細版を呼ぶ
        val defaultSettings = ReserveRecordSettings(
            isEnabled = true,
            priority = 3,
            recordingMode = "SpecifiedService",
            isEventRelayFollowEnabled = true
        )
        addReserveWithSettings(programId, defaultSettings, onSuccess)
    }

    // ★追加: 設定を指定して予約追加
    fun addReserveWithSettings(programId: String, settings: ReserveRecordSettings, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val request = ReserveRequest(
                programId = programId,
                recordSettings = settings
            )

            repository.addReserve(request)
                .onSuccess {
                    Log.i(TAG, "Added reservation for $programId with settings")
                    fetchReserves() // リスト更新
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to add reservation", e)
                }
            _isLoading.value = false
        }
    }

    // 予約情報更新機能
    fun updateReservation(item: ReserveItem, newSettings: ReserveRecordSettings, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true

            val request = ReserveRequest(
                programId = item.program.id,
                recordSettings = newSettings
            )

            repository.updateReserve(item.id, request)
                .onSuccess {
                    Log.i(TAG, "Updated reservation ${item.id}")
                    fetchReserves() // リスト更新
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to update reservation ${item.id}", e)
                }
            _isLoading.value = false
        }
    }

    // ★追加: 最新の予約情報を1件再取得する
    fun refreshReserveItem(reservationId: Int, onComplete: (ReserveItem?) -> Unit) {
        viewModelScope.launch {
            repository.getReserves()
                .onSuccess { list ->
                    val latest = list.find { it.id == reservationId }
                    _reserves.value = list.sortedBy { it.program.startTime }
                    onComplete(latest)
                }
                .onFailure {
                    onComplete(null)
                }
        }
    }

    // 予約削除機能
    fun deleteReservation(reservationId: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteReservation(reservationId)
                .onSuccess {
                    Log.i(TAG, "Deleted reservation $reservationId")
                    fetchReserves() // リスト更新
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to delete reservation $reservationId", e)
                    if (e.message?.contains("not found", ignoreCase = true) == true) {
                        fetchReserves()
                        onSuccess()
                    }
                }
            _isLoading.value = false
        }
    }
}