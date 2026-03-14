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

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    fun updateTabIndex(index: Int) {
        _selectedTabIndex.value = index
    }

    private val _reserves = MutableStateFlow<List<ReserveItem>>(emptyList())
    val reserves: StateFlow<List<ReserveItem>> = _reserves.asStateFlow()

    val normalReserves: StateFlow<List<ReserveItem>> = _reserves
        .map { list -> list.filter { !it.comment.contains("EPG自動予約") } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _conditions = MutableStateFlow<List<ReservationCondition>>(emptyList())
    val conditions: StateFlow<List<ReservationCondition>> = _conditions.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        fetchReserves()
        fetchConditions()
    }

    fun fetchReserves(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            repository.getReserves()
                .onSuccess { list -> _reserves.value = list }
                .onFailure { e -> Log.e(TAG, "Failed to fetch reservations", e) }
            if (showLoading) _isLoading.value = false
        }
    }

    fun fetchConditions(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            repository.getReservationConditions()
                .onSuccess { list -> _conditions.value = list }
                .onFailure { e -> Log.e(TAG, "Failed to fetch conditions", e) }
            if (showLoading) _isLoading.value = false
        }
    }

    fun addReserve(programId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val request =
                ReserveRequest(programId = programId, recordSettings = ReserveRecordSettings())
            repository.addReserve(request)
                .onSuccess { fetchReserves(); onSuccess() }
                .onFailure { e -> Log.e(TAG, "Failed to add reservation", e); onSuccess() }
            _isLoading.value = false
        }
    }

    fun addReserveWithSettings(
        programId: String,
        settings: ReserveRecordSettings,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val request = ReserveRequest(programId = programId, recordSettings = settings)
            repository.addReserve(request)
                .onSuccess { fetchReserves(); onSuccess() }
                .onFailure { e -> Log.e(TAG, "Failed to add reservation", e); onSuccess() }
            _isLoading.value = false
        }
    }

    fun updateReservation(
        reserve: ReserveItem,
        newSettings: ReserveRecordSettings,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val request =
                ReserveRequest(programId = reserve.program.id, recordSettings = newSettings)
            repository.updateReserve(reserve.id, request)
                .onSuccess { fetchReserves(); onSuccess() }
                .onFailure { e -> Log.e(TAG, "Failed to update reservation", e); onSuccess() }
            _isLoading.value = false
        }
    }

    fun deleteReservation(reservationId: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteReservation(reservationId)
                .onSuccess { fetchReserves(); onSuccess() }
                .onFailure { e -> Log.e(TAG, "Failed to delete reservation", e); onSuccess() }
            _isLoading.value = false
        }
    }

    fun refreshReserveItem(reservationId: Int, onResult: (ReserveItem?) -> Unit) {
        viewModelScope.launch {
            repository.getReserves()
                .onSuccess { list ->
                    _reserves.value = list
                    val item = list.find { it.id == reservationId }
                    onResult(item)
                }
                .onFailure {
                    Log.e(TAG, "Failed to refresh item", it)
                    onResult(null)
                }
        }
    }

    // ★修正: 引数を大幅に追加し、新規作成のリクエストボディへマッピングする
    fun addEpgReserve(
        keyword: String,
        networkId: Int,
        transportStreamId: Int,
        serviceId: Int,
        daysOfWeek: Set<Int>,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        excludeKeyword: String,
        isTitleOnly: Boolean,
        broadcastType: String,
        isFuzzySearch: Boolean,
        duplicateScope: String,
        priority: Int,
        isEventRelay: Boolean,
        isExactRecord: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true

            val isNextDay = endHour < startHour || (endHour == startHour && endMinute < startMinute)
            val dateRanges = daysOfWeek.map { dayOfWeek ->
                com.beeregg2001.komorebi.data.model.ProgramSearchConditionDate(
                    startDayOfWeek = dayOfWeek, startHour = startHour, startMinute = startMinute,
                    endDayOfWeek = if (isNextDay) (dayOfWeek + 1) % 7 else dayOfWeek, endHour = endHour, endMinute = endMinute
                )
            }

            val serviceRange = com.beeregg2001.komorebi.data.model.ProgramSearchConditionService(
                networkId = networkId,
                transportStreamId = transportStreamId,
                serviceId = serviceId
            )

            // ★修正: 詳細設定の値をすべて反映
            val searchCondition = com.beeregg2001.komorebi.data.model.ProgramSearchCondition(
                isEnabled = true,
                keyword = keyword,
                excludeKeyword = excludeKeyword,
                isTitleOnly = isTitleOnly,
                broadcastType = broadcastType,
                isFuzzySearchEnabled = isFuzzySearch,
                serviceRanges = listOf(serviceRange),
                dateRanges = dateRanges,
                duplicateTitleCheckScope = duplicateScope,
                duplicateTitleCheckPeriodDays = 6
            )

            // ★修正: 録画設定側も反映
            val recordSettings = com.beeregg2001.komorebi.data.model.RecordSettings(
                isEnabled = true,
                priority = priority,
                recordingMode = "SpecifiedService",
                isEventRelayFollowEnabled = isEventRelay,
                isExactRecordingEnabled = isExactRecord
            )

            val request = com.beeregg2001.komorebi.data.model.ReservationConditionAddRequest(
                programSearchCondition = searchCondition,
                recordSettings = recordSettings
            )

            repository.addReservationCondition(request)
                .onSuccess {
                    fetchConditions(showLoading = false)
                    fetchReserves(showLoading = false)
                    _isLoading.value = false
                    onSuccess()

                    // EDCBが条件に基づき検索し、実際の予約を生成するのを待つ（3秒後に裏でこっそり更新）
                    viewModelScope.launch {
                        delay(3000)
                        fetchConditions(showLoading = false)
                        fetchReserves(showLoading = false)
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to add EPG reservation", e)
                    _isLoading.value = false
                    onSuccess()
                }
        }
    }

    fun updateEpgReserve(
        originalCondition: ReservationCondition,
        isEnabled: Boolean,
        keyword: String,
        daysOfWeek: Set<Int>,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        excludeKeyword: String,
        isTitleOnly: Boolean,
        broadcastType: String,
        isFuzzySearch: Boolean,
        duplicateScope: String,
        priority: Int,
        isEventRelay: Boolean,
        isExactRecord: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true

            val exactComment = "EPG自動予約(${originalCondition.programSearchCondition.keyword})"
            val relatedReserves = _reserves.value.filter { it.comment == exactComment }
            relatedReserves.forEach { reserve ->
                repository.deleteReservation(reserve.id)
            }

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

            val searchCondition = originalCondition.programSearchCondition.copy(
                isEnabled = isEnabled,
                keyword = keyword,
                excludeKeyword = excludeKeyword,
                isTitleOnly = isTitleOnly,
                broadcastType = broadcastType,
                isFuzzySearchEnabled = isFuzzySearch,
                duplicateTitleCheckScope = duplicateScope,
                dateRanges = dateRanges
            )

            val recordSettings = originalCondition.recordSettings.copy(
                priority = priority,
                isEventRelayFollowEnabled = isEventRelay,
                isExactRecordingEnabled = isExactRecord
            )

            val request = com.beeregg2001.komorebi.data.model.ReservationConditionUpdateRequest(
                programSearchCondition = searchCondition,
                recordSettings = recordSettings
            )

            repository.updateReservationCondition(originalCondition.id, request)
                .onSuccess {
                    fetchConditions(showLoading = false)
                    fetchReserves(showLoading = false)
                    _isLoading.value = false
                    onSuccess()

                    // EDCBが新しい条件で予約を再構築するのを待つ間、UIをパカパカさせずにこっそり更新
                    viewModelScope.launch {
                        delay(3000)
                        fetchConditions(showLoading = false)
                        fetchReserves(showLoading = false)
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to update EPG reservation", e)
                    _isLoading.value = false
                    onSuccess()
                }
        }
    }

    fun deleteConditionWithCleanup(
        condition: ReservationCondition,
        deleteRelatedReserves: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteReservationCondition(condition.id)

            if (deleteRelatedReserves) {
                val keyword = condition.programSearchCondition.keyword
                val exactComment = "EPG自動予約($keyword)"
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