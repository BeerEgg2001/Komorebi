package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme // ★追加

@Composable
fun LoadingScreen() {
    val colors = KomorebiTheme.colors // ★追加
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.textPrimary) // ★修正
            Spacer(modifier = Modifier.height(20.dp))
            Text("Loading...", color = colors.textPrimary) // ★修正
        }
    }
}