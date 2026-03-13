package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import com.beeregg2001.komorebi.data.model.ReserveRequest
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ReserveViewModel"

@HiltViewModel
class ReserveViewModel @Inject constructor(
    private val repository: KonomiRepository
) : ViewModel() {

    private val _reserves = MutableStateFlow<List<ReserveItem>>(emptyList())
    val reserves: StateFlow<List<ReserveItem>> = _reserves.asStateFlow()

    val normalReserves: StateFlow<List<ReserveItem>> = _reserves
        .map { list -> list.filter { !it.comment.contains("EPG自動予約") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _conditions = MutableStateFlow<List<ReservationCondition>>(emptyList())
    val conditions: StateFlow<List<ReservationCondition>> = _conditions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            delay(1000)
            fetchAll()
        }
    }

    fun fetchAll() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getReserves()
                .onSuccess { list -> _reserves.value = list.sortedBy { it.program.startTime } }
                .onFailure { e ->
                    Log.e(
                        TAG,
                        "Failed to fetch reservations. Check data model mismatch.",
                        e
                    )
                }

            repository.getReservationConditions()
                .onSuccess { list -> _conditions.value = list }
                .onFailure { e -> Log.e(TAG, "Failed to fetch reservation conditions.", e) }

            _isLoading.value = false
        }
    }

    fun fetchReserves() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getReserves()
                .onSuccess { list -> _reserves.value = list.sortedBy { it.program.startTime } }
                .onFailure { e ->
                    Log.e(
                        TAG,
                        "Failed to fetch reservations. Check data model mismatch.",
                        e
                    )
                }
            _isLoading.value = false
        }
    }

    fun fetchConditions() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getReservationConditions()
                .onSuccess { list -> _conditions.value = list }
                .onFailure { e -> Log.e(TAG, "Failed to fetch reservation conditions.", e) }
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

    fun addReserveWithSettings(
        programId: String,
        settings: ReserveRecordSettings,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val safeSettings = settings.copy(recordingMode = "SpecifiedService")
            val request = ReserveRequest(programId = programId, recordSettings = safeSettings)

            repository.addReserve(request)
                .onSuccess {
                    fetchReserves()
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to add reservation", e)
                    onSuccess()
                }
            _isLoading.value = false
        }
    }

    fun updateReservation(
        item: ReserveItem,
        newSettings: ReserveRecordSettings,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
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

    // ★修正: transportStreamId を引数に追加し、0固定を廃止
    fun addEpgReserve(
        keyword: String,
        networkId: Int,
        transportStreamId: Int, // ★追加
        serviceId: Int,
        daysOfWeek: Set<Int>,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true

            // 日またぎの判定
            val isNextDay = endHour < startHour || (endHour == startHour && endMinute < startMinute)

            val dateRanges = daysOfWeek.map { dayOfWeek ->
                com.beeregg2001.komorebi.data.model.ProgramSearchConditionDate(
                    startDayOfWeek = dayOfWeek,
                    startHour = startHour,
                    startMinute = startMinute,
                    endDayOfWeek = if (isNextDay) (dayOfWeek + 1) % 7 else dayOfWeek,
                    endHour = endHour,
                    endMinute = endMinute
                )
            }

            val serviceRange = com.beeregg2001.komorebi.data.model.ProgramSearchConditionService(
                networkId = networkId,
                transportStreamId = transportStreamId, // ★修正: 0 から引数に変更
                serviceId = serviceId
            )

            val searchCondition = com.beeregg2001.komorebi.data.model.ProgramSearchCondition(
                isEnabled = true,
                keyword = keyword,
                serviceRanges = listOf(serviceRange),
                dateRanges = dateRanges,
                duplicateTitleCheckScope = "SameChannelOnly",
                duplicateTitleCheckPeriodDays = 6
            )

            val recordSettings = com.beeregg2001.komorebi.data.model.RecordSettings(
                isEnabled = true,
                priority = 3,
                recordingMode = "SpecifiedService",
                isEventRelayFollowEnabled = true
            )

            val request = com.beeregg2001.komorebi.data.model.ReservationConditionAddRequest(
                programSearchCondition = searchCondition,
                recordSettings = recordSettings
            )

            repository.addReservationCondition(request)
                .onSuccess {
                    fetchConditions()
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to add EPG reservation", e)
                    onSuccess()
                }

            _isLoading.value = false
        }
    }

    // ★追加: 登録済みのEPG予約条件を更新する
    fun updateEpgReserve(
        conditionId: Int,
        keyword: String,
        networkId: Int,
        transportStreamId: Int,
        serviceId: Int,
        daysOfWeek: Set<Int>,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true

            val isNextDay = endHour < startHour || (endHour == startHour && endMinute < startMinute)
            val dateRanges = daysOfWeek.map { dayOfWeek ->
                com.beeregg2001.komorebi.data.model.ProgramSearchConditionDate(
                    startDayOfWeek = dayOfWeek,
                    startHour = startHour,
                    startMinute = startMinute,
                    endDayOfWeek = if (isNextDay) (dayOfWeek + 1) % 7 else dayOfWeek,
                    endHour = endHour,
                    endMinute = endMinute
                )
            }

            val serviceRange = com.beeregg2001.komorebi.data.model.ProgramSearchConditionService(
                networkId = networkId, transportStreamId = transportStreamId, serviceId = serviceId
            )

            val searchCondition = com.beeregg2001.komorebi.data.model.ProgramSearchCondition(
                isEnabled = true,
                keyword = keyword,
                serviceRanges = listOf(serviceRange),
                dateRanges = dateRanges,
                duplicateTitleCheckScope = "SameChannelOnly",
                duplicateTitleCheckPeriodDays = 6
            )

            val recordSettings = com.beeregg2001.komorebi.data.model.RecordSettings(
                isEnabled = true,
                priority = 3,
                recordingMode = "SpecifiedService",
                isEventRelayFollowEnabled = true
            )

            val request = com.beeregg2001.komorebi.data.model.ReservationConditionUpdateRequest(
                programSearchCondition = searchCondition, recordSettings = recordSettings
            )

            repository.updateReservationCondition(conditionId, request)
                .onSuccess {
                    fetchConditions()
                    onSuccess()
                }
                .onFailure { e -> Log.e(TAG, "Failed to update EPG reservation", e); onSuccess() }

            _isLoading.value = false
        }
    }

    // ★追加: 条件の削除と、付随する関連予約の一掃 (クリーンアップ UseCase)
    fun deleteConditionWithCleanup(
        condition: ReservationCondition,
        deleteRelatedReserves: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true

            // 1. まず自動予約条件の本体を削除
            repository.deleteReservationCondition(condition.id)

            // 2. 関連予約を一掃する場合
            if (deleteRelatedReserves) {
                val keyword = condition.programSearchCondition.keyword
                val exactComment = "EPG自動予約($keyword)" // バックエンドが付与するコメントと完全一致させる

                // 現在の予約リストから、この条件によって登録された番組だけを抽出して全て削除
                val relatedReserves = _reserves.value.filter { it.comment == exactComment }
                relatedReserves.forEach { reserve ->
                    repository.deleteReservation(reserve.id)
                }
            }

            fetchConditions()
            fetchReserves()
            onSuccess()
            _isLoading.value = false
        }
    }
}