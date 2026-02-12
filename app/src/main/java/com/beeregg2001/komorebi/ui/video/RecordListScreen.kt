package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.*
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListScreen(
    recentRecordings: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchBarVisible by remember { mutableStateOf(false) }

    val filteredRecordings = remember(searchQuery, recentRecordings) {
        if (searchQuery.isBlank()) recentRecordings
        else recentRecordings.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val gridState = rememberTvLazyGridState()
    val searchFocusRequester = remember { FocusRequester() }

    // ★内部でリクエストを生成
    val gridFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchBarVisible) { if (isSearchBarVisible) searchFocusRequester.requestFocus() }

    // ★重要: 画面が表示されたら自分自身（グリッド）にフォーカスを当てる
    // Crossfadeを使うことで、前の画面が消える前にこの処理が走るため、タブに抜けなくなります
    LaunchedEffect(Unit) {
        delay(50)
        if (!isSearchBarVisible) {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(horizontal = 40.dp, vertical = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "戻る", tint = Color.White) }
            Spacer(Modifier.width(16.dp))
            Text("録画一覧", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { isSearchBarVisible = true }) { Icon(Icons.Default.Search, "検索", tint = Color.White) }
        }

        Spacer(Modifier.height(24.dp))

        TvLazyVerticalGrid(
            state = gridState,
            columns = TvGridCells.Fixed(4),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(gridFocusRequester) // 内部Requesterを使用
        ) {
            items(filteredRecordings) { program ->
                var isFocused by remember { mutableStateOf(false) }

                RecordedCard(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort, onClick = { onProgramClick(program) },
                    modifier = Modifier
                        .aspectRatio(16f / 9f)
                        .onFocusChanged { isFocused = it.isFocused }
                        .then(if (isFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                )
            }
        }
    }
}