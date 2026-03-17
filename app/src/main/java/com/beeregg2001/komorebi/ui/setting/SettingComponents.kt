@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox // ★追加
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank // ★追加
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

sealed class SettingDialogState {
    object None : SettingDialogState()
    data class Input(val title: String, val initialValue: String, val onConfirm: (String) -> Unit) :
        SettingDialogState()

    data class BatchInput(val onConfirm: (String, String) -> Unit) : SettingDialogState()

    data class Selection(
        val title: String,
        val options: List<Pair<String, String>>,
        val current: String,
        val onSelect: (String) -> Unit
    ) : SettingDialogState()

    // ★追加: 複数選択可能なダイアログ（プロ野球球団など）用
    data class MultiSelection(
        val title: String,
        val options: List<Pair<String, String>>,
        val currentSelections: Set<String>,
        val onConfirm: (Set<String>) -> Unit
    ) : SettingDialogState()

    data class ConfirmClear(val title: String, val message: String, val onConfirm: () -> Unit) :
        SettingDialogState()

    object Licenses : SettingDialogState()
}

data class Category(val name: String, val icon: ImageVector)

fun getThemeFromModeAndSeason(isDark: Boolean, season: String): String {
    return when (season) {
        "DEFAULT" -> if (isDark) "MONOTONE" else "HIGHTONE"
        "SPRING" -> if (isDark) "SPRING" else "SPRING_LIGHT"
        "SUMMER" -> if (isDark) "SUMMER" else "SUMMER_LIGHT"
        "AUTUMN" -> if (isDark) "AUTUMN" else "AUTUMN_LIGHT"
        "WINTER" -> if (isDark) "WINTER_DARK" else "WINTER_LIGHT"
        else -> if (isDark) "MONOTONE" else "HIGHTONE"
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = colors.textSecondary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        content()
    }
}

@Composable
fun CategoryItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        selected = isSelected,
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enabled }
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused && enabled) onFocused() },
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = colors.textPrimary.copy(0.1f),
            focusedContainerColor = colors.textPrimary.copy(0.2f),
            contentColor = if (enabled) colors.textSecondary else colors.textSecondary.copy(alpha = 0.3f),
            selectedContentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = SelectableSurfaceDefaults.scale(focusedScale = if (enabled) 1.05f else 1.0f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected || isFocused) colors.textPrimary else colors.textSecondary.copy(
                    alpha = if (enabled) 1f else 0.3f
                )
            )
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (isSelected) Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(colors.accent, MaterialTheme.shapes.small)
            )
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    value: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enabled }
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(if (enabled) 0.05f else 0.02f),
            focusedContainerColor = colors.textPrimary.copy(if (enabled) 0.9f else 0.02f),
            contentColor = colors.textPrimary.copy(alpha = if (enabled) 1f else 0.4f),
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (enabled) 1.02f else 1.0f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isFocused && enabled) Color.Transparent.copy(0.7f) else colors.textPrimary.copy(
                        if (enabled) 0.7f else 0.3f
                    )
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused && enabled) Color.Unspecified else colors.textSecondary.copy(
                    alpha = if (enabled) 1f else 0.5f
                )
            )
        }
    }
}

@Composable
fun BatchInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    val colors = KomorebiTheme.colors
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        focusRequester.safeRequestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.8f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(540.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    "バッチの登録",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "バッチ名称",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    DialogTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "例: エンコード実行",
                        focusRequester = focusRequester
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "フルパス",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    DialogTextField(
                        value = path,
                        onValueChange = { path = it },
                        placeholder = "/var/local/edcb/transcode.sh"
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(0.1f),
                            contentColor = colors.textPrimary
                        )
                    ) {
                        Text("キャンセル")
                    }
                    Button(
                        onClick = {
                            if (name.isNotBlank() && path.isNotBlank()) onConfirm(
                                name,
                                path
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank() && path.isNotBlank()
                    ) {
                        Text("追加")
                    }
                }
            }
        }
    }
}

@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val colors = KomorebiTheme.colors
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        onClick = { focusRequester.requestFocus() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.textPrimary.copy(0.05f)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(
                    2.dp,
                    colors.accent
                )
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            keyboardController?.show()
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = colors.textSecondary.copy(0.5f),
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
fun SelectionDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val colors = KomorebiTheme.colors
    val initialIndex = remember(options, current) {
        options.indexOfFirst { it.second == current }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    var isClosing by remember { mutableStateOf(false) }
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            initialFocusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.8f))
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .focusGroup()
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (it.type == KeyEventType.KeyUp) {
                        isClosing = true
                        onDismiss()
                    }
                    return@onKeyEvent true
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(400.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    itemsIndexed(options) { index, (label, value) ->
                        val isSelected = value == current
                        val focusModifier = if (isSelected || (current.isEmpty() && index == 0)) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else Modifier

                        SelectionDialogItem(
                            label = label,
                            isSelected = isSelected,
                            onClick = {
                                isClosing = true
                                onSelect(value)
                            },
                            modifier = focusModifier
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        isClosing = true
                        onDismiss()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = colors.textPrimary.copy(0.1f),
                        contentColor = colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("キャンセル") }
            }
        }
    }
}

@Composable
fun SelectionDialogItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.textPrimary.copy(0.1f) else Color.Transparent,
            focusedContainerColor = colors.accent,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isFocused) Color.Unspecified else colors.textPrimary
                )
            }
        }
    }
}

// ★追加: 複数選択（チェックボックス形式）用ダイアログコンポーネント
@Composable
fun MultiSelectionDialog(
    title: String,
    options: List<Pair<String, String>>,
    currentSelections: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val colors = KomorebiTheme.colors
    // ローカルで選択状態を保持（確定ボタンが押されるまでは本体のStateには反映させない）
    var selections by remember { mutableStateOf(currentSelections) }
    val listState = rememberLazyListState()

    var isClosing by remember { mutableStateOf(false) }
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            initialFocusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.8f))
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .focusGroup()
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (it.type == KeyEventType.KeyUp) {
                        isClosing = true
                        onDismiss()
                    }
                    return@onKeyEvent true
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(460.dp) // 球団名などが長くなることを考慮して少し広めに
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 350.dp) // テレビ画面でのスクロール領域を確保
                ) {
                    itemsIndexed(options) { index, (label, value) ->
                        val isSelected = selections.contains(value)
                        val focusModifier =
                            if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier

                        MultiSelectionDialogItem(
                            label = label,
                            isSelected = isSelected,
                            onClick = {
                                // 選択/解除のトグル動作
                                selections = if (isSelected) {
                                    selections - value
                                } else {
                                    selections + value
                                }
                            },
                            modifier = focusModifier
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            isClosing = true
                            onDismiss()
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("キャンセル") }

                    Button(
                        onClick = {
                            isClosing = true
                            onConfirm(selections)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.accent,
                            contentColor = if (colors.isDark) Color.Black else Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("確定", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ★追加: 複数選択ダイアログ用のチェックボックス付きアイテム
@Composable
fun MultiSelectionDialogItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.textPrimary.copy(0.1f) else Color.Transparent,
            focusedContainerColor = colors.accent,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isFocused) Color.Unspecified else if (isSelected) colors.accent else colors.textSecondary
            )
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ConfirmClearDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); focusRequester.safeRequestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    onDismiss(); true
                } else false
            },
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
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    message,
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
                    ) { Text(AppStrings.BUTTON_CANCEL) }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFFD32F2F),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    ) { Text(AppStrings.BUTTON_DELETE) }
                }
            }
        }
    }
}