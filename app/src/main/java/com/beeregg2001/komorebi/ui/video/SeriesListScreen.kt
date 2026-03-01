//@file:OptIn(ExperimentalTvMaterial3Api::class)
//
//package com.beeregg2001.komorebi.ui.video
//
//import androidx.compose.foundation.BorderStroke
//import androidx.compose.foundation.background
//import androidx.compose.foundation.basicMarquee
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.material.icons.Icons
//import androidx.compose.material.icons.filled.ArrowBack
//import androidx.compose.material3.CircularProgressIndicator
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.focus.FocusRequester
//import androidx.compose.ui.focus.focusRequester
//import androidx.compose.ui.focus.onFocusChanged
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.hilt.navigation.compose.hiltViewModel
//import androidx.tv.foundation.lazy.grid.*
//import androidx.tv.foundation.lazy.list.TvLazyRow
//import androidx.tv.foundation.lazy.list.itemsIndexed
//import androidx.tv.material3.*
//import com.beeregg2001.komorebi.common.safeRequestFocus
//import com.beeregg2001.komorebi.data.util.EpgUtils
//import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
//import com.beeregg2001.komorebi.viewmodel.RecordViewModel
//import kotlinx.coroutines.delay
//
//@Composable
//fun SeriesListScreen(
//    viewModel: RecordViewModel = hiltViewModel(), // ★ViewModelを直接受け取る
//    onSeriesClick: (String, String) -> Unit,
//    onBack: () -> Unit
//) {
//    // ★親ではなく、ここでデータを監視する
//    val groupedSeries by viewModel.groupedSeries.collectAsState()
//    val isLoading by viewModel.isSeriesLoading.collectAsState()
//
//    var selectedGenre by remember(groupedSeries) { mutableStateOf(groupedSeries.keys.firstOrNull()) }
//    val colors = KomorebiTheme.colors
//    val backButtonRequester = remember { FocusRequester() }
//    val firstGenreRequester = remember { FocusRequester() }
//    val firstItemRequester = remember { FocusRequester() }
//
//    var initialFocusSet by remember { mutableStateOf(false) }
//
//    // ★画面表示時にシリーズのインデックス構築をリクエスト
//    LaunchedEffect(Unit) {
//        viewModel.buildSeriesIndex()
//    }
//
//    LaunchedEffect(isLoading, groupedSeries) {
//        if (!isLoading && !initialFocusSet) {
//            delay(100)
//            if (groupedSeries.isNotEmpty()) firstGenreRequester.safeRequestFocus("SeriesList_FirstGenre")
//            else backButtonRequester.safeRequestFocus("SeriesList_Back")
//            initialFocusSet = true
//        }
//    }
//
//    // ★修正: 上・左・右のみにパディングを設定し、下方向の制限を解除
//    Column(Modifier
//        .fillMaxSize()
//        .padding(top = 32.dp, start = 48.dp, end = 48.dp)) {
//        Row(verticalAlignment = Alignment.CenterVertically) {
//            IconButton(
//                onClick = onBack,
//                modifier = Modifier.focusRequester(backButtonRequester),
//                colors = IconButtonDefaults.colors(
//                    focusedContainerColor = colors.textPrimary,
//                    focusedContentColor = if (colors.isDark) Color.Black else Color.White,
//                    contentColor = colors.textPrimary
//                )
//            ) {
//                Icon(Icons.Default.ArrowBack, "戻る")
//            }
//            // ★修正: headlineLarge に変更し、太字を設定して録画一覧とサイズ感を合わせる
//            Text(
//                text = "シリーズ(作品名)から探す",
//                style = MaterialTheme.typography.headlineSmall,
//                fontSize = 20.sp,
//                fontWeight = FontWeight.Bold,
//                color = colors.textPrimary,
//                modifier = Modifier.padding(start = 16.dp)
//            )
//        }
//
//        Spacer(Modifier.height(24.dp))
//
//        if (isLoading && groupedSeries.isEmpty()) {
//            Box(Modifier
//                .weight(1f)
//                .fillMaxWidth(), contentAlignment = Alignment.Center) {
//                CircularProgressIndicator(color = colors.textPrimary)
//            }
//        } else {
//            TvLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
//                itemsIndexed(groupedSeries.keys.toList()) { index, genre ->
//                    val genreColor = EpgUtils.getGenreColor(genre)
//                    FilterChip(
//                        selected = selectedGenre == genre,
//                        onClick = { selectedGenre = genre },
//                        modifier = Modifier.then(
//                            if (index == 0) Modifier.focusRequester(
//                                firstGenreRequester
//                            ) else Modifier
//                        ),
//                        scale = FilterChipDefaults.scale(focusedScale = 1.0f),
//                        colors = FilterChipDefaults.colors(
//                            containerColor = colors.textPrimary.copy(alpha = 0.05f),
//                            focusedContainerColor = genreColor.copy(alpha = 0.4f),
//                            selectedContainerColor = genreColor.copy(alpha = 0.2f),
//                            focusedSelectedContainerColor = genreColor.copy(alpha = 0.6f)
//                        ),
//                        border = FilterChipDefaults.border(
//                            border = Border(
//                                BorderStroke(
//                                    1.dp,
//                                    colors.textPrimary.copy(alpha = 0.2f)
//                                )
//                            ),
//                            focusedBorder = Border(BorderStroke(2.dp, colors.accent)),
//                            selectedBorder = Border(BorderStroke(1.dp, genreColor)),
//                            focusedSelectedBorder = Border(BorderStroke(2.dp, colors.textPrimary))
//                        )
//                    ) { Text(genre, color = colors.textPrimary) }
//                }
//            }
//
//            Spacer(Modifier.height(24.dp))
//
//            // ★修正: Modifier.weight(1f) を追加して画面の残り高さを全て使い切り、
//            // contentPadding でスクロール終端の余白を確保する。
//            TvLazyVerticalGrid(
//                columns = TvGridCells.Fixed(3),
//                horizontalArrangement = Arrangement.spacedBy(16.dp),
//                verticalArrangement = Arrangement.spacedBy(16.dp),
//                contentPadding = PaddingValues(bottom = 48.dp),
//                modifier = Modifier.weight(1f)
//            ) {
//                val seriesList = groupedSeries[selectedGenre] ?: emptyList()
//                itemsIndexed(seriesList) { index, pair ->
//                    var isFocused by remember { mutableStateOf(false) }
//                    Surface(
//                        onClick = { onSeriesClick(pair.second, pair.first) },
//                        modifier = Modifier
//                            .then(if (index == 0) Modifier.focusRequester(firstItemRequester) else Modifier)
//                            .onFocusChanged { isFocused = it.isFocused },
//                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
//                        colors = ClickableSurfaceDefaults.colors(
//                            containerColor = colors.textPrimary.copy(alpha = 0.05f),
//                            focusedContainerColor = colors.textPrimary.copy(alpha = 0.15f),
//                            contentColor = colors.textPrimary,
//                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
//                        ),
//                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
//                        border = ClickableSurfaceDefaults.border(
//                            focusedBorder = Border(
//                                BorderStroke(
//                                    2.dp,
//                                    colors.accent
//                                )
//                            )
//                        )
//                    ) {
//                        Text(
//                            text = pair.first,
//                            modifier = Modifier
//                                .padding(16.dp)
//                                .then(if (isFocused) Modifier.basicMarquee() else Modifier),
//                            maxLines = 1,
//                            color = colors.textPrimary
//                        )
//                    }
//                }
//            }
//        }
//    }
//}