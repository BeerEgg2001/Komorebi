package com.beeregg2001.komorebi.ui.setting

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    val liveQuality by repository.liveQuality.collectAsState(initial = "1080p-60fps")
    val videoQuality by repository.videoQuality.collectAsState(initial = "1080p-60fps")
    val pickupGenre by repository.homePickupGenre.collectAsState(initial = "アニメ")
    // ★追加: 有料放送を除外する設定（デフォルトはON）
    val excludePaid by repository.excludePaidBroadcasts.collectAsState(initial = "ON")

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var editingItem by remember { mutableStateOf<Pair<String, String>?>(null) }

    var showLiveQualitySelection by remember { mutableStateOf(false) }
    var showVideoQualitySelection by remember { mutableStateOf(false) }
    var showPickupGenreSelection by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    val categories = listOf(Category("接続設定", Icons.Default.CastConnected), Category("再生設定", Icons.Default.PlayCircle), Category("コメント表示設定", Icons.Default.Tv), Category("アプリ情報", Icons.Default.Info))
    val categoryFocusRequesters = remember { List(categories.size) { FocusRequester() } }
    var isSidebarFocused by remember { mutableStateOf(true) }

    val kIpFocusRequester = remember { FocusRequester() }
    val kPortFocusRequester = remember { FocusRequester() }
    val mIpFocusRequester = remember { FocusRequester() }
    val mPortFocusRequester = remember { FocusRequester() }

    val liveQFocusRequester = remember { FocusRequester() }
    val videoQFocusRequester = remember { FocusRequester() }
    val genreFocusRequester = remember { FocusRequester() }
    val exPaidFocusRequester = remember { FocusRequester() } // ★追加: 有料放送除外ボタンのフォーカス管理

    val cDefaultDisplayFocusRequester = remember { FocusRequester() }
    val cSpeedFocusRequester = remember { FocusRequester() }
    val cSizeFocusRequester = remember { FocusRequester() }
    val cOpacityFocusRequester = remember { FocusRequester() }
    val cMaxLinesFocusRequester = remember { FocusRequester() }

    val appInfoLicenseRequester = remember { FocusRequester() }

    var restoreFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var restoreCategoryIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        delay(300)
        if (restoreFocusRequester == null) { categoryFocusRequesters.getOrNull(selectedCategoryIndex)?.safeRequestFocus() }
    }

    LaunchedEffect(editingItem, showLiveQualitySelection, showVideoQualitySelection, showPickupGenreSelection, showLicenses) {
        if (editingItem == null && !showLiveQualitySelection && !showVideoQualitySelection && !showPickupGenreSelection && !showLicenses) {
            delay(250)
            if (restoreFocusRequester != null) {
                if (restoreCategoryIndex != -1 && selectedCategoryIndex != restoreCategoryIndex) { selectedCategoryIndex = restoreCategoryIndex; delay(50) }
                restoreFocusRequester?.safeRequestFocus(); restoreFocusRequester = null; restoreCategoryIndex = -1
            } else if (isSidebarFocused) { categoryFocusRequesters.getOrNull(selectedCategoryIndex)?.safeRequestFocus() }
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF111111)).onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK || keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
            if (!isSidebarFocused) { categoryFocusRequesters.getOrNull(selectedCategoryIndex)?.safeRequestFocus(); return@onKeyEvent true }
        }
        false
    }) {
        Column(modifier = Modifier.width(280.dp).fillMaxHeight().background(Color(0xFF0A0A0A)).padding(vertical = 48.dp, horizontal = 24.dp).onFocusChanged { isSidebarFocused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp, start = 8.dp)) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "設定", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
            categories.forEachIndexed { index, category ->
                CategoryItem(title = category.name, icon = category.icon, isSelected = selectedCategoryIndex == index, onFocused = { selectedCategoryIndex = index }, onClick = {
                    when (index) { 0 -> kIpFocusRequester.safeRequestFocus(); 1 -> liveQFocusRequester.safeRequestFocus(); 2 -> cDefaultDisplayFocusRequester.safeRequestFocus(); 3 -> appInfoLicenseRequester.safeRequestFocus() }
                }, modifier = Modifier.focusRequester(categoryFocusRequesters[index]))
            }
            Spacer(modifier = Modifier.weight(1f))
            CategoryItem(title = "ホームに戻る", icon = Icons.Default.Home, isSelected = false, onFocused = { }, onClick = onBack)
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 48.dp, horizontal = 64.dp)) {
            when (selectedCategoryIndex) {
                0 -> ConnectionSettingsContent(konomiIp, konomiPort, mirakurunIp, mirakurunPort, { t, v -> editingItem = t to v }, kIpFocusRequester, kPortFocusRequester, mIpFocusRequester, mPortFocusRequester, { restoreFocusRequester = it; restoreCategoryIndex = 0 })
                // ★修正: PlaybackSettingsContentに excludePaid と関連するコールバックを渡す
                1 -> PlaybackSettingsContent(liveQuality, videoQuality, pickupGenre, excludePaid, liveQFocusRequester, videoQFocusRequester, genreFocusRequester, exPaidFocusRequester, { showLiveQualitySelection = true }, { showVideoQualitySelection = true }, { showPickupGenreSelection = true }, { scope.launch { repository.saveString(SettingsRepository.EXCLUDE_PAID_BROADCASTS, if (excludePaid == "ON") "OFF" else "ON") } }, { restoreFocusRequester = it; restoreCategoryIndex = 1 })
                2 -> DisplaySettingsContent(commentDefaultDisplay, commentSpeed, commentFontSize, commentOpacity, commentMaxLines, { t, v -> editingItem = t to v }, { scope.launch { repository.saveString(SettingsRepository.COMMENT_DEFAULT_DISPLAY, if (commentDefaultDisplay == "ON") "OFF" else "ON") } }, cDefaultDisplayFocusRequester, cSpeedFocusRequester, cSizeFocusRequester, cOpacityFocusRequester, cMaxLinesFocusRequester, { restoreFocusRequester = it; restoreCategoryIndex = 2 })
                3 -> AppInfoContent({ showLicenses = true }, appInfoLicenseRequester, { restoreFocusRequester = it; restoreCategoryIndex = 3 })
            }
        }
    }

    editingItem?.let { (title, value) ->
        InputDialog(title = title, initialValue = value, onDismiss = { editingItem = null }, onConfirm = { newValue ->
            scope.launch {
                val key = when (title) { "KonomiTV アドレス" -> SettingsRepository.KONOMI_IP; "KonomiTV ポート番号" -> SettingsRepository.KONOMI_PORT; "Mirakurun IPアドレス" -> SettingsRepository.MIRAKURUN_IP; "Mirakurun ポート番号" -> SettingsRepository.MIRAKURUN_PORT; "実況コメントの速さ" -> SettingsRepository.COMMENT_SPEED; "実況フォントサイズ倍率" -> SettingsRepository.COMMENT_FONT_SIZE; "実況コメント不透明度" -> SettingsRepository.COMMENT_OPACITY; "実況最大同時表示行数" -> SettingsRepository.COMMENT_MAX_LINES; else -> null }
                if (key != null) repository.saveString(key, newValue)
            }
            editingItem = null
        })
    }

    if (showLiveQualitySelection) { SelectionDialog("ライブ視聴画質を選択", StreamQuality.entries.map { it.label to it.value }, liveQuality, { showLiveQualitySelection = false }, { scope.launch { repository.saveString(SettingsRepository.LIVE_QUALITY, it) }; showLiveQualitySelection = false }) }
    if (showVideoQualitySelection) { SelectionDialog("録画視聴画質を選択", StreamQuality.entries.map { it.label to it.value }, videoQuality, { showVideoQualitySelection = false }, { scope.launch { repository.saveString(SettingsRepository.VIDEO_QUALITY, it) }; showVideoQualitySelection = false }) }
    if (showPickupGenreSelection) {
        val genres = listOf("アニメ", "映画", "ドラマ", "スポーツ", "音楽", "バラエティ", "ドキュメンタリー")
        SelectionDialog("ピックアップジャンルを選択", genres.map { it to it }, pickupGenre, { showPickupGenreSelection = false }, { scope.launch { repository.saveString(SettingsRepository.HOME_PICKUP_GENRE, it) }; showPickupGenreSelection = false })
    }

    if (showLicenses) {
        OpenSourceLicensesScreen(
            onBack = { showLicenses = false }
        )
    }
}

