package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.components.InputDialog
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.settings.OpenSourceLicensesScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.beeregg2001.komorebi.ui.live.StreamQuality as LiveQuality
import com.beeregg2001.komorebi.ui.video.StreamQuality as VideoQuality

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }

    val konomiIp by repository.konomiIp.collectAsState(initial = "")
    val konomiPort by repository.konomiPort.collectAsState(initial = "")
    val mirakurunIp by repository.mirakurunIp.collectAsState(initial = "")
    val mirakurunPort by repository.mirakurunPort.collectAsState(initial = "")

    val commentSpeed by repository.commentSpeed.collectAsState(initial = "1.0")
    val commentFontSize by repository.commentFontSize.collectAsState(initial = "1.0")
    val commentOpacity by repository.commentOpacity.collectAsState(initial = "1.0")
    val commentMaxLines by repository.commentMaxLines.collectAsState(initial = "0")
    val commentDefaultDisplay by repository.commentDefaultDisplay.collectAsState(initial = "ON")

    // 画質設定
    val liveQuality by repository.liveQuality.collectAsState(initial = "1080p-60fps")
    val videoQuality by repository.videoQuality.collectAsState(initial = "1080p-60fps")

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var editingItem by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ★追加: 画質選択ダイアログの表示状態
    var showLiveQualitySelection by remember { mutableStateOf(false) }
    var showVideoQualitySelection by remember { mutableStateOf(false) }

    var showLicenses by remember { mutableStateOf(false) }

    val categories = listOf(
        Category("接続設定", Icons.Default.CastConnected),
        Category("再生設定", Icons.Default.PlayCircle),
        Category("コメント表示設定", Icons.Default.Tv),
        Category("アプリ情報", Icons.Default.Info)
    )

    // サイドバー項目のFocusRequesterをリスト化
    val categoryFocusRequesters = remember { List(categories.size) { FocusRequester() } }
    // サイドバーにフォーカスがあるかを管理するフラグ
    var isSidebarFocused by remember { mutableStateOf(true) }

    val kIpFocusRequester = remember { FocusRequester() }
    val kPortFocusRequester = remember { FocusRequester() }
    val mIpFocusRequester = remember { FocusRequester() }
    val mPortFocusRequester = remember { FocusRequester() }

    val liveQFocusRequester = remember { FocusRequester() }
    val videoQFocusRequester = remember { FocusRequester() }

    val cDefaultDisplayFocusRequester = remember { FocusRequester() }
    val cSpeedFocusRequester = remember { FocusRequester() }
    val cSizeFocusRequester = remember { FocusRequester() }
    val cOpacityFocusRequester = remember { FocusRequester() }
    val cMaxLinesFocusRequester = remember { FocusRequester() }

    val appInfoLicenseRequester = remember { FocusRequester() }

    var restoreFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }

    if (showLicenses) {
        OpenSourceLicensesScreen(onBack = { showLicenses = false })
    } else {
        // ダイアログが閉じられたときにフォーカスを戻す
        LaunchedEffect(editingItem, showLiveQualitySelection, showVideoQualitySelection) {
            if (editingItem == null && !showLiveQualitySelection && !showVideoQualitySelection) {
                if (restoreFocusRequester != null) {
                    delay(50)
                    restoreFocusRequester?.requestFocus()
                } else {
                    delay(50)
                    categoryFocusRequesters[selectedCategoryIndex].requestFocus()
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111111))
                .onKeyEvent { keyEvent ->
                    // 戻るボタンの制御
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                                keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {

                        // サイドバーにフォーカスがない（第2階層にいる）場合
                        if (!isSidebarFocused) {
                            // サイドバーの現在のカテゴリにフォーカスを戻す
                            categoryFocusRequesters[selectedCategoryIndex].requestFocus()
                            return@onKeyEvent true // イベントを消費して第2階層内移動として扱う
                        }
                    }
                    // サイドバーにフォーカスがある場合は false を返し、MainRootScreen の BackHandler に任せる
                    false
                }
        ) {
            // --- 左側：サイドバーメニュー (第1階層) ---
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF0A0A0A))
                    .padding(vertical = 48.dp, horizontal = 24.dp)
                    // Column内（サイドバー内）にフォーカスがあるか監視
                    .onFocusChanged { isSidebarFocused = it.hasFocus },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 32.dp, start = 8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "設定", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                }

                categories.forEachIndexed { index, category ->
                    CategoryItem(
                        title = category.name,
                        icon = category.icon,
                        isSelected = selectedCategoryIndex == index,
                        onFocused = { selectedCategoryIndex = index },
                        onClick = {
                            when (index) {
                                0 -> kIpFocusRequester.requestFocus()
                                1 -> liveQFocusRequester.requestFocus() // 再生設定
                                2 -> cDefaultDisplayFocusRequester.requestFocus()
                                3 -> appInfoLicenseRequester.requestFocus()
                            }
                        },
                        // カテゴリごとにFocusRequesterを割り当て
                        modifier = Modifier.focusRequester(categoryFocusRequesters[index])
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                CategoryItem(
                    title = "ホームに戻る",
                    icon = Icons.Default.Home,
                    isSelected = false,
                    onFocused = { },
                    onClick = onBack,
                    modifier = Modifier
                )
            }

            // --- 右側：詳細コンテンツエリア (第2階層) ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 48.dp, horizontal = 64.dp)
            ) {
                when (selectedCategoryIndex) {
                    0 -> ConnectionSettingsContent(
                        kIp = konomiIp, kPort = konomiPort, mIp = mirakurunIp, mPort = mirakurunPort,
                        onEditRequest = { title, currentVal -> editingItem = title to currentVal },
                        kIpRequester = kIpFocusRequester, kPortRequester = kPortFocusRequester,
                        mIpRequester = mIpFocusRequester, mPortRequester = mPortFocusRequester,
                        onItemClicked = { requester -> restoreFocusRequester = requester }
                    )
                    1 -> PlaybackSettingsContent(
                        liveQuality = liveQuality,
                        videoQuality = videoQuality,
                        liveQRequester = liveQFocusRequester,
                        videoQRequester = videoQFocusRequester,
                        // ★修正: トグルではなくダイアログ表示へ変更
                        onLiveQualityClick = { showLiveQualitySelection = true },
                        onVideoQualityClick = { showVideoQualitySelection = true },
                        onItemClicked = { requester -> restoreFocusRequester = requester }
                    )
                    2 -> DisplaySettingsContent(
                        defaultDisplay = commentDefaultDisplay,
                        speed = commentSpeed, size = commentFontSize, opacity = commentOpacity, maxLines = commentMaxLines,
                        onEditRequest = { title, currentVal -> editingItem = title to currentVal },
                        onToggleDefaultDisplay = {
                            scope.launch {
                                val nextValue = if (commentDefaultDisplay == "ON") "OFF" else "ON"
                                repository.saveString(SettingsRepository.COMMENT_DEFAULT_DISPLAY, nextValue)
                            }
                        },
                        defaultDisplayRequester = cDefaultDisplayFocusRequester,
                        speedRequester = cSpeedFocusRequester, sizeRequester = cSizeFocusRequester,
                        opacityRequester = cOpacityFocusRequester, maxLinesRequester = cMaxLinesFocusRequester,
                        onItemClicked = { requester -> restoreFocusRequester = requester }
                    )
                    3 -> AppInfoContent(
                        onShowLicenses = { showLicenses = true },
                        licenseRequester = appInfoLicenseRequester,
                        onItemClicked = { requester -> restoreFocusRequester = requester }
                    )
                }
            }
        }

        // --- ダイアログ関連 ---

        // テキスト入力ダイアログ
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
                            "実況コメントの速さ" -> SettingsRepository.COMMENT_SPEED
                            "実況フォントサイズ倍率" -> SettingsRepository.COMMENT_FONT_SIZE
                            "実況コメント不透明度" -> SettingsRepository.COMMENT_OPACITY
                            "実況最大同時表示行数" -> SettingsRepository.COMMENT_MAX_LINES
                            else -> null
                        }
                        if (key != null) repository.saveString(key, newValue)
                    }
                    editingItem = null
                }
            )
        }

        // ★追加: ライブ画質選択ダイアログ
        if (showLiveQualitySelection) {
            SelectionDialog(
                title = "ライブ視聴画質を選択",
                options = LiveQuality.entries.map { it.label to it.value },
                currentValue = liveQuality,
                onDismiss = { showLiveQualitySelection = false },
                onSelect = { newValue ->
                    scope.launch { repository.saveString(SettingsRepository.LIVE_QUALITY, newValue) }
                    showLiveQualitySelection = false
                }
            )
        }

        // ★追加: 録画画質選択ダイアログ
        if (showVideoQualitySelection) {
            SelectionDialog(
                title = "録画視聴画質を選択",
                options = VideoQuality.entries.map { it.label to it.apiParams },
                currentValue = videoQuality,
                onDismiss = { showVideoQualitySelection = false },
                onSelect = { newValue ->
                    scope.launch { repository.saveString(SettingsRepository.VIDEO_QUALITY, newValue) }
                    showVideoQualitySelection = false
                }
            )
        }
    }
}

