package com.beeregg2001.komorebi.ui.video

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordListScreen(
    recordings: List<RecordedProgram>,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram) -> Unit,
    onBack: () -> Unit,
    topNavFocusRequester: FocusRequester // タブへのフォーカス移動用
) {
    val gridFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        gridFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(start = 58.dp, top = 24.dp, end = 48.dp, bottom = 24.dp)
    ) {
        // ヘッダー部分
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.focusProperties {
                    up = topNavFocusRequester // ここでタブへの移動を指定
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "戻る",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "録画一覧",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${recordings.size} 件",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        // グリッドリスト
        TvLazyVerticalGrid(
            columns = TvGridCells.Fixed(4),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(recordings) { program ->
                RecordedCard(
                    program = program,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onClick = { onProgramClick(program) },
                    modifier = Modifier
                        .aspectRatio(16f / 9f)
                        .then(if (program == recordings.firstOrNull()) Modifier.focusRequester(gridFocusRequester) else Modifier)
                        .focusProperties {
                            // グリッドの最上列から上キーを押したときもタブへ移動させたい場合はここで指定
                            // ただし、通常はヘッダー（戻るボタン）を経由するのが自然なため、基本はそのままでOK
                        }
                )
            }
        }
    }
}