package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.viewmodel.*

// ホーム画面専用のチケットマネージャー
enum class HomeFocusTicket { NONE, TAB_BAR, CONTENT_TOP }

@Stable
class HomeFocusTicketManager {
    var currentTicket by mutableStateOf(HomeFocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set

    fun issue(ticket: HomeFocusTicket) {
        currentTicket = ticket
        issueTime = System.currentTimeMillis()
    }

    fun consume(ticket: HomeFocusTicket) {
        if (currentTicket == ticket) {
            currentTicket = HomeFocusTicket.NONE
        }
    }
}

@Composable
fun rememberHomeFocusTicketManager() = remember { HomeFocusTicketManager() }

/**
 * HomeLauncherScreen の UI状態とビジネスロジックを管理する State Holder
 */
@Stable
class HomeLauncherState(
    initialTabIndex: Int,
) {
    // --- 内部UI状態 ---
    var selectedTabIndex by mutableIntStateOf(initialTabIndex)
    var internalLastPlayerChannelId by mutableStateOf<String?>(null)
    var isEpgJumping by mutableStateOf(false)
    var topNavHasFocus by mutableStateOf(false)
    var isCurrentTabContentReady by mutableStateOf(false)

    // --- データ保持 ---
    var watchHistory by mutableStateOf<List<KonomiHistoryProgram>>(emptyList())
    var lastChannels by mutableStateOf<List<Channel>>(emptyList())
    var recentRecordings by mutableStateOf<List<RecordedProgram>>(emptyList())
    var isLoadingInitial by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var reserves by mutableStateOf<List<ReserveItem>>(emptyList())

    var hotChannels by mutableStateOf<List<UiChannelState>>(emptyList())
    var upcomingReserves by mutableStateOf<List<ReserveItem>>(emptyList())
    var genrePickup by mutableStateOf<List<Pair<EpgProgram, String>>>(emptyList())
    var pickupGenreLabel by mutableStateOf("アニメ")
    var genrePickupTimeSlot by mutableStateOf("夜")

    var epgUiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
    var logoUrls by mutableStateOf<List<String>>(emptyList())

    var openedSeriesTitle by mutableStateOf<String?>(null)
    var isSeriesListOpen by mutableStateOf(false)

    var favoriteBaseballTeams by mutableStateOf<Set<String>>(emptySet())
    var favoriteBaseballGames by mutableStateOf<List<Pair<String, List<BaseballGameInfo>>>>(
        emptyList()
    )

    val tabFocusRequesters = List(10) { FocusRequester() }
    val contentFirstItemRequesters = List(10) { FocusRequester() }
    val settingsFocusRequester = FocusRequester()

    // 🌟 フォーカスが迷子になった時の避難所
    val safeHouseRequester = FocusRequester()

    val watchHistoryPrograms: List<RecordedProgram>
        @RequiresApi(Build.VERSION_CODES.O) get() = watchHistory.map {
            KonomiDataMapper.toDomainModel(it)
        }

    fun onTabSelected(
        index: Int,
        onTabChange: (Int) -> Unit,
        homeViewModel: HomeViewModel,
        channelViewModel: ChannelViewModel,
        recordViewModel: RecordViewModel,
        reserveViewModel: ReserveViewModel
    ) {
        onTabChange(index)
        isCurrentTabContentReady = false
        when (index) {
            0 -> {
                homeViewModel.refreshHomeData()
                channelViewModel.startPolling()
            }

            1 -> {
                channelViewModel.startPolling()
            }

            2 -> {
                channelViewModel.stopPolling()
                recordViewModel.fetchRecentRecordings(forceRefresh = false)
            }

            3 -> {
                channelViewModel.stopPolling()
            }

            4 -> {
                channelViewModel.stopPolling()
                reserveViewModel.fetchReserves()
            }

            5 -> {
                channelViewModel.stopPolling()
            }

            else -> channelViewModel.stopPolling()
        }
    }

    fun isFullScreen(
        selectedChannel: Channel?,
        selectedProgram: RecordedProgram?,
        epgSelectedProgram: EpgProgram?,
        isSettingsOpen: Boolean,
        isRecordListOpen: Boolean,
        isReserveOverlayOpen: Boolean
    ): Boolean {
        return selectedChannel != null || selectedProgram != null || epgSelectedProgram != null ||
                isSettingsOpen || isRecordListOpen || isReserveOverlayOpen || isSeriesListOpen
    }

    // 🌟 修正: タブ切り替え時のフォーカス消失（迷子）を防ぐための SafeHouse 退避を追加
    fun handleBackNavigation(
        onTabChange: (Int) -> Unit,
        onFinalBack: () -> Unit,
        onBackTriggered: () -> Unit,
        requestTopNavFocus: () -> Unit,
        escapeToSafeHouse: () -> Unit // ★新規追加: 安全地帯への退避
    ) {
        if (!topNavHasFocus) {
            // 1. コンテンツにいる場合：まずは安全地帯に逃がしてからトップナビに戻す
            escapeToSafeHouse()
            requestTopNavFocus()
            onBackTriggered()
        } else if (selectedTabIndex != 0) {
            // 2. トップナビにいてホーム以外の場合：UIが消滅する前に安全地帯に逃がす
            escapeToSafeHouse()
            selectedTabIndex = 0
            onTabChange(0)
            requestTopNavFocus() // 確実にホームのタブにフォーカスを当て直す
            onBackTriggered()
        } else {
            // 3. ホームタブのトップナビにいる場合：アプリ終了
            onFinalBack()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun rememberHomeLauncherState(
    initialTabIndex: Int,
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel
): HomeLauncherState {

    val state = rememberSaveable(
        saver = Saver(
            save = {
                listOf(
                    it.selectedTabIndex,
                    it.internalLastPlayerChannelId,
                    it.openedSeriesTitle,
                    it.isSeriesListOpen
                )
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                val list = it as List<Any?>
                HomeLauncherState(list[0] as Int).apply {
                    internalLastPlayerChannelId = list[1] as String?
                    openedSeriesTitle = list[2] as String?
                    isSeriesListOpen = list[3] as Boolean
                }
            }
        )
    ) {
        HomeLauncherState(initialTabIndex)
    }

    LaunchedEffect(initialTabIndex) {
        state.selectedTabIndex = initialTabIndex
    }

    state.watchHistory = homeViewModel.watchHistory.collectAsState().value
    state.lastChannels = homeViewModel.lastWatchedChannelFlow.collectAsState().value
    state.recentRecordings = recordViewModel.recentRecordings.collectAsState().value
    state.isLoadingInitial = recordViewModel.isRecordingLoading.collectAsState().value
    state.isLoadingMore = recordViewModel.isLoadingMore.collectAsState().value
    state.reserves = reserveViewModel.reserves.collectAsState().value

    val liveRows by channelViewModel.liveRows.collectAsState()
    state.hotChannels = remember(liveRows) { homeViewModel.getHotChannels(liveRows) }
    state.upcomingReserves =
        remember(state.reserves) { homeViewModel.getUpcomingReserves(state.reserves) }
    state.genrePickup = homeViewModel.genrePickupPrograms.collectAsState().value
    state.pickupGenreLabel = homeViewModel.pickupGenreLabel.collectAsState().value
    state.genrePickupTimeSlot = homeViewModel.genrePickupTimeSlot.collectAsState().value
    state.epgUiState = epgViewModel.uiState
    state.logoUrls = remember(state.epgUiState) {
        val eData = state.epgUiState
        if (eData is EpgUiState.Success) eData.data.map { epgViewModel.getLogoUrl(it.channel) } else emptyList()
    }

    state.favoriteBaseballTeams = homeViewModel.favoriteBaseballTeams.collectAsState().value
    state.favoriteBaseballGames = homeViewModel.favoriteBaseballGames.collectAsState().value

    return state
}