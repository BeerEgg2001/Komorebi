package com.example.komorebi.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.example.komorebi.ui.components.InputDialog
import com.example.komorebi.data.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }

    val konomiIp by repository.konomiIp.collectAsState(initial = "192.168.100.60")
    val konomiPort by repository.konomiPort.collectAsState(initial = "40772")
    val mirakurunIp by repository.mirakurunIp.collectAsState(initial = "192.168.100.60")
    val mirakurunPort by repository.mirakurunPort.collectAsState(initial = "40772")

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var editingItem by remember { mutableStateOf<Pair<String, String>?>(null) }
    val categories = listOf("接続設定", "表示設定", "アプリ情報")

    val sideBarFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    LaunchedEffect(editingItem) {
        // ダイアログが閉じた（editingItem が null になった）とき、
        // かつ、まだどこにもフォーカスがない場合にサイドバーにフォーカスを戻す
        if (editingItem == null) {
            kotlinx.coroutines.delay(100)
            sideBarFocusRequester.requestFocus()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(48.dp)
    ) {
        // --- 左側：詳細コンテンツエリア ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 48.dp)
        ) {
            when (selectedCategoryIndex) {
                0 -> ConnectionSettingsContent(
                    kIp = konomiIp,
                    kPort = konomiPort,
                    mIp = mirakurunIp,
                    mPort = mirakurunPort,
                    onEditRequest = { title, currentVal -> editingItem = title to currentVal },
                    firstItemModifier = Modifier.focusRequester(contentFocusRequester)
                )
                1 -> Text("表示設定は準備中です", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
                2 -> Text("DTV Client v1.0.0", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // --- 右側：サイドバーメニュー ---
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "設定",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 24.dp, end = 16.dp)
            )

            categories.forEachIndexed { index, title ->
                CategoryItem(
                    title = title,
                    isSelected = selectedCategoryIndex == index,
                    onFocused = { selectedCategoryIndex = index },
                    onClick = { contentFocusRequester.requestFocus() },
                    modifier = if (index == 0) Modifier.focusRequester(sideBarFocusRequester) else Modifier
                )
            }
        }
    }

    editingItem?.let { (title, value) ->
        InputDialog(
            title = title,
            initialValue = value,
            onDismiss = { editingItem = null },
            onConfirm = { newValue ->
                scope.launch {
                    val key = when (title) {
                        "KonomiTV アドレス" -> SettingsRepository.KONOMI_IP // ここを「アドレス」に修正
                        "KonomiTV ポート番号" -> SettingsRepository.KONOMI_PORT
                        "Mirakurun アドレス" -> SettingsRepository.MIRAKURUN_IP // ここを「アドレス」に修正
                        "Mirakurun ポート番号" -> SettingsRepository.MIRAKURUN_PORT
                        else -> null
                    }

                    if (key != null) {
                        repository.saveString(key, newValue)
                        println("Successfully saved: $title as $newValue") // 成功ログ
                    } else {
                        println("Failed to save: Title '$title' did not match any key") // 失敗ログ
                    }
                }
                editingItem = null
            }
        )
    }
}

@Composable
fun ConnectionSettingsContent(
    kIp: String, kPort: String, mIp: String, mPort: String,
    onEditRequest: (String, String) -> Unit,
    firstItemModifier: Modifier = Modifier
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("KonomiTV 接続設定", color = Color.Gray, style = MaterialTheme.typography.labelLarge)
        SettingItem(
            title = "KonomiTV アドレス",
            value = kIp,
            modifier = firstItemModifier, // Modifier を正しい順序で渡す
            onClick = { onEditRequest("KonomiTV アドレス", kIp) }
        )
        SettingItem(
            title = "KonomiTV ポート番号",
            value = kPort,
            onClick = { onEditRequest("KonomiTV ポート番号", kPort) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Mirakurun 接続設定", color = Color.Gray, style = MaterialTheme.typography.labelLarge)
        SettingItem(
            title = "Mirakurun アドレス",
            value = mIp,
            onClick = { onEditRequest("Mirakurun アドレス", mIp) }
        )
        SettingItem(
            title = "Mirakurun ポート番号",
            value = mPort,
            onClick = { onEditRequest("Mirakurun ポート番号", mPort) }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryItem(
    title: String,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        selected = isSelected,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused) onFocused()
            },
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White.copy(alpha = 0.2f)
        ),
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingItem(
    title: String,
    value: String,
    modifier: Modifier = Modifier, // modifier を onClick の前に配置（標準的な順序）
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
            focusedContainerColor = Color.White.copy(alpha = 0.15f)
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
    }
}