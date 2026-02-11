package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.grid.*
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard

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
    val gridFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchBarVisible) { if (isSearchBarVisible) searchFocusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(horizontal = 40.dp, vertical = 20.dp)) {
        // ヘッダー (省略... 既存通り)
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
            modifier = Modifier.fillMaxSize().focusRequester(gridFocusRequester)
        ) {
            items(filteredRecordings) { program ->
                var isFocused by remember { mutableStateOf(false) } // ★追加

                RecordedCard(
                    program = program, konomiIp = konomiIp, konomiPort = konomiPort, onClick = { onProgramClick(program) },
                    modifier = Modifier
                        .aspectRatio(16f / 9f)
                        .onFocusChanged { isFocused = it.isFocused } // ★追加
                        .then(if (isFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier) // ★追加: 白枠
                )
            }
        }
    }
}