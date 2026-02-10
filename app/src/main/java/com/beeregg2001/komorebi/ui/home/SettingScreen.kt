package com.beeregg2001.komorebi.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.components.InputDialog
import com.beeregg2001.komorebi.data.SettingsRepository
import kotlinx.coroutines.delay
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

    val categories = listOf(
        Category("接続設定", Icons.Default.CastConnected),
        Category("表示設定", Icons.Default.Tv),
        Category("アプリ情報", Icons.Default.Info)
    )

    val sideBarFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    // ★ 修正ポイント：画面が表示されたらサイドバーの先頭にフォーカスを当てる
    LaunchedEffect(Unit) {
        delay(100) // 描画完了を少し待ってからフォーカス
        sideBarFocusRequester.requestFocus()
    }

    // ダイアログが閉じた後のフォーカス復帰
    LaunchedEffect(editingItem) {
        if (editingItem == null) {
            if (selectedCategoryIndex == 0) {
                contentFocusRequester.requestFocus()
            } else {
                sideBarFocusRequester.requestFocus()
            }
        }
    }

    // 全体の背景とレイアウト
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
    ) {
        // --- 左側：サイドバーメニュー ---
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(Color(0xFF0A0A0A))
                .padding(vertical = 48.dp, horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 32.dp, start = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "設定",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            categories.forEachIndexed { index, category ->
                CategoryItem(
                    title = category.name,
                    icon = category.icon,
                    isSelected = selectedCategoryIndex == index,
                    onFocused = { selectedCategoryIndex = index },
                    onClick = { contentFocusRequester.requestFocus() },
                    // 最初のアイテムにフォーカスリクエスト用のタグを付ける
                    modifier = if (index == 0) Modifier.focusRequester(sideBarFocusRequester) else Modifier
                )
            }

            // スペーサーで下部に押し下げる
            Spacer(modifier = Modifier.weight(1f))

            // ホームに戻るボタン
            CategoryItem(
                title = "ホームに戻る",
                icon = Icons.Default.Home,
                isSelected = false,
                onFocused = { /* 必要であればカテゴリ選択を解除する処理 */ },
                onClick = onBack, // 戻る処理を実行
                modifier = Modifier
            )
        }

        // --- 右側：詳細コンテンツエリア ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 48.dp, horizontal = 64.dp)
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
                1 -> PlaceholderContent("表示設定は準備中です", Icons.Default.Tv)
                2 -> AppInfoContent()
            }
        }
    }

    // 編集ダイアログ
    editingItem?.let { (title, value) ->
        InputDialog(
            title = title,
            initialValue = value,
            onDismiss = { editingItem = null },
            onConfirm = { newValue ->
                scope.launch {
                    val key = when (title) {
                        "KonomiTV アドレス" -> SettingsRepository.KONOMI_IP
                        "KonomiTV ポート番号" -> SettingsRepository.KONOMI_PORT
                        "Mirakurun IPアドレス" -> SettingsRepository.MIRAKURUN_IP
                        "Mirakurun ポート番号" -> SettingsRepository.MIRAKURUN_PORT
                        else -> null
                    }

                    if (key != null) {
                        repository.saveString(key, newValue)
                    }
                }
                editingItem = null
            }
        )
    }
}

// データクラス
data class Category(val name: String, val icon: ImageVector)

@Composable
fun ConnectionSettingsContent(
    kIp: String, kPort: String, mIp: String, mPort: String,
    onEditRequest: (String, String) -> Unit,
    firstItemModifier: Modifier = Modifier
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            text = "接続設定",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        SettingsSection(title = "KonomiTV") {
            SettingItem(
                title = "アドレス",
                value = kIp,
                icon = Icons.Default.Dns,
                modifier = firstItemModifier,
                onClick = { onEditRequest("KonomiTV アドレス", kIp) }
            )
            SettingItem(
                title = "ポート番号",
                value = kPort,
                // icon = Icons.Default.Numbers, // 必要ならアイコン追加
                onClick = { onEditRequest("KonomiTV ポート番号", kPort) }
            )
        }

        SettingsSection(title = "Mirakurun") {
            SettingItem(
                title = "アドレス",
                value = mIp,
                icon = Icons.Default.Dns,
                onClick = { onEditRequest("Mirakurun IPアドレス", mIp) }
            )
            SettingItem(
                title = "ポート番号",
                value = mPort,
                onClick = { onEditRequest("Mirakurun ポート番号", mPort) }
            )
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        content()
    }
}

@Composable
fun PlaceholderContent(message: String, icon: ImageVector) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(120.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = message,
                color = Color.Gray,
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}

@Composable
fun AppInfoContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Komorebi",
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Version 0.1.0 alpha-4",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "© 2026 Komorebi Project",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        selected = isSelected,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White.copy(alpha = 0.2f),
            contentColor = Color.Gray,
            selectedContentColor = Color.White,
            focusedContentColor = Color.White
        ),
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected || isFocused) Color.White else Color.Gray
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.weight(1f))
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(20.dp)
                        .background(Color.White, MaterialTheme.shapes.small)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingItem(
    title: String,
    value: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
            focusedContainerColor = Color.White.copy(alpha = 0.9f),
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.7f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) Color.Black.copy(0.8f) else Color.White.copy(0.6f),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}