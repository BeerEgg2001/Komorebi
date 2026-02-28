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
import androidx.compose.ui.focus.*
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
    var isViewToggleFocused by remember { mutableStateOf(false) }

    val historyFirstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchBarVisible) {
        if (isSearchBarVisible) {
            searchInputFocusRequester.safeRequestFocus(TAG)
        }
    }

    val iconButtonColors = IconButtonDefaults.colors(
        containerColor = colors.surface.copy(alpha = 0.5f),
        contentColor = colors.textPrimary,
        focusedContainerColor = colors.textPrimary,
        focusedContentColor = if (colors.isDark) Color.Black else Color.White
    )

    Box(modifier = modifier.fillMaxWidth()) {
        if (isSearchBarVisible) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onBackPress() },
                        modifier = Modifier
                            .focusRequester(searchCloseButtonFocusRequester)
                            .focusProperties { up = FocusRequester.Cancel },
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
                            .height(48.dp)
                            .focusRequester(searchInputFocusRequester)
                            .onFocusChanged { isSearchInputFocused = it.isFocused || it.hasFocus }
                            .focusProperties {
                                up = FocusRequester.Cancel
                                left = searchCloseButtonFocusRequester
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
                                    .focusRequester(innerTextFieldFocusRequester),
                                textStyle = TextStyle(color = colors.textPrimary, fontSize = 18.sp),
                                cursorBrush = SolidColor(colors.textPrimary),
                                singleLine = true,
                                maxLines = 1,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    onExecuteSearch(searchQuery)
                                    keyboardController?.hide()
                                }),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "番組名やキーワードを入力...",
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
                        onClick = { onExecuteSearch(searchQuery) },
                        modifier = Modifier.focusProperties { up = FocusRequester.Cancel },
                        colors = iconButtonColors
                    ) {
                        Icon(Icons.Default.Search, "検索実行")
                    }
                }

                if ((isSearchInputFocused || isHistoryFocused) && searchHistory.isNotEmpty()) {
                    RecordSearchHistoryDropdown(
                        limitedHistory = searchHistory.take(5),
                        historyListFocusRequester = historyListFocusRequester,
                        historyFirstItemFocusRequester = historyFirstItemFocusRequester,
                        searchInputFocusRequester = searchInputFocusRequester,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onExecuteSearch = onExecuteSearch,
                        onFocusChanged = { isHistoryFocused = it },
                        modifier = Modifier
                            .zIndex(150f)
                            .padding(top = 58.dp, start = 72.dp, end = 72.dp)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onBackPress() },
                    modifier = Modifier
                        .focusRequester(backButtonFocusRequester)
                        .onFocusChanged { onBackButtonFocusChanged(it.isFocused) }
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = firstItemFocusRequester
                            left = FocusRequester.Cancel
                        },
                    colors = iconButtonColors
                ) {
                    Icon(Icons.Default.ArrowBack, "戻る")
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentDisplayTitle ?: "録画リスト",
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = 22.sp,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    if (activeSearchQuery.isNotEmpty()) {
                        Text(
                            text = "検索結果: $activeSearchQuery",
                            fontSize = 13.sp,
                            color = colors.accent,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    onClick = onViewToggle,
                    modifier = Modifier
                        .focusRequester(viewToggleButtonFocusRequester)
                        .onFocusChanged { isViewToggleFocused = it.isFocused }
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = firstItemFocusRequester
                        },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.surface.copy(alpha = 0.5f),
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
                        val contrastColor = if (colors.isDark) Color.Black else Color.White
                        val activeColor = if (isViewToggleFocused) contrastColor else colors.accent
                        val inactiveColor =
                            if (isViewToggleFocused) contrastColor.copy(alpha = 0.4f) else colors.textPrimary.copy(
                                alpha = 0.4f
                            )

                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "リスト表示",
                            tint = if (isListView) activeColor else inactiveColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "|",
                            color = inactiveColor,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Light
                        )
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "グリッド表示",
                            tint = if (!isListView) activeColor else inactiveColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                IconButton(
                    onClick = { onSearchOpen() },
                    modifier = Modifier
                        .focusRequester(searchOpenButtonFocusRequester)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = firstItemFocusRequester
                        },
                    colors = iconButtonColors
                ) {
                    Icon(Icons.Default.Search, "検索")
                }
            }
        }
    }
}