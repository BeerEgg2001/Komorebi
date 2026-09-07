@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import kotlinx.coroutines.delay

@Composable
fun AiTextInputDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val colors = KomorebiTheme.colors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LaunchedEffect(Unit) {
            delay(300)
            runCatching { focusRequester.requestFocus() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = colors.surface),
                modifier = Modifier.width(500.dp)
            ) {
                Column(modifier = Modifier.padding(32.dp)) {
                    Text(
                        text = "AIに質問・指示を入力",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .background(
                                colors.textPrimary.copy(alpha = 0.1f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.textPrimary,
                            fontSize = 18.sp
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                            onSubmit(text)
                        }),
                        cursorBrush = Brush.verticalGradient(listOf(colors.accent, colors.accent))
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                contentColor = colors.textPrimary
                            )
                        ) { Text("キャンセル") }
                        Button(
                            onClick = { onSubmit(text) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.colors(
                                containerColor = colors.accent,
                                contentColor = if (colors.isDark) Color.Black else Color.White
                            )
                        ) { Text("送信") }
                    }
                }
            }
        }
    }
}

@Composable
fun RobustUpdateDialog(
    versionName: String,
    releaseNotes: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val confirmRequester = remember { FocusRequester() }
    var isDialogFocused by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { delay(300); runCatching { confirmRequester.requestFocus() } }
    LaunchedEffect(isDialogFocused) {
        if (!isDialogFocused) {
            delay(150); runCatching { confirmRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .zIndex(1000f)
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onFocusChanged { isDialogFocused = it.hasFocus },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(500.dp),
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "アップデートのお知らせ",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "バージョン $versionName が利用可能です。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .background(
                            colors.textPrimary.copy(alpha = 0.05f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        scale = ButtonDefaults.scale(focusedScale = 1.05f),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) { Text("後で", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(confirmRequester),
                        scale = ButtonDefaults.scale(focusedScale = 1.05f),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White,
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) { Text("ダウンロード", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun SyncProgressIndicator(recordViewModel: RecordViewModel, modifier: Modifier = Modifier) {
    val syncProgress by recordViewModel.syncProgress.collectAsState()
    val colors = KomorebiTheme.colors
    val progress =
        if (syncProgress.total > 0) syncProgress.current.toFloat() / syncProgress.total.toFloat() else 0f

    AnimatedVisibility(
        visible = syncProgress.isSyncing,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(
                containerColor = colors.surface.copy(alpha = 0.9f),
                contentColor = colors.textPrimary
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(min = 200.dp, max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = syncProgress.progressText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (syncProgress.total > 0) LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.textPrimary.copy(alpha = 0.2f)
                )
                else LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.textPrimary.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
fun SyncErrorDialog(errorMessage: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("SyncError") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "同期エラー",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("閉じる") }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    ) { Text("再実行") }
                }
            }
        }
    }
}

@Composable
fun UpdateProgressBanner(updateState: UpdateState, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    val isReady = updateState is UpdateState.ReadyToInstall
    val progress =
        if (updateState is UpdateState.Downloading) updateState.progressPercentage else 100

    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(8.dp),
        colors = SurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.95f),
            contentColor = colors.textPrimary
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Download,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isReady) "インストーラ起動中..." else "アップデートをダウンロード中",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isReady) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            colors.textSecondary.copy(alpha = 0.2f),
                            RoundedCornerShape(2.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress / 100f)
                            .fillMaxHeight()
                            .background(colors.accent, RoundedCornerShape(2.dp))
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$progress %",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}