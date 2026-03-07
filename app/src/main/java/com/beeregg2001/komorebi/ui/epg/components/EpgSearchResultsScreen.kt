package com.beeregg2001.komorebi.ui.epg.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.viewmodel.UiSearchResultItem
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgSearchResultsScreen(
    searchResults: List<UiSearchResultItem>,
    isSearching: Boolean,
    reserves: List<ReserveItem>,
    onProgramClick: (EpgProgram) -> Unit,
    firstItemFocusRequester: FocusRequester,
    backButtonFocusRequester: FocusRequester // ★追加: 戻るボタンへの導線
) {
    val colors = KomorebiTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        if (isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(firstItemFocusRequester)
                    // ★追加: 検索中画面から上を押した時も確実に戻るボタンへ
                    .focusProperties { up = backButtonFocusRequester }
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else if (searchResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(firstItemFocusRequester)
                    // ★追加: 0件画面から上を押した時も確実に戻るボタンへ
                    .focusProperties { up = backButtonFocusRequester }
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "一致する未来の番組が見つかりませんでした",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textSecondary
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 28.dp,
                    end = 28.dp,
                    top = 20.dp,
                    bottom = 40.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(searchResults, key = { _, item -> item.program.id }) { index, item ->
                    val reserve = reserves.find { it.program.id == item.program.id }

                    EpgSearchListItem(
                        resultItem = item,
                        reserveItem = reserve,
                        onClick = { onProgramClick(item.program) },
                        modifier = Modifier.then(
                            if (index == 0) Modifier
                                .focusRequester(firstItemFocusRequester)
                                // ★追加: リスト先頭のアイテムから上を押したら確実に戻るボタンへ
                                .focusProperties { up = backButtonFocusRequester }
                            else Modifier
                        )
                    )
                }
            }
        }
    }
}