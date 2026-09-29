package com.beeregg2001.komorebi.ui.components

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
import com.beeregg2001.komorebi.ui.theme.ProvideUiScale

@Composable
fun ExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1B1F),
        titleContentColor = Color.White,
        // AlertDialog は内部で Dialog(別ウィンドウ)を使うため LocalDensity の上書きが
        // 引き継がれない。スロットごとに ProvideUiScale で「UI の大きさ」を再適用する。
        confirmButton = {
            ProvideUiScale {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.focusRequester(focusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF333333),
                        focusedContainerColor = Color.White
                    ),
                    scale = ButtonDefaults.scale(focusedScale = 1.1f)
                ) { Text("終了") }
            }
        },
        dismissButton = {
            ProvideUiScale {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF333333).copy(alpha = 0.1f),
                        focusedContainerColor = Color.White
                    ),
                    scale = ButtonDefaults.scale(focusedScale = 1.1f)
                ) { Text("キャンセル") }
            }
        },
        title = {
            ProvideUiScale {
                Text(
                    "アプリを終了しますか？",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Left,
                    color = Color.White
                )
            }
        }
    )
}