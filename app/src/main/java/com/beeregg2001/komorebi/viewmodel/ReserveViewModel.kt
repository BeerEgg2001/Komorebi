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

/**
 * 録画予約タブ（Reserve Tab）のUI状態とビジネスロジックを管理するViewModel。
 * KonomiTV（EDCBバックエンド）と通信し、単発の録画予約や自動予約条件（キーワード予約など）の
 * 取得・追加・更新・削除を行います。
 */
@HiltViewModel
class ReserveViewModel @Inject constructor(
    private val repository: KonomiRepository
) : ViewModel() {

    // ==========================================
    // UI状態の管理 (State)
    // ==========================================

    // 現在選択されているタブ（0: 単発予約リスト, 1: 自動予約条件リスト）のインデックス
    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    fun updateTabIndex(index: Int) {
        _selectedTabIndex.value = index
    }

    // サーバーから取得したすべての録画予約リスト
    private val _reserves = MutableStateFlow<List<ReserveItem>>(emptyList())
    val reserves: StateFlow<List<ReserveItem>> = _reserves.asStateFlow()

    // EDCBの自動予約によって生成された予約（EPG自動予約）を除外し、
    // 手動で登録した「単発予約」のみを抽出したリスト。UIの「単発予約タブ」で表示します。
    val normalReserves: StateFlow<List<ReserveItem>> = _reserves
        .map { list -> list.filter { !it.comment.contains("EPG自動予約") } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // サーバーから取得した自動予約条件（キーワードやジャンルによる自動録画のルール）のリスト
    private val _conditions = MutableStateFlow<List<ReservationCondition>>(emptyList())
    val conditions: StateFlow<List<ReservationCondition>> = _conditions.asStateFlow()

    // API通信中かどうかを示すローディングフラグ
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // ViewModel生成時に初期データをサーバーから取得
        fetchReserves()
        fetchConditions()
    }

    // ==========================================
    // データ取得系メソッド (Fetch)
    // ==========================================

    /**
     * 現在登録されているすべての録画予約（単発・自動生成問わず）を取得します。
     * @param showLoading trueの場合、取得中は画面にローディングインジケーターを表示します。
     */
    fun fetchReserves(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            repository.getReserves()
                .onSuccess { list -> _reserves.value = list }
                .onFailure { e -> Log.e(TAG, "Failed to fetch reservations", e) }
            if (showLoading) _isLoading.value = false
        }
    }

    /**
     * 現在登録されている自動予約条件（ルール）を取得します。
     */
    fun fetchConditions(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            repository.getReservationConditions()
                .onSuccess { list -> _conditions.value = list }
                .onFailure { e -> Log.e(TAG, "Failed to fetch conditions", e) }
            if (showLoading) _isLoading.value = false
        }
    }

    // ==========================================
    // 単発予約の操作メソッド (Single Reservation)
    // ==========================================

    /**
     * デフォルト設定で番組を単発予約します（番組表から「録画する」を押した場合など）。
     */
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

    /**
     * 録画マージンや優先度などの詳細設定を指定して、番組を単発予約します。
     */
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

    /**
     * 既に登録されている予約の設定（優先度や録画モードなど）を更新します。
     */
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

    /**
     * 予約を削除（キャンセル）します。
     */
    fun deleteReservation(reservationId: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteReservation(reservationId)
                .onSuccess { fetchReserves(); onSuccess() }
                .onFailure { e -> Log.e(TAG, "Failed to delete reservation", e); onSuccess() }
            _isLoading.value = false
        }
    }

    /**
     * 特定の予約項目の最新状態だけをサーバーから再取得してコールバックに返します。
     * （予約詳細画面を開く直前に最新ステータスを確認するためなどに使用）
     */
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

    // ==========================================
    // EPG自動予約（条件予約）の操作メソッド (Auto Reservation)
    // ==========================================

    /**
     * 番組表データに基づくキーワード検索条件（EPG自動予約）を新規作成します。
     * UIで入力された多数のパラメータを、KonomiTV/EDCBが解釈できるデータモデルに変換して送信します。
     */
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

            // 開始時刻より終了時刻の方が小さい場合は「日またぎ（翌日終了）」と判定して曜日を調整
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

            // 検索対象のサービス（チャンネル）を特定するID群
            val serviceRange = com.beeregg2001.komorebi.data.model.ProgramSearchConditionService(
                networkId = networkId,
                transportStreamId = transportStreamId,
                serviceId = serviceId
            )

            // 検索条件の組み立て（キーワード、除外ワード、あいまい検索など）
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

            // 録画実行時の振る舞い設定（優先度、イベントリレー追従など）
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
                    // 追加に成功したらリストを更新
                    fetchConditions(showLoading = false)
                    fetchReserves(showLoading = false)
                    _isLoading.value = false
                    onSuccess()

                    // 新しい条件がEDCBに登録された後、EDCBが実際に番組表を検索して
                    // 予約リスト（ReserveItem）を生成するまでにはタイムラグがあります。
                    // そのため、3秒後に裏でこっそり予約リストを再取得し、UIに反映させます。
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

    /**
     * 既存のEPG自動予約条件（キーワードや時間帯など）を更新します。
     */
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

            // 条件を更新する前に、古い条件によって既に生成されていた予約をいったん削除します。
            // EDCBは条件が更新されると新しい予約を作り直しますが、古い予約が残ってしまうことがあるためのフェイルセーフです。
            val exactComment = "EPG自動予約(${originalCondition.programSearchCondition.keyword})"
            val relatedReserves = _reserves.value.filter { it.comment == exactComment }
            relatedReserves.forEach { reserve ->
                repository.deleteReservation(reserve.id)
            }

            // 新しい時間帯・曜日の計算
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

            // 古い条件オブジェクトをコピーし、変更されたパラメータだけを上書き
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

                    // 追加時と同様、EDCBが新しい条件で予約を再構築するのを待ってから裏でリストを更新
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

    /**
     * 自動予約条件を削除します。
     * @param deleteRelatedReserves trueの場合、この条件によって生成されていた実際の予約項目も道連れにして一括削除します。
     */
    fun deleteConditionWithCleanup(
        condition: ReservationCondition,
        deleteRelatedReserves: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            // 1. まず条件（ルール）自体を削除
            repository.deleteReservationCondition(condition.id)

            // 2. ユーザーが希望した場合は、このルールによって生成された予約も削除
            // （EDCBの仕様上、ルールを消しても既に確定した未来の予約は残ってしまうため手動で消す必要があります）
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