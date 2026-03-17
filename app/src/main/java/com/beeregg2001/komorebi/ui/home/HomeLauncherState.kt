package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus

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

    // --- データ保持 (rememberHomeLauncherState から自動更新される) ---
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

    // --- 録画・シリーズ関連の状態保持 ---
    var openedSeriesTitle by mutableStateOf<String?>(null)
    var isSeriesListOpen by mutableStateOf(false)

    // ★修正: 球団名ごとにグループ化されたリストを受け取る
    var favoriteBaseballTeams by mutableStateOf<Set<String>>(emptySet())
    var favoriteBaseballGames by mutableStateOf<List<Pair<String, List<BaseballGameInfo>>>>(
        emptyList()
    )

    val tabFocusRequesters = List(6) { FocusRequester() }
    val contentFirstItemRequesters = List(6) { FocusRequester() }
    val settingsFocusRequester = FocusRequester()

    val watchHistoryPrograms: List<RecordedProgram>
        @RequiresApi(Build.VERSION_CODES.O) get() = watchHistory.map {
            KonomiDataMapper.toDomainModel(it)
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

    @RequiresApi(Build.VERSION_CODES.O)
    fun onTabSelected(
        index: Int,
        onTabChange: (Int) -> Unit,
        homeViewModel: HomeViewModel,
        channelViewModel: ChannelViewModel,
        recordViewModel: RecordViewModel,
        reserveViewModel: ReserveViewModel
    ) {
        if (selectedTabIndex == index) return

        isCurrentTabContentReady = false
        selectedTabIndex = index
        onTabChange(index)
        when (index) {
            0 -> {
                homeViewModel.refreshHomeData()
                channelViewModel.fetchChannels()
            }

            1 -> channelViewModel.fetchChannels()
            2 -> recordViewModel.fetchRecentRecordings(forceRefresh = false)
            4 -> reserveViewModel.fetchReserves()
        }
    }

    fun handleBackNavigation(
        onTabChange: (Int) -> Unit, onFinalBack: () -> Unit, onBackTriggered: () -> Unit
    ) {
        if (!topNavHasFocus) {
            tabFocusRequesters.getOrNull(selectedTabIndex)?.safeRequestFocus("Home_Back")
        } else {
            if (selectedTabIndex > 0) {
                isCurrentTabContentReady = false
                selectedTabIndex = 0
                onTabChange(0)
            } else {
                onFinalBack()
            }
        }
        onBackTriggered()
    }

    fun requestFocusToCurrentTab() {
        tabFocusRequesters.getOrNull(selectedTabIndex)?.safeRequestFocus("HomeLauncher_CurrentTab")
    }

    fun requestFocusToCurrentContent() {
        contentFirstItemRequesters.getOrNull(selectedTabIndex)
            ?.safeRequestFocus("HomeLauncher_Content")
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
    val savedTabIndex = rememberSaveable { mutableIntStateOf(initialTabIndex) }
    val state = remember { HomeLauncherState(savedTabIndex.intValue) }

    LaunchedEffect(state.selectedTabIndex) {
        savedTabIndex.intValue = state.selectedTabIndex
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