@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

private const val TAG = "RecordScreenTopBar"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordScreenTopBar(
    isSearchBarVisible: Boolean,
    searchQuery: String,
    activeSearchQuery: String,
    currentDisplayTitle: String?,
    searchHistory: List<String>,
    hasHistory: Boolean,
    isListView: Boolean,
    searchCloseButtonFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    innerTextFieldFocusRequester: FocusRequester,
    historyListFocusRequester: FocusRequester,
    firstItemFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    searchOpenButtonFocusRequester: FocusRequester,
    viewToggleButtonFocusRequester: FocusRequester,
    onSearchQueryChange: (String) -> Unit,
    onExecuteSearch: (String) -> Unit,
    onBackPress: () -> Unit,
    onSearchOpen: () -> Unit,
    onViewToggle: () -> Unit,
    onKeyboardActiveClick: () -> Unit,
    onBackButtonFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val keyboardController = LocalSoftwareKeyboardController.current

    var isSearchInputFocused by remember { mutableStateOf(false) }
    var isHistoryFocused by remember { mutableStateOf(false) }

    // ★追加：履歴の最初のアイテムを直接狙い撃ちするためのRequester
    val historyFirstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            searchInputFocusRequester.safeRequestFocus(TAG)
        }
    }

    val iconButtonColors = IconButtonDefaults.colors(
        focusedContainerColor = colors.textPrimary,
        focusedContentColor = if (colors.isDark) Color.Black else Color.White,
        contentColor = colors.textPrimary
    )

    Box(modifier = modifier.fillMaxWidth()) {
        if (isSearchBarVisible) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onBackPress() },
                        modifier = Modifier.focusRequester(searchCloseButtonFocusRequester),
                        colors = iconButtonColors
                    ) {
                        Icon(Icons.Default.ArrowBack, "閉じる")
                    }

                    Spacer(Modifier.width(16.dp))

                    Surface(
                        onClick = {
                            onKeyboardActiveClick()
                            innerTextFieldFocusRequester.safeRequestFocus(TAG)
                            keyboardController?.show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .focusRequester(searchInputFocusRequester)
                            .onFocusChanged { isSearchInputFocused = it.isFocused || it.hasFocus }
                            .onKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                    if (hasHistory) {
                                        // ★修正：リスト全体ではなく最初のアイテムに飛ばす
                                        historyFirstItemFocusRequester.safeRequestFocus(TAG)
                                        return@onKeyEvent true
                                    } else {
                                        firstItemFocusRequester.safeRequestFocus(TAG)
                                        return@onKeyEvent true
                                    }
                                }
                                false
                            }
                            .focusProperties {
                                left = searchCloseButtonFocusRequester
                                // ★修正：下方向も最初のアイテムを明示
                                down =
                                    if (hasHistory) historyFirstItemFocusRequester else firstItemFocusRequester
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
                                    .focusRequester(innerTextFieldFocusRequester)
                                    .onFocusChanged {
                                        if (it.isFocused) isSearchInputFocused = true
                                    },
                                textStyle = TextStyle(color = colors.textPrimary, fontSize = 20.sp),
                                cursorBrush = SolidColor(colors.textPrimary),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    onExecuteSearch(searchQuery)
                                }),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "番組名を検索...",
                                                color = colors.textSecondary,
                                                fontSize = 18.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    IconButton(
                        onClick = { onExecuteSearch(searchQuery) },
                        colors = iconButtonColors
                    ) {
                        Icon(Icons.Default.Search, "検索実行")
                    }
                }

                if ((isSearchInputFocused || isHistoryFocused) && searchHistory.isNotEmpty()) {
                    RecordSearchHistoryDropdown(
                        limitedHistory = searchHistory.take(5),
                        historyListFocusRequester = historyListFocusRequester,
                        historyFirstItemFocusRequester = historyFirstItemFocusRequester, // ★追加
                        searchInputFocusRequester = searchInputFocusRequester,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onExecuteSearch = onExecuteSearch,
                        onFocusChanged = { isHistoryFocused = it },
                        modifier = Modifier
                            .zIndex(100f)
                            .padding(top = 48.dp, start = 64.dp, end = 64.dp)
                    )
                }
            }
        } else {
            // タイトルモード（変更なし）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onBackPress() },
                    modifier = Modifier
                        .focusRequester(backButtonFocusRequester)
                        .onFocusChanged { onBackButtonFocusChanged(it.isFocused) }
                        .focusProperties {
                            down = firstItemFocusRequester
                            left = FocusRequester.Cancel
                        },
                    colors = iconButtonColors
                ) {
                    Icon(Icons.Default.ArrowBack, "戻る")
                }

                Spacer(Modifier.width(16.dp))

                Text(
                    text = currentDisplayTitle
                        ?: if (activeSearchQuery.isEmpty()) "録画リスト" else "「${activeSearchQuery}」の検索結果",
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = 20.sp,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                var isToggleFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onViewToggle,
                    modifier = Modifier
                        .focusRequester(viewToggleButtonFocusRequester)
                        .onFocusChanged { isToggleFocused = it.isFocused }
                        .focusProperties { down = firstItemFocusRequester },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.textPrimary.copy(alpha = 0.1f),
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textPrimary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activeTint =
                            if (isToggleFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent
                        val inactiveTint =
                            if (isToggleFocused) (if (colors.isDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(
                                alpha = 0.4f
                            )) else colors.textPrimary.copy(alpha = 0.4f)

                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "リスト表示",
                            tint = if (isListView) activeTint else inactiveTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "|",
                            color = inactiveTint,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light
                        )
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "グリッド表示",
                            tint = if (!isListView) activeTint else inactiveTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                IconButton(
                    onClick = onSearchOpen,
                    modifier = Modifier
                        .focusRequester(searchOpenButtonFocusRequester)
                        .focusProperties { down = firstItemFocusRequester },
                    colors = iconButtonColors
                ) {
                    Icon(Icons.Default.Search, "検索")
                }
            }
        }
    }
}