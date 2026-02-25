package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    searchInputFocusRequester: FocusRequester,
    firstItemFocusRequester: FocusRequester,
    onExecuteSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 64.dp)
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
                .heightIn(max = 320.dp)
        ) {
            itemsIndexed(
                items = limitedHistory,
                contentType = { _, _ -> "HistoryItem" }
            ) { index, historyItem ->
                Surface(
                    onClick = { onExecuteSearch(historyItem) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (index == 0) Modifier.focusRequester(historyListFocusRequester) else Modifier
                        )
                        .onKeyEvent {
                            if (index == 0 && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                searchInputFocusRequester.safeRequestFocus(TAG)
                                return@onKeyEvent true
                            }
                            false
                        }
                        .focusProperties {
                            if (index == limitedHistory.size - 1) down = firstItemFocusRequester
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.History,
                            null,
                            Modifier.size(18.dp),
                            tint = colors.textSecondary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = historyItem,
                            color = colors.textPrimary,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}