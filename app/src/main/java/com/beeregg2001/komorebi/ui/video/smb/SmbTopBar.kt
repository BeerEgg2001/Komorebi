package com.beeregg2001.komorebi.ui.video.smb

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbTopBar(
    path: String,
    isListView: Boolean,
    onViewToggle: () -> Unit,
    onBack: () -> Unit,
    focuses: com.beeregg2001.komorebi.ui.video.RecordListFocusRequesters,
    onFocusDown: () -> Unit // ★ 追加: 下への移動時にチケットを発行するためのコールバック
) {
    val colors = KomorebiTheme.colors
    val displayPath = path.replace("smb://", "").trimEnd('/')

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 20.dp)
            .focusProperties {
                // 上へのフォーカス移動は禁止
                up = FocusRequester.Cancel
            }
            // ★ 追加: TopBar 全体で下キーをキャッチしてチケットを発行させる
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onFocusDown()
                    return@onKeyEvent true
                }
                false
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .focusRequester(focuses.backButton)
                .focusProperties { up = FocusRequester.Cancel } // down は Row でキャッチさせる
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = displayPath.ifEmpty { "SMBサーバー" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onViewToggle,
            modifier = Modifier
                .focusRequester(focuses.viewToggleButton)
                .focusProperties { up = FocusRequester.Cancel }
        ) {
            Icon(
                imageVector = if (isListView) Icons.Default.GridView else Icons.Default.List,
                contentDescription = "Toggle View"
            )
        }
    }
}