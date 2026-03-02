@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.ListFocusTarget
import com.beeregg2001.komorebi.ui.video.ListResetState
import com.beeregg2001.komorebi.ui.video.RecordListFocusRequesters
import com.beeregg2001.komorebi.ui.video.RecordListMenuState
import com.beeregg2001.komorebi.viewmodel.SeriesInfo

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RecordListOverlay(
    menuState: RecordListMenuState,
    focuses: RecordListFocusRequesters,
    resetState: ListResetState,
    selectedCategory: RecordCategory,
    availableGenres: List<String>,
    selectedGenre: String?,
    groupedChannels: Map<String, List<Pair<String, String>>>,
    selectedDay: String?,
    groupedSeries: Map<String, List<SeriesInfo>>,
    selectedSeriesGenre: String?,
    isNavOverlayVisible: Boolean,
    paneTransitionState: MutableTransitionState<Boolean>,
    hasContent: Boolean,
    isListFirstItemReady: Boolean,
    onCategorySelect: (RecordCategory) -> Unit,
    onGenreSelect: (String?) -> Unit,
    onChannelSelect: (String?) -> Unit,
    onDaySelect: (String?) -> Unit,
    onSeriesGenreSelect: (String?) -> Unit
) {
    val colors = KomorebiTheme.colors

    // 黒背景（半透明）
    AnimatedVisibility(
        visible = isNavOverlayVisible,
        enter = fadeIn(animationSpec = tween(350)),
        exit = fadeOut(animationSpec = tween(350))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    menuState.isNavPaneOpen = false
                    focuses.firstItem.safeRequestFocus()
                }
        )
    }

    // 左側のナビゲーションペイン（オーバーレイ時）
    AnimatedVisibility(
        visible = isNavOverlayVisible,
        enter = slideInHorizontally(animationSpec = tween(350)) { -it } + fadeIn(
            animationSpec = tween(
                350
            )
        ),
        exit = slideOutHorizontally(animationSpec = tween(350)) { -it } + fadeOut(
            animationSpec = tween(
                350
            )
        ),
        modifier = Modifier
            .zIndex(6f)
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .padding(start = 28.dp, bottom = 20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface.copy(alpha = 0.95f))
        ) {
            RecordNavigationPane(
                selectedCategory = selectedCategory,
                onCategorySelect = onCategorySelect,
                isOverlay = true,
                navPaneFocusRequester = focuses.navPane,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties {
                        right = when {
                            menuState.isGenrePaneOpen -> focuses.genrePane
                            menuState.isChannelPaneOpen -> focuses.channelPane
                            menuState.isDayPaneOpen -> focuses.dayPane
                            menuState.isSeriesGenrePaneOpen -> focuses.seriesGenrePane
                            else -> FocusRequester.Cancel
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.DirectionRight && !menuState.isPaneOpen) {
                                menuState.isNavPaneOpen =
                                    false; focuses.firstItem.safeRequestFocus(); return@onKeyEvent true
                            } else if (event.key == Key.Back || event.key == Key.Escape) {
                                menuState.isNavPaneOpen =
                                    false; focuses.firstItem.safeRequestFocus(); return@onKeyEvent true
                            }
                        }
                        false
                    }
            )
        }
    }

    // 右側の各種カテゴリ選択ペイン（アニメーションでスライドイン）
    AnimatedVisibility(
        visibleState = paneTransitionState,
        enter = slideInHorizontally(tween(350)) { -it },
        exit = slideOutHorizontally(tween(350)) { it } + fadeOut(tween(200)),
        modifier = Modifier
            .zIndex(7f)
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .offset(x = 240.dp)
                .width(220.dp)
                .fillMaxHeight()
                .padding(bottom = 20.dp)
                .background(colors.surface.copy(alpha = 0.98f))
        ) {
            when {
                menuState.isGenrePaneOpen -> RecordGenrePane(
                    genres = availableGenres,
                    selectedGenre = selectedGenre,
                    onGenreSelect = { genre ->
                        menuState.isSelectionMade =
                            true; onGenreSelect(genre); menuState.isGenrePaneOpen =
                        false; menuState.isNavPaneOpen = false; resetState.reset()
                    },
                    onClosePane = {
                        menuState.isGenrePaneOpen = false; focuses.navPane.safeRequestFocus()
                    },
                    firstItemFocusRequester = focuses.paneFirstItem,
                    onFirstItemBound = { menuState.isPaneListReady = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focuses.genrePane)
                        .focusProperties { left = focuses.navPane; right = FocusRequester.Cancel }
                )

                menuState.isChannelPaneOpen -> RecordChannelPane(
                    groupedChannels = groupedChannels,
                    onChannelSelect = { channelId ->
                        menuState.isSelectionMade =
                            true; onChannelSelect(channelId); menuState.isChannelPaneOpen =
                        false; menuState.isNavPaneOpen = false; resetState.reset()
                    },
                    onClosePane = {
                        menuState.isChannelPaneOpen = false; focuses.navPane.safeRequestFocus()
                    },
                    firstItemFocusRequester = focuses.paneFirstItem,
                    onFirstItemBound = { menuState.isPaneListReady = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focuses.channelPane)
                        .focusProperties { left = focuses.navPane; right = FocusRequester.Cancel }
                )

                menuState.isDayPaneOpen -> RecordDayPane(
                    selectedDay = selectedDay,
                    onDaySelect = { day ->
                        menuState.isSelectionMade =
                            true; onDaySelect(day); menuState.isDayPaneOpen =
                        false; menuState.isNavPaneOpen = false; resetState.reset()
                    },
                    onClosePane = {
                        menuState.isDayPaneOpen = false; focuses.navPane.safeRequestFocus()
                    },
                    firstItemFocusRequester = focuses.paneFirstItem,
                    onFirstItemBound = { menuState.isPaneListReady = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focuses.dayPane)
                        .focusProperties { left = focuses.navPane; right = FocusRequester.Cancel }
                )

                menuState.isSeriesGenrePaneOpen -> RecordGenrePane(
                    genres = groupedSeries.keys.toList(),
                    selectedGenre = selectedSeriesGenre,
                    onGenreSelect = { genre ->
                        menuState.isSelectionMade =
                            true; onSeriesGenreSelect(genre); menuState.isSeriesGenrePaneOpen =
                        false; menuState.isNavPaneOpen = false; resetState.reset()
                    },
                    onClosePane = {
                        menuState.isSeriesGenrePaneOpen = false; focuses.navPane.safeRequestFocus()
                    },
                    firstItemFocusRequester = focuses.paneFirstItem,
                    onFirstItemBound = { menuState.isPaneListReady = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focuses.seriesGenrePane)
                        .focusProperties { left = focuses.navPane; right = FocusRequester.Cancel }
                )
            }
        }
    }

    // 左側のナビゲーションペイン（通常時/固定表示）
    Box(
        modifier = Modifier
            .zIndex(2f)
            .width(240.dp)
            .fillMaxHeight()
            .padding(start = 28.dp, bottom = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface.copy(alpha = 0.5f))
            .focusProperties {
                if (menuState.isPaneOpen) {
                    up = FocusRequester.Cancel; down = FocusRequester.Cancel; left =
                        FocusRequester.Cancel; right = FocusRequester.Cancel
                }
            }
            .onFocusChanged { menuState.isNavFocused = it.hasFocus }
    ) {
        RecordNavigationPane(
            selectedCategory = selectedCategory,
            onCategorySelect = onCategorySelect,
            isOverlay = false,
            navPaneFocusRequester = focuses.navPane,
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    right = when {
                        menuState.isGenrePaneOpen -> focuses.genrePane
                        menuState.isChannelPaneOpen -> focuses.channelPane
                        menuState.isDayPaneOpen -> focuses.dayPane
                        menuState.isSeriesGenrePaneOpen -> focuses.seriesGenrePane
                        hasContent && isListFirstItemReady -> focuses.firstItem
                        else -> FocusRequester.Cancel
                    }
                }
        )
    }
}