data class Category(val name: String, val icon: ImageVector)

@Composable
fun ConnectionSettingsContent(
    kIp: String, kPort: String, mIp: String, mPort: String,
    onEditRequest: (String, String) -> Unit,
    kIpRequester: FocusRequester, kPortRequester: FocusRequester, mIpRequester: FocusRequester, mPortRequester: FocusRequester,
    onItemClicked: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(text = "接続設定", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
        SettingsSection(title = "KonomiTV") {
            SettingItem(title = "アドレス", value = kIp, icon = Icons.Default.Dns, modifier = Modifier.focusRequester(kIpRequester), onClick = { onItemClicked(kIpRequester); onEditRequest("KonomiTV アドレス", kIp) })
            SettingItem(title = "ポート番号", value = kPort, modifier = Modifier.focusRequester(kPortRequester), onClick = { onItemClicked(kPortRequester); onEditRequest("KonomiTV ポート番号", kPort) })
        }
        SettingsSection(title = "Mirakurun(オプション)") {
            SettingItem(title = "アドレス", value = mIp, icon = Icons.Default.Dns, modifier = Modifier.focusRequester(mIpRequester), onClick = { onItemClicked(mIpRequester); onEditRequest("Mirakurun IPアドレス", mIp) })
            SettingItem(title = "ポート番号", value = mPort, modifier = Modifier.focusRequester(mPortRequester), onClick = { onItemClicked(mPortRequester); onEditRequest("Mirakurun ポート番号", mPort) })
        }
    }
}

