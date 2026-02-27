package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecordDayPane(
    selectedDay: String?,
    onDaySelect: (String) -> Unit,
    onClosePane: () -> Unit,
    firstItemFocusRequester: FocusRequester,
    onFirstItemBound: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val listState = rememberLazyListState()

    val days = listOf("月曜日", "火曜日", "水曜日", "木曜日", "金曜日", "土曜日", "日曜日")

    val isListReady by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() } }
    LaunchedEffect(isListReady) {
        onFirstItemBound(isListReady)
    }

    Surface(
        modifier = modifier.fillMaxHeight(),
        colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(16.dp),
        border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
    ) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)) {
            Text(
                text = "曜日を選択",
                style = MaterialTheme.typography.titleSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
            )

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(days) { index, day ->
                    val isSelected = selectedDay == day
                    Surface(
                        onClick = { onDaySelect(day) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties {
                                // ★上下右のフォーカス逸脱をガード
                                if (index == 0) up = FocusRequester.Cancel
                                if (index == days.lastIndex) down = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionLeft, Key.Back, Key.Escape -> {
                                            onClosePane()
                                            true
                                        }

                                        else -> false
                                    }
                                } else false
                            },
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) colors.accent.copy(alpha = 0.15f) else Color.Transparent,
                            focusedContainerColor = colors.textPrimary,
                            contentColor = if (isSelected) colors.accent else colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(
                                BorderStroke(
                                    1.dp,
                                    colors.textPrimary.copy(alpha = 0.1f)
                                )
                            ),
                            focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(text = day, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}