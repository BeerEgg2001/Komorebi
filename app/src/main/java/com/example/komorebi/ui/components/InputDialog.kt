package com.example.komorebi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // --- ダイアログ自体の色設定 ---
        containerColor = Color(0xFF1E1E1E), // 深いグレー
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.8f),

        title = {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            Column {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true, // 改行を禁止して1行にする
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done // キーボードの右下を「完了」にする
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onConfirm(text) } // キーボードの「完了」を押したときも決定処理を走らせる
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2D2D2D),
                        unfocusedContainerColor = Color(0xFF252525),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                // --- 決定ボタンをより見やすく ---
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFFE0E0E0), // 通常時は明るいグレー
                    contentColor = Color.Black,          // 文字は黒
                    focusedContainerColor = Color.White, // フォーカス時は真っ白
                    focusedContentColor = Color.Black
                )
            ) {
                Text("決定")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    contentColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.1f)
                )
            ) {
                Text("キャンセル")
            }
        }
    )
}