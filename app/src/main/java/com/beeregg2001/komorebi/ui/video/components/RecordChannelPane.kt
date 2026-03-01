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
fun RecordChannelPane(
    groupedChannels: Map<String, List<Pair<String, String>>>,
    onChannelSelect: (String) -> Unit,
    onClosePane: () -> Unit,
    firstItemFocusRequester: FocusRequester,
    onFirstItemBound: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val listState = rememberLazyListState()
    var selectedType by remember { mutableStateOf<String?>(null) }
    val currentItems = remember(selectedType, groupedChannels) {
        if (selectedType == null) groupedChannels.keys.toList()
        else groupedChannels[selectedType] ?: emptyList()
    }
    LaunchedEffect(currentItems) { onFirstItemBound(currentItems.isNotEmpty()) }
    LaunchedEffect(selectedType) { delay(150); firstItemFocusRequester.safeRequestFocus("ChannelPaneChange") }
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
                text = if (selectedType == null) "放送波種別" else "チャンネル選択 ($selectedType)",
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
                itemsIndexed(currentItems) { index, item ->
                    // label: 表示名, id: キーワード(チャンネル名) または 種別(地デジ等)
                    val label =
                        if (item is Pair<*, *>) (item as Pair<String, String>).first else item.toString()
                    val id =
                        if (item is Pair<*, *>) (item as Pair<String, String>).second else item.toString()
                    Surface(
                        onClick = {
                            if (selectedType == null) selectedType = id else onChannelSelect(id)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties {
                                if (index == 0) up =
                                    FocusRequester.Cancel; if (index == currentItems.lastIndex) down =
                                FocusRequester.Cancel; right = FocusRequester.Cancel
                            }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionLeft, Key.Back, Key.Escape -> {
                                            if (selectedType != null) {
                                                selectedType = null; true
                                            } else {
                                                onClosePane(); true
                                            }
                                        }

                                        else -> false
                                    }
                                } else false
                            },
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = colors.textPrimary,
                            contentColor = colors.textPrimary,
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
                            Text(text = label, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}