@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.epg.components.EpgSearchResultsScreen
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.viewmodel.UiSearchResultItem
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.RecordSearchHistoryDropdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgNavigationContainer(
    uiState: EpgUiState,
    logoUrls: List<String>,
    mainTabFocusRequester: FocusRequester,
    contentRequester: FocusRequester,
    selectedProgram: EpgProgram?,
    onProgramSelected: (EpgProgram?) -> Unit,
    isJumpMenuOpen: Boolean,
    onJumpMenuStateChanged: (Boolean) -> Unit,
    onNavigateToPlayer: (String, String, String) -> Unit,
    currentType: String,
    onTypeChanged: (String) -> Unit,
    restoreChannelId: String?,
    availableTypes: List<String>,
    onJumpStateChanged: (Boolean) -> Unit,
    reserves: List<ReserveItem>,
    onUpdateTargetTime: (OffsetDateTime) -> Unit,
    searchQuery: String,
    searchHistory: List<String>,
    onSearchQueryChange: (String) -> Unit,
    onExecuteSearch: (String) -> Unit,
    activeSearchQuery: String,
    searchResults: List<UiSearchResultItem>,
    isSearching: Boolean,
    onClearSearch: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colors = KomorebiTheme.colors

    var isInternalJumping by remember { mutableStateOf(false) }

    val gridFocusRequester = remember { FocusRequester() }
    val jumpButtonRequester = remember { FocusRequester() }
    val headerFocusRequester = remember { contentRequester }

    var isSearchBarVisible by remember { mutableStateOf(false) }
    val searchButtonRequester = remember { FocusRequester() }
    val searchInputFocusRequester = remember { FocusRequester() }
    val innerTextFieldFocusRequester = remember { FocusRequester() }
    val searchCloseButtonFocusRequester = remember { FocusRequester() }
    val historyListFocusRequester = remember { FocusRequester() }
    val historyFirstItemFocusRequester = remember { FocusRequester() }

    val searchResultsFirstItemRequester = remember { FocusRequester() }
    val searchResultsBackButtonRequester = remember { FocusRequester() }

    val safeHouseRequester = remember { FocusRequester() }
    var wasSearching by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    var isSearchInputFocused by remember { mutableStateOf(false) }
    var isHistoryFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isInternalJumping) { onJumpStateChanged(isInternalJumping) }

    // ★修正: 検索実行時、安全地帯に逃してから少しディレイを挟んで検索バーを閉じる
    val performSearch = { query: String ->
        safeHouseRequester.safeRequestFocus("EscapeToSafeHouse_Search")
        keyboardController?.hide()
        onExecuteSearch(query)
        scope.launch {
            delay(100)
            isSearchBarVisible = false
        }
    }

    val handleBackFromSearchResults = {
        safeHouseRequester.safeRequestFocus("EscapeToSafeHouse_Back")
        onClearSearch()
    }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            delay(50)
            searchInputFocusRequester.safeRequestFocus("EpgSearchOpen")
        }
    }

    LaunchedEffect(activeSearchQuery) {
        if (activeSearchQuery.isNotEmpty()) {
            wasSearching = true
        } else if (activeSearchQuery.isEmpty() && wasSearching) {
            delay(300)
            searchButtonRequester.safeRequestFocus("BackFromSearch")
            wasSearching = false
        }
    }

    // ★修正: 検索中と完了時で「絶対に消えない要素」へフォーカスを誘導する
    LaunchedEffect(isSearching, activeSearchQuery) {
        if (activeSearchQuery.isNotEmpty()) {
            if (isSearching) {
                // 検索中は、画面に常駐する「戻るボタン」にフォーカスを置いて保護する！
                delay(50)
                searchResultsBackButtonRequester.safeRequestFocus("Searching_BackBtn")
            } else {
                // 検索完了後は、リストがレンダリングされるのを待ってから先頭アイテムへ
                delay(150)
                if (searchResults.isNotEmpty()) {
                    searchResultsFirstItemRequester.safeRequestFocus("SearchResults_List")
                } else {
                    searchResultsBackButtonRequester.safeRequestFocus("SearchResults_Empty")
                }
            }
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Transparent)) {

        // ★修正: 避難所から絶対にフォーカスが漏れないように要塞化する
        Box(
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(safeHouseRequester)
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }
                .focusable()
        )

        // ==========================================
        // メインコンテンツ切り替え
        // ==========================================
        Box(modifier = Modifier.fillMaxSize()) {
            if (activeSearchQuery.isEmpty()) {
                ModernEpgCanvasEngine_Smooth(
                    uiState = uiState,
                    logoUrls = logoUrls,
                    onProgramSelected = { onProgramSelected(it) },
                    topTabFocusRequester = mainTabFocusRequester,
                    headerFocusRequester = headerFocusRequester,
                    jumpButtonFocusRequester = jumpButtonRequester,
                    searchButtonFocusRequester = searchButtonRequester,
                    gridFocusRequester = gridFocusRequester,
                    currentType = currentType,
                    onTypeChanged = onTypeChanged,
                    availableTypes = availableTypes,
                    onEpgJumpMenuStateChanged = onJumpMenuStateChanged,
                    onSearchClick = { isSearchBarVisible = true },
                    restoreChannelId = restoreChannelId,
                    reserves = reserves,
                    onUpdateTargetTime = onUpdateTargetTime,
                    onRequestJumpToNow = {
                        scope.launch {
                            isInternalJumping = true
                            val now = OffsetDateTime.now()
                            onUpdateTargetTime(now)

                            delay(400)
                            gridFocusRequester.safeRequestFocus("EpgNav_JumpNow")
                            isInternalJumping = false
                        }
                    }
                )
            } else {
                BackHandler(enabled = true) { handleBackFromSearchResults() }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .onKeyEvent { event ->
                            if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                                if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                                if (event.type == KeyEventType.KeyUp) {
                                    handleBackFromSearchResults()
                                    return@onKeyEvent true
                                }
                            }
                            false
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { handleBackFromSearchResults() },
                            modifier = Modifier
                                .focusRequester(searchResultsBackButtonRequester)
                                .focusProperties {
                                    up = mainTabFocusRequester
                                    left = FocusRequester.Cancel
                                    down = searchResultsFirstItemRequester
                                },
                            colors = IconButtonDefaults.colors(
                                containerColor = colors.surface.copy(alpha = 0.5f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) {
                            Icon(Icons.Default.ArrowBack, "戻る")
                        }

                        Spacer(Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "番組検索",
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "検索結果: $activeSearchQuery",
                                fontSize = 13.sp,
                                color = colors.accent
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        EpgSearchResultsScreen(
                            searchResults = searchResults,
                            isSearching = isSearching,
                            reserves = reserves,
                            onProgramClick = { onProgramSelected(it) },
                            firstItemFocusRequester = searchResultsFirstItemRequester,
                            backButtonFocusRequester = searchResultsBackButtonRequester
                        )
                    }
                }
            }
        }

        // ==========================================
        // 検索バーオーバーレイ
        // ==========================================
        AnimatedVisibility(
            visible = isSearchBarVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier
                .zIndex(20f)
                .align(Alignment.TopCenter)
        ) {
            BackHandler(enabled = true) {
                isSearchBarVisible = false
                searchButtonRequester.safeRequestFocus("SearchClose")
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onKeyEvent { event ->
                        if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                            if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                            if (event.type == KeyEventType.KeyUp) {
                                isSearchBarVisible = false
                                searchButtonRequester.safeRequestFocus("SearchClose")
                                return@onKeyEvent true
                            }
                        }
                        false
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(colors.background.copy(alpha = 0.95f))
                        .padding(horizontal = 28.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                isSearchBarVisible = false
                                searchButtonRequester.safeRequestFocus("SearchClose")
                            },
                            modifier = Modifier
                                .focusRequester(searchCloseButtonFocusRequester)
                                .focusProperties {
                                    up = FocusRequester.Cancel; left = FocusRequester.Cancel
                                },
                            colors = IconButtonDefaults.colors(
                                containerColor = colors.surface.copy(alpha = 0.5f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) {
                            Icon(Icons.Default.ArrowBack, "閉じる")
                        }

                        Spacer(Modifier.width(16.dp))

                        Surface(
                            onClick = {
                                innerTextFieldFocusRequester.safeRequestFocus("TextFieldClick")
                                keyboardController?.show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .focusRequester(searchInputFocusRequester)
                                .onFocusChanged {
                                    isSearchInputFocused = it.isFocused || it.hasFocus
                                }
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    left = searchCloseButtonFocusRequester
                                    down =
                                        if (searchHistory.isNotEmpty()) historyFirstItemFocusRequester else FocusRequester.Cancel
                                },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                focusedContainerColor = colors.textPrimary.copy(alpha = 0.15f)
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(
                                    BorderStroke(
                                        1.dp,
                                        colors.textPrimary.copy(alpha = 0.3f)
                                    )
                                ),
                                focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            ) {
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(innerTextFieldFocusRequester),
                                    textStyle = TextStyle(
                                        color = colors.textPrimary,
                                        fontSize = 18.sp
                                    ),
                                    cursorBrush = SolidColor(colors.textPrimary),
                                    singleLine = true,
                                    maxLines = 1,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        performSearch(
                                            searchQuery
                                        )
                                    }),
                                    decorationBox = { innerTextField ->
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "未来の番組名や出演者を入力...",
                                                color = colors.textSecondary.copy(alpha = 0.6f),
                                                fontSize = 16.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        IconButton(
                            onClick = { performSearch(searchQuery) },
                            modifier = Modifier.focusProperties {
                                up = FocusRequester.Cancel; right = FocusRequester.Cancel
                            },
                            colors = IconButtonDefaults.colors(
                                containerColor = colors.surface.copy(alpha = 0.5f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) {
                            Icon(Icons.Default.Search, "検索実行")
                        }
                    }
                }

                if ((isSearchInputFocused || isHistoryFocused) && searchHistory.isNotEmpty()) {
                    RecordSearchHistoryDropdown(
                        limitedHistory = searchHistory,
                        historyListFocusRequester = historyListFocusRequester,
                        historyFirstItemFocusRequester = historyFirstItemFocusRequester,
                        searchInputFocusRequester = searchInputFocusRequester,
                        firstItemFocusRequester = searchCloseButtonFocusRequester,
                        onExecuteSearch = {
                            onSearchQueryChange(it)
                            performSearch(it)
                        },
                        onFocusChanged = { isHistoryFocused = it },
                        modifier = Modifier
                            .zIndex(150f)
                            .padding(top = 60.dp, start = 84.dp, end = 84.dp)
                    )
                }
            }
        }

        // ==========================================
        // 日時指定ジャンプメニュー
        // ==========================================
        AnimatedVisibility(
            visible = isJumpMenuOpen, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.zIndex(10f)
        ) {
            val now = remember { OffsetDateTime.now() }
            EpgJumpMenu(
                dates = remember(now) { List(7) { now.plusDays(it.toLong()) } },
                onSelect = { selectedTime ->
                    scope.launch {
                        isInternalJumping = true
                        onJumpMenuStateChanged(false)

                        onUpdateTargetTime(selectedTime)

                        delay(400)
                        gridFocusRequester.safeRequestFocus("EpgNav_JumpSelect")
                        isInternalJumping = false
                    }
                },
                onDismiss = {
                    onJumpMenuStateChanged(false)
                    jumpButtonRequester.safeRequestFocus("EpgNav_JumpDismiss")
                }
            )
        }
    }
}