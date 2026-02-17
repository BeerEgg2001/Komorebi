package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.model.ReserveItem
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
                    // 404 (Not Found) の場合もリストを更新してダイアログを閉じる
                    if (e.message?.contains("not found", ignoreCase = true) == true) {
                        fetchReserves()
                        onSuccess()
                    }
                }
            _isLoading.value = false
        }
    }
}