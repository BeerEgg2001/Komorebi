package com.example.komorebi.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

@Composable
fun ExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1B1F),
        titleContentColor = Color.White,
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFFB3261E),
                    focusedContainerColor = Color(0xFFF2B8B5)
                ),
                scale = ButtonDefaults.scale(focusedScale = 1.1f)
            ) { Text("終了") }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.White
                ),
                scale = ButtonDefaults.scale(focusedScale = 1.1f)
            ) { Text("キャンセル") }
        },
        title = { Text("アプリを終了しますか？", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    )
}