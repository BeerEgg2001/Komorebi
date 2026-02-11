package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    // フォーカス制御用
    val textFieldFocusRequester = remember { FocusRequester() }
    val saveButtonFocusRequester = remember { FocusRequester() }

    // テキストフィールドのフォーカス状態監視
    val textFieldInteractionSource = remember { MutableInteractionSource() }
    val isTextFieldFocused by textFieldInteractionSource.collectIsFocusedAsState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            // ダイアログ背景: 暗いグレー
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.width(400.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(24.dp))

                // --- テキスト入力フィールド (モノトーンスタイル) ---
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(textFieldFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        // フォーカス時: 白背景・黒文字
                        focusedContainerColor = Color.White,
                        focusedTextColor = Color.Black,
                        focusedBorderColor = Color.White,
                        cursorColor = Color.Black,

                        // 非フォーカス時: 暗い背景・白文字
                        unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedTextColor = Color.White,
                        unfocusedBorderColor = Color.Gray
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    interactionSource = textFieldInteractionSource,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // 完了キーで保存ボタンへ移動
                    keyboardActions = KeyboardActions(
                        onDone = {
                            saveButtonFocusRequester.requestFocus()
                        }
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                // --- アクションボタン ---
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // キャンセルボタン
                    MonochromeButton(
                        text = "キャンセル",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )

                    // 保存ボタン
                    MonochromeButton(
                        text = "保存",
                        onClick = { onConfirm(text) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(saveButtonFocusRequester),
                        isPrimary = true
                    )
                }
            }
        }
    }

    // 初期フォーカス
    LaunchedEffect(Unit) {
        textFieldFocusRequester.requestFocus()
    }
}

/**
 * モノトーンスタイルのボタンコンポーネント
 * フォーカス時に白背景・黒文字に反転します
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MonochromeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Button(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                // ★修正: Buttonパラメータのborderを使わず、Modifierで条件付き枠線を描画する
                // Primary以外(キャンセル)で、かつフォーカスされていない時だけ薄い枠線を表示
                if (!isPrimary && !isFocused) {
                    Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.2f),
                        shape = shape
                    )
                } else {
                    Modifier
                }
            ),
        shape = ButtonDefaults.shape(shape = shape),
        colors = ButtonDefaults.colors(
            // フォーカス時: 白背景・黒文字
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,

            // 通常時:
            // Primary(保存) -> 少し明るいグレー
            // Secondary(キャンセル) -> 透明または薄いグレー
            containerColor = if (isPrimary) Color(0xFF333333) else Color.Transparent,
            contentColor = if (isPrimary) Color.White else Color.Gray
        ),
        // scale設定 (フォーカス時に少し大きくなる)
        scale = ButtonDefaults.scale(focusedScale = 1.05f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}