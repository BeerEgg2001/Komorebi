package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.components.ReserveCard
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

private const val TAG = "ReserveListScreen"

@OptIn(ExperimentalTvMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ReserveListScreen(
    onBack: () -> Unit,
    onProgramClick: (ReserveItem) -> Unit,
    konomiIp: String,
    konomiPort: String,
    contentFirstItemRequester: FocusRequester? = null,
    viewModel: ReserveViewModel = hiltViewModel()
) {
    val reserves by viewModel.reserves.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val colors = KomorebiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            // ★修正: 親のグラデーションを通すため、ここにあった .background() を削除しました
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .onKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                    if (event.type == KeyEventType.KeyUp) {
                        onBack()
                        return@onKeyEvent true
                    }
                }
                false
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 20.dp)
        ) {
            Text(
                text = "放送が近い録画予約",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else if (reserves.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .then(if (contentFirstItemRequester != null) Modifier.focusRequester(contentFirstItemRequester) else Modifier)
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "現在、予約されている番組はありません。",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            TvLazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .then(if (contentFirstItemRequester != null) Modifier.focusRequester(contentFirstItemRequester) else Modifier)
            ) {
                items(reserves) { program ->
                    ReserveCard(
                        item = program, konomiIp = konomiIp, konomiPort = konomiPort,
                        onClick = { onProgramClick(program) }
                    )
                }
            }
        }
    }
}