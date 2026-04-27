@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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

    data class MultiSelection(
        val title: String,
        val options: List<Pair<String, String>>,
        val currentSelections: Set<String>,
        val onConfirm: (Set<String>) -> Unit
    ) : SettingDialogState()

    data class ConfirmClear(val title: String, val message: String, val onConfirm: () -> Unit) :
        SettingDialogState()

    object Licenses : SettingDialogState()
    object GeminiSetup : SettingDialogState()
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
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val focusedTextColor = MaterialTheme.colorScheme.onPrimary
    val focusedBgColor = MaterialTheme.colorScheme.primary

    Surface(
        selected = isSelected,
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enabled }
            .onFocusChanged { if (it.isFocused && enabled) onFocused() },
        interactionSource = interactionSource, // ★ 確実なフォーカス監視
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = colors.textPrimary.copy(0.1f),
            focusedContainerColor = focusedBgColor, // ★ アクセント背景に統一
            contentColor = if (enabled) colors.textSecondary else colors.textSecondary.copy(alpha = 0.3f),
            selectedContentColor = colors.textPrimary,
            focusedContentColor = focusedTextColor // ★ 確実な文字色
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
                tint = if (isFocused) focusedTextColor else if (isSelected) colors.textPrimary else colors.textSecondary.copy(
                    alpha = if (enabled) 1f else 0.3f
                )
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isFocused) focusedTextColor else Color.Unspecified
            )
            Spacer(Modifier.weight(1f))
            if (isSelected) Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(
                        if (isFocused) focusedTextColor else colors.accent,
                        MaterialTheme.shapes.small
                    )
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

    // ★ 確実なフォーカス状態の取得
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val focusedTextColor = MaterialTheme.colorScheme.onPrimary
    val focusedBgColor = MaterialTheme.colorScheme.primary

    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enabled },
        interactionSource = interactionSource, // ★ ここで結びつける
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = if (enabled) 0.05f else 0.02f),
            focusedContainerColor = focusedBgColor,
            contentColor = colors.textPrimary.copy(alpha = if (enabled) 1f else 0.4f),
            focusedContentColor = focusedTextColor
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
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isFocused && enabled) focusedTextColor else colors.textPrimary.copy(
                        alpha = if (enabled) 0.7f else 0.3f
                    )
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (isFocused && enabled) focusedTextColor else Color.Unspecified
            )
            Text(
                text = value,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused && enabled) focusedTextColor else colors.textSecondary.copy(
                    alpha = if (enabled) 1f else 0.5f
                )
            )
        }
    }
}

@Composable
fun BatchInputDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    val colors = KomorebiTheme.colors
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { delay(150); focusRequester.safeRequestFocus() }

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
                    ) { Text("キャンセル") }
                    Button(
                        onClick = {
                            if (name.isNotBlank() && path.isNotBlank()) onConfirm(
                                name,
                                path
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank() && path.isNotBlank()
                    ) { Text("追加") }
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
                value = value, onValueChange = onValueChange,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) keyboardController?.show() },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(
                            text = placeholder,
                            color = colors.textSecondary.copy(0.5f),
                            fontSize = 16.sp
                        )
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
        delay(50); try {
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
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && it.type == KeyEventType.KeyUp) {
                    isClosing = true; onDismiss(); return@onKeyEvent true
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
                        val focusModifier =
                            if (isSelected || (current.isEmpty() && index == 0)) Modifier.focusRequester(
                                initialFocusRequester
                            ) else Modifier
                        SelectionDialogItem(
                            label = label, isSelected = isSelected,
                            onClick = { isClosing = true; onSelect(value) },
                            modifier = focusModifier
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { isClosing = true; onDismiss() },
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
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val focusedTextColor = MaterialTheme.colorScheme.onPrimary
    val focusedBgColor = MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.textPrimary.copy(0.1f) else Color.Transparent,
            focusedContainerColor = focusedBgColor,
            contentColor = colors.textPrimary,
            focusedContentColor = focusedTextColor // ★ ハードコードを完全排除
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                color = if (isFocused) focusedTextColor else Color.Unspecified
            )
            if (isSelected) {
                Icon(
                    Icons.Default.Check, null, modifier = Modifier.size(20.dp),
                    tint = if (isFocused) focusedTextColor else colors.textPrimary
                )
            }
        }
    }
}

