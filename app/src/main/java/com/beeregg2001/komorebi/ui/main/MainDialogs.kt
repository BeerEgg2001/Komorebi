@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@Composable
fun DeleteConfirmationDialog(title: String, message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("DeleteConfirm") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = colors.surface), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onCancel, colors = ButtonDefaults.colors(containerColor = colors.textPrimary.copy(alpha = 0.1f), contentColor = colors.textPrimary), modifier = Modifier.weight(1f)) { Text("キャンセル") }
                    Button(onClick = onConfirm, colors = ButtonDefaults.colors(containerColor = Color(0xFFD32F2F), contentColor = Color.White), modifier = Modifier.weight(1f).focusRequester(focusRequester)) { Text("削除する") }
                }
            }
        }
    }
}

@Composable
fun InitialSetupDialog(onConfirm: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("InitialSetup") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = colors.surface), modifier = Modifier.width(400.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = AppStrings.SETUP_REQUIRED_TITLE, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = AppStrings.SETUP_REQUIRED_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onConfirm, colors = ButtonDefaults.colors(containerColor = colors.accent, contentColor = if(colors.isDark) Color.Black else Color.White), modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)) { Text(AppStrings.GO_TO_SETTINGS) }
            }
        }
    }
}

@Composable
fun ConnectionErrorDialog(onGoToSettings: () -> Unit, onExit: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("ConnectionError") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = colors.surface), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = AppStrings.CONNECTION_ERROR_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = AppStrings.CONNECTION_ERROR_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onExit, colors = ButtonDefaults.colors(containerColor = colors.textPrimary.copy(alpha = 0.1f), contentColor = colors.textPrimary), modifier = Modifier.weight(1f)) { Text(AppStrings.EXIT_APP) }
                    Button(onClick = onGoToSettings, colors = ButtonDefaults.colors(containerColor = colors.accent, contentColor = if(colors.isDark) Color.Black else Color.White), modifier = Modifier.weight(1f).focusRequester(focusRequester)) { Text(AppStrings.GO_TO_SETTINGS_SHORT) }
                }
            }
        }
    }
}

@Composable
fun IncompatibleOsDialog(onExit: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("IncompatibleOS") }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = colors.surface), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "非対応のOSバージョン", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "本アプリの実行には Android 8.0 (API 26) 以上が必要です。\nお使いの端末 (API ${Build.VERSION.SDK_INT}) は現在サポートされていません。", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onExit, colors = ButtonDefaults.colors(containerColor = colors.textPrimary.copy(alpha = 0.1f), contentColor = colors.textPrimary), modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)) { Text("アプリを終了する") }
            }
        }
    }
}