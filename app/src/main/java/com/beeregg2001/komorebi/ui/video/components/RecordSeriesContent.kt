package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.util.EpgUtils
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordSeriesContent(
    groupedSeries: Map<String, List<Pair<String, String>>>,
    isLoading: Boolean,
    onSeriesClick: (String) -> Unit,
    firstItemFocusRequester: FocusRequester,
    searchInputFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester,
    isSearchBarVisible: Boolean
) {
    val colors = KomorebiTheme.colors
    var selectedGenre by remember(groupedSeries) { mutableStateOf(groupedSeries.keys.firstOrNull()) }
    val firstGenreRequester = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ジャンル選択チップ
        TvLazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(groupedSeries.keys.toList()) { index, genre ->
                val genreColor = EpgUtils.getGenreColor(genre)
                FilterChip(
                    selected = selectedGenre == genre,
                    onClick = { selectedGenre = genre },
                    modifier = Modifier.then(
                        if (index == 0) Modifier.focusRequester(firstGenreRequester) else Modifier
                    ),
                    scale = FilterChipDefaults.scale(focusedScale = 1.0f),
                    colors = FilterChipDefaults.colors(
                        containerColor = colors.textPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = genreColor.copy(alpha = 0.4f),
                        selectedContainerColor = genreColor.copy(alpha = 0.2f),
                        focusedSelectedContainerColor = genreColor.copy(alpha = 0.6f)
                    ),
                    border = FilterChipDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.2f))),
                        focusedBorder = Border(BorderStroke(2.dp, colors.accent)),
                        selectedBorder = Border(BorderStroke(1.dp, genreColor))
                    )
                ) { Text(genre, color = colors.textPrimary) }
            }
        }

        // 作品名グリッド
        TvLazyVerticalGrid(
            columns = TvGridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.weight(1f)
        ) {
            val seriesList = groupedSeries[selectedGenre] ?: emptyList()
            itemsIndexed(seriesList) { index, pair ->
                var isFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onSeriesClick(pair.second) }, // キーワードで検索を実行
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusProperties {
                            if (index < 3) {
                                up = if (isSearchBarVisible) searchInputFocusRequester else backButtonFocusRequester
                            }
                        },
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.textPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textPrimary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                    )
                ) {
                    Text(
                        text = pair.first,
                        modifier = Modifier
                            .padding(16.dp)
                            .then(if (isFocused) Modifier.basicMarquee() else Modifier),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}