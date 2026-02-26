package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

private const val TAG = "RecordSearchHistoryDropdown"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordSearchHistoryDropdown(
    limitedHistory: List<String>,
    historyListFocusRequester: FocusRequester,
    historyFirstItemFocusRequester: FocusRequester, // ★追加：最初のアイテム用
    searchInputFocusRequester: FocusRequester,
    firstItemFocusRequester: FocusRequester, // これは下の番組リストの1番目
    onExecuteSearch: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (limitedHistory.isEmpty()) return

    val colors = KomorebiTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(100f)
            .onFocusChanged { onFocusChanged(it.isFocused || it.hasFocus) }
            .background(
                colors.surface,
                RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
            )
            .border(
                1.dp,
                colors.textPrimary.copy(alpha = 0.2f),
                RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
            )
    ) {
        TvLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup() // ★重要：グループ化してフォーカスを逃がさないようにする
                .focusRequester(historyListFocusRequester)
        ) {
            itemsIndexed(limitedHistory) { index, historyItem ->
                Surface(
                    onClick = { onExecuteSearch(historyItem) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            // ★追加：最初のアイテムにRequesterを付与
                            if (index == 0) Modifier.focusRequester(historyFirstItemFocusRequester)
                            else Modifier
                        )
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        if (index == 0) {
                                            searchInputFocusRequester.safeRequestFocus(TAG)
                                            return@onKeyEvent true
                                        }
                                    }

                                    Key.DirectionDown -> {
                                        if (index == limitedHistory.size - 1) {
                                            firstItemFocusRequester.safeRequestFocus(TAG)
                                            return@onKeyEvent true
                                        }
                                    }
                                }
                            }
                            false
                        }
                        .focusProperties {
                            // 明示的に上下の繋がりを固定する
                            if (index == limitedHistory.size - 1) {
                                down = firstItemFocusRequester
                            }
                            if (index == 0) {
                                up = searchInputFocusRequester
                            }
                        },
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = colors.textPrimary.copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.History,
                            null,
                            Modifier.size(20.dp),
                            tint = colors.textSecondary
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = historyItem,
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}