@Composable
fun ConnectionSettingsContent(kIp: String, kPort: String, mIp: String, mPort: String, onEdit: (String, String) -> Unit, kIpR: FocusRequester, kPortR: FocusRequester, mIpR: FocusRequester, mPortR: FocusRequester, onClick: (FocusRequester) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("接続設定", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        SettingsSection("KonomiTV") {
            SettingItem("アドレス", kIp, Icons.Default.Dns, modifier = Modifier.focusRequester(kIpR), onClick = { onClick(kIpR); onEdit("KonomiTV アドレス", kIp) })
            SettingItem("ポート番号", kPort, modifier = Modifier.focusRequester(kPortR), onClick = { onClick(kPortR); onEdit("KonomiTV ポート番号", kPort) })
        }
        SettingsSection("Mirakurun(オプション)") {
            SettingItem("アドレス", mIp, Icons.Default.Dns, modifier = Modifier.focusRequester(mIpR), onClick = { onClick(mIpR); onEdit("Mirakurun IPアドレス", mIp) })
            SettingItem("ポート番号", mPort, modifier = Modifier.focusRequester(mPortR), onClick = { onClick(mPortR); onEdit("Mirakurun ポート番号", mPort) })
        }
    }
}

// ★修正: 引数とUIの構造を調整
@Composable
fun PlaybackSettingsContent(
    liveQ: String, videoQ: String, genre: String, excludePaid: String,
    liveR: FocusRequester, videoR: FocusRequester, genreR: FocusRequester, exPaidR: FocusRequester,
    onL: () -> Unit, onV: () -> Unit, onG: () -> Unit, onExPaid: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("再生設定", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)

        SettingsSection("画質設定") {
            SettingItem("ライブ視聴画質", StreamQuality.fromValue(liveQ).label, modifier = Modifier.focusRequester(liveR), onClick = { onClick(liveR); onL() })
            SettingItem("録画視聴画質", StreamQuality.fromValue(videoQ).label, modifier = Modifier.focusRequester(videoR), onClick = { onClick(videoR); onV() })
        }

        SettingsSection("ホーム画面設定") {
            SettingItem("ピックアップジャンル", genre, Icons.Default.Category, modifier = Modifier.focusRequester(genreR), onClick = { onClick(genreR); onG() })
            SettingItem("ピックアップから有料放送を除外する", if (excludePaid == "ON") "ON (除外する)" else "OFF (除外しない)", Icons.Default.Block, modifier = Modifier.focusRequester(exPaidR), onClick = { onClick(exPaidR); onExPaid() })
        }
    }
}