@Composable
fun PlaybackSettingsContent(
    liveQuality: String, videoQuality: String,
    liveQRequester: FocusRequester, videoQRequester: FocusRequester,
    onLiveQualityClick: () -> Unit, onVideoQualityClick: () -> Unit,
    onItemClicked: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(text = "再生設定", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
        SettingsSection(title = "デフォルト画質 (KonomiTV)") {
            SettingItem(
                title = "ライブ視聴画質",
                value = LiveQuality.fromValue(liveQuality).label,
                icon = Icons.Default.HighQuality,
                modifier = Modifier.focusRequester(liveQRequester),
                onClick = { onItemClicked(liveQRequester); onLiveQualityClick() }
            )
            SettingItem(
                title = "録画視聴画質",
                value = VideoQuality.fromApiParams(videoQuality).label,
                icon = Icons.Default.HighQuality,
                modifier = Modifier.focusRequester(videoQRequester),
                onClick = { onItemClicked(videoQRequester); onVideoQualityClick() }
            )
        }
    }
}

@Composable
fun DisplaySettingsContent(
    defaultDisplay: String, speed: String, size: String, opacity: String, maxLines: String,
    onEditRequest: (String, String) -> Unit,
    onToggleDefaultDisplay: () -> Unit,
    defaultDisplayRequester: FocusRequester, speedRequester: FocusRequester, sizeRequester: FocusRequester,
    opacityRequester: FocusRequester, maxLinesRequester: FocusRequester,
    onItemClicked: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(text = "表示設定", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
        SettingsSection(title = "ニコニコ実況") {
            SettingItem(title = "コメントのデフォルト表示", value = if (defaultDisplay == "ON") "表示" else "非表示", modifier = Modifier.focusRequester(defaultDisplayRequester), onClick = { onItemClicked(defaultDisplayRequester); onToggleDefaultDisplay() })
            SettingItem(title = "コメントの速さ", value = speed, icon = Icons.Default.Chat, modifier = Modifier.focusRequester(speedRequester), onClick = { onItemClicked(speedRequester); onEditRequest("実況コメントの速さ", speed) })
            SettingItem(title = "フォントサイズ倍率", value = size, modifier = Modifier.focusRequester(sizeRequester), onClick = { onItemClicked(sizeRequester); onEditRequest("実況フォントサイズ倍率", size) })
            SettingItem(title = "コメント不透明度", value = opacity, modifier = Modifier.focusRequester(opacityRequester), onClick = { onItemClicked(opacityRequester); onEditRequest("実況コメント不透明度", opacity) })
            SettingItem(title = "最大同時表示行数", value = maxLines, modifier = Modifier.focusRequester(maxLinesRequester), onClick = { onItemClicked(maxLinesRequester); onEditRequest("実況最大同時表示行数", maxLines) })
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
fun AppInfoContent(onShowLicenses: () -> Unit, licenseRequester: FocusRequester, onItemClicked: (FocusRequester) -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Komorebi", style = MaterialTheme.typography.displayMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Version 0.2.0 beta2", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(48.dp))
        SettingItem(title = "オープンソースライセンス", value = "", modifier = Modifier.width(400.dp).focusRequester(licenseRequester), onClick = { onItemClicked(licenseRequester); onShowLicenses() })
        Spacer(modifier = Modifier.height(48.dp))
        Text(text = "© 2026 Komorebi Project", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryItem(title: String, icon: ImageVector, isSelected: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        selected = isSelected, onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused() },
        colors = SelectableSurfaceDefaults.colors(containerColor = Color.Transparent, selectedContainerColor = Color.White.copy(alpha = 0.1f), focusedContainerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.Gray, selectedContentColor = Color.White, focusedContentColor = Color.White),
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (isSelected || isFocused) Color.White else Color.Gray)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            Spacer(modifier = Modifier.weight(1f))
            if (isSelected) Box(modifier = Modifier.width(4.dp).height(20.dp).background(Color.White, MaterialTheme.shapes.small))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingItem(title: String, value: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.05f), focusedContainerColor = Color.White.copy(alpha = 0.9f), contentColor = Color.White, focusedContentColor = Color.Black),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.wrapContentWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.7f))
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Text(text = value, modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge, color = if (isFocused) Color.Black.copy(0.8f) else Color.White.copy(0.6f), fontWeight = FontWeight.SemiBold)
        }
    }
}

// ★追加: 汎用選択ダイアログ
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SelectionDialog(
    title: String,
    options: List<Pair<String, String>>, // Label, Value
    currentValue: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF222222)),
            modifier = Modifier.width(400.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(options) { (label, value) ->
                        val isSelected = value == currentValue
                        val focusRequester = remember { FocusRequester() }

                        LaunchedEffect(Unit) {
                            if (isSelected) {
                                delay(50)
                                focusRequester.requestFocus()
                            }
                        }

                        SelectionDialogItem(
                            label = label,
                            isSelected = isSelected,
                            onClick = { onSelect(value) },
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("キャンセル")
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SelectionDialogItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(0.1f) else Color.Transparent,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
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
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = if (isFocused) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}