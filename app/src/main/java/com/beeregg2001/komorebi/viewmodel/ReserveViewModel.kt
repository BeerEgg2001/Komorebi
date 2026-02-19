package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.data.model.ReserveRequest
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
        viewModelScope.launch {
            delay(1000)
            fetchReserves()
        }
    }

    fun fetchReserves() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getReserves()
                .onSuccess { list ->
                    _reserves.value = list.sortedBy { it.program.startTime }
                }
                .onFailure { e ->
                    // ★修正: エラー内容を詳細にログ出力（開発時の切り分け用）
                    Log.e(TAG, "Failed to fetch reservations. Check data model mismatch.", e)
                }
            _isLoading.value = false
        }
    }

    fun addReserve(programId: String, onSuccess: () -> Unit) {
        val defaultSettings = ReserveRecordSettings(
            isEnabled = true,
            priority = 3,
            recordingMode = "SpecifiedService",
            isEventRelayFollowEnabled = true
        )
        addReserveWithSettings(programId, defaultSettings, onSuccess)
    }

    fun addReserveWithSettings(programId: String, settings: ReserveRecordSettings, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            // ★修正: recordingMode を固定
            val safeSettings = settings.copy(recordingMode = "SpecifiedService")
            val request = ReserveRequest(programId = programId, recordSettings = safeSettings)

            repository.addReserve(request)
                .onSuccess {
                    fetchReserves()
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to add reservation", e)
                    onSuccess() // 失敗しても画面を閉じれるように呼ぶ
                }
            _isLoading.value = false
        }
    }

    fun updateReservation(item: ReserveItem, newSettings: ReserveRecordSettings, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            // ★修正: 422エラーを防ぐため録画モードを固定。API仕様に合わせ末尾に 's' が必要
            val safeSettings = newSettings.copy(recordingMode = "SpecifiedService")

            val request = ReserveRequest(
                programId = item.program.id,
                recordSettings = safeSettings
            )

            repository.updateReserve(item.id, request)
                .onSuccess {
                    fetchReserves()
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to update reservation", e)
                    // ★重要: 失敗時もダイアログを閉じて「無反応」を解消する
                    onSuccess()
                }
            _isLoading.value = false
        }
    }

    fun refreshReserveItem(reservationId: Int, onComplete: (ReserveItem?) -> Unit) {
        viewModelScope.launch {
            repository.getReserves()
                .onSuccess { list ->
                    val latest = list.find { it.id == reservationId }
                    _reserves.value = list.sortedBy { it.program.startTime }
                    onComplete(latest)
                }
                .onFailure { onComplete(null) }
        }
    }

    fun deleteReservation(reservationId: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteReservation(reservationId)
                .onSuccess {
                    fetchReserves()
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to delete reservation", e)
                    fetchReserves()
                    onSuccess()
                }
            _isLoading.value = false
        }
    }
}