@Composable
fun DisplaySettingsContent(def: String, speed: String, size: String, opacity: String, max: String, onEdit: (String, String) -> Unit, onT: () -> Unit, defR: FocusRequester, spR: FocusRequester, szR: FocusRequester, opR: FocusRequester, mxR: FocusRequester, onClick: (FocusRequester) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("表示設定", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        SettingsSection("ニコニコ実況") {
            SettingItem("デフォルト表示", if (def == "ON") "表示" else "非表示", modifier = Modifier.focusRequester(defR), onClick = { onClick(defR); onT() })
            SettingItem("コメントの速さ", speed, Icons.Default.Chat, modifier = Modifier.focusRequester(spR), onClick = { onClick(spR); onEdit("実況コメントの速さ", speed) })
            SettingItem("サイズ倍率", size, modifier = Modifier.focusRequester(szR), onClick = { onClick(szR); onEdit("実況フォントサイズ倍率", size) })
            SettingItem("不透明度", opacity, modifier = Modifier.focusRequester(opR), onClick = { onClick(opR); onEdit("実況コメント不透明度", opacity) })
            SettingItem("最大同時表示行数", max, modifier = Modifier.focusRequester(mxR), onClick = { onClick(mxR); onEdit("実況最大同時表示行数", max) })
        }
    }
}

@Composable
fun AppInfoContent(onShow: () -> Unit, licR: FocusRequester, onClick: (FocusRequester) -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Komorebi", style = MaterialTheme.typography.displayMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Text("Version 0.3.5 beta", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(Modifier.height(48.dp))
        SettingItem("オープンソースライセンス", "", modifier = Modifier.width(400.dp).focusRequester(licR), onClick = { onClick(licR); onShow() })
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryItem(title: String, icon: ImageVector, isSelected: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(selected = isSelected, onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused() },
        colors = SelectableSurfaceDefaults.colors(containerColor = Color.Transparent, selectedContainerColor = Color.White.copy(0.1f), focusedContainerColor = Color.White.copy(0.2f), contentColor = Color.Gray, selectedContentColor = Color.White, focusedContentColor = Color.White), shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium), scale = SelectableSurfaceDefaults.scale(focusedScale = 1.05f)) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = if (isSelected || isFocused) Color.White else Color.Gray)
            Spacer(Modifier.width(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.weight(1f))
            if (isSelected) Box(Modifier.width(4.dp).height(20.dp).background(Color.White, MaterialTheme.shapes.small))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingItem(title: String, value: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(0.05f), focusedContainerColor = Color.White.copy(0.9f), contentColor = Color.White, focusedContentColor = Color.Black), shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)) {
        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(24.dp), tint = if (isFocused) Color.Black.copy(0.7f) else Color.White.copy(0.7f)); Spacer(Modifier.width(16.dp)) }
            Text(title, style = MaterialTheme.typography.bodyLarge); Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge, color = if (isFocused) Color.Black.copy(0.8f) else Color.White.copy(0.6f))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SelectionDialog(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)).onKeyEvent { if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) { onDismiss(); true } else false }, contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF222222)), modifier = Modifier.width(400.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 400.dp)) {
                    items(options) { (label, value) ->
                        val isSelected = value == current
                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect(isSelected) { if (isSelected) { delay(100); focusRequester.safeRequestFocus() } }
                        SelectionDialogItem(label, isSelected, { onSelect(value) }, Modifier.focusRequester(focusRequester))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss, colors = ButtonDefaults.colors(containerColor = Color.White.copy(0.1f), contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("キャンセル") }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SelectionDialogItem(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(containerColor = if (isSelected) Color.White.copy(0.1f) else Color.Transparent, focusedContainerColor = Color.White, contentColor = Color.White, focusedContentColor = Color.Black), shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
            if (isSelected) { Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp), tint = if (isFocused) Color.Black else Color.White) }
        }
    }
}

data class Category(val name: String, val icon: ImageVector)