@Composable
fun MultiSelectionDialog(
    title: String,
    options: List<Pair<String, String>>,
    currentSelections: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val colors = KomorebiTheme.colors
    var selections by remember { mutableStateOf(currentSelections) }
    val listState = rememberLazyListState()

    var isClosing by remember { mutableStateOf(false) }
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50); try {
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
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && it.type == KeyEventType.KeyUp) {
                    isClosing = true; onDismiss(); return@onKeyEvent true
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(460.dp)
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
                    modifier = Modifier.heightIn(max = 350.dp)
                ) {
                    itemsIndexed(options) { index, (label, value) ->
                        val isSelected = selections.contains(value)
                        val focusModifier =
                            if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier
                        MultiSelectionDialogItem(
                            label = label, isSelected = isSelected,
                            onClick = {
                                selections =
                                    if (isSelected) selections - value else selections + value
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
                        onClick = { isClosing = true; onDismiss() },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("キャンセル") }
                    Button(
                        onClick = { isClosing = true; onConfirm(selections) },
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary // ★ 修正: 確定ボタンのテキスト色
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("確定", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun MultiSelectionDialogItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val focusedTextColor = MaterialTheme.colorScheme.onPrimary
    val focusedBgColor = MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.textPrimary.copy(0.1f) else Color.Transparent,
            focusedContainerColor = focusedBgColor,
            contentColor = colors.textPrimary,
            focusedContentColor = focusedTextColor // ★ 修正
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
                contentDescription = null, modifier = Modifier.size(24.dp),
                tint = if (isFocused) focusedTextColor else if (isSelected) colors.accent else colors.textSecondary
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                color = if (isFocused) focusedTextColor else Color.Unspecified
            )
        }
    }
}

@Composable
fun ConfirmClearDialog(
    title: String,
    message: String,
    confirmButtonText: String = AppStrings.BUTTON_DELETE,
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
                    ) { Text(confirmButtonText) }
                }
            }
        }
    }
}

@Composable
fun GeminiSetupDialog(
    currentKey: String,
    serverIp: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onDismiss: () -> Unit,
    onManualInputClick: () -> Unit,
    onDeleteKey: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isClosing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) { onStartServer(); onDispose { onStopServer() } }
    LaunchedEffect(Unit) { delay(150); focusRequester.safeRequestFocus() }

    val serverUrl = "http://$serverIp:8081"
    val qrBitmap = rememberQrBitmap(content = serverUrl, size = 300)
    val isKeySet = currentKey.isNotBlank() && currentKey.startsWith("AIza")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.85f))
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .focusGroup()
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && it.type == KeyEventType.KeyUp) {
                    isClosing = true; onDismiss(); return@onKeyEvent true
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(680.dp)
        ) {
            Column(modifier = Modifier.padding(40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        null,
                        tint = colors.accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "AIコンシェルジュ連携 (Gemini)",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(32.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "連携ステータス",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textSecondary
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isKeySet) Icons.Default.CheckCircle else Icons.Default.Warning,
                                null,
                                tint = if (isKeySet) Color(0xFF34A853) else Color(0xFFFFB300),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isKeySet) "設定済み (キー受信完了)" else "未設定",
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(32.dp))
                        Text(
                            "設定手順",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.accent,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "1. スマホのカメラで右のQRコードを読み取ります。",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "2. スマホの画面に従い、GoogleからAPIキーを取得して送信してください。",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "3. 送信後、上のステータスが「設定済み」になれば完了です！",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp
                        )
                    }
                    Spacer(Modifier.width(32.dp))
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) Image(
                            bitmap = qrBitmap,
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        ) else CircularProgressIndicator(color = colors.accent)
                    }
                }
                Spacer(Modifier.height(40.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { isClosing = true; onDismiss() },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(0.1f),
                            contentColor = colors.textPrimary
                        ),
                        modifier = Modifier
                            .width(180.dp)
                            .focusRequester(focusRequester)
                    ) { Text(if (isKeySet) "閉じる" else "キャンセル") }
                    Spacer(Modifier.width(24.dp))
                    if (isKeySet) {
                        Button(
                            onClick = { isClosing = true; onDeleteKey() },
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFFD32F2F),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.width(180.dp)
                        ) { Text("連携を解除") }
                    } else {
                        Button(
                            onClick = { isClosing = true; onManualInputClick() },
                            colors = ButtonDefaults.colors(
                                containerColor = colors.textPrimary.copy(0.1f),
                                contentColor = colors.textPrimary
                            ),
                            modifier = Modifier.width(180.dp)
                        ) { Text("手動で入力") }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberQrBitmap(content: String, size: Int): androidx.compose.ui.graphics.ImageBitmap? {
    return remember(content, size) {
        try {
            val bitMatrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.MARGIN to 1)
            )
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size) bmp.setPixel(
                x,
                y,
                if (bitMatrix.get(
                        x,
                        y
                    )
                ) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val focusedTextColor = MaterialTheme.colorScheme.onPrimary
    val focusedBgColor = MaterialTheme.colorScheme.primary

    Surface(
        onClick = { onCheckedChange(!checked) },
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = focusedBgColor,
            focusedContentColor = focusedTextColor
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon, contentDescription = null, modifier = Modifier.size(32.dp),
                tint = if (isFocused) focusedTextColor else colors.textPrimary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isFocused) focusedTextColor else colors.textPrimary
                )
                if (description.isNotEmpty()) {
                    Text(
                        text = description, style = MaterialTheme.typography.bodySmall,
                        color = if (isFocused) focusedTextColor.copy(alpha = 0.8f) else colors.textSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null, modifier = Modifier.size(32.dp),
                tint = if (isFocused) focusedTextColor else if (checked) colors.accent else colors.textSecondary
            )
        }
    }
}