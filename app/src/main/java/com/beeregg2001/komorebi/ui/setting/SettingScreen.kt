package com.beeregg2001.komorebi.ui.setting

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState // ★追加
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll // ★追加
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }

    val colors = KomorebiTheme.colors

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
    val excludePaid by repository.excludePaidBroadcasts.collectAsState(initial = "ON")
    val pickupTime by repository.homePickupTime.collectAsState(initial = "自動")
    val startupTab by repository.startupTab.collectAsState(initial = "ホーム")
    val currentThemeName by repository.appTheme.collectAsState(initial = "MONOTONE")

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var editingItem by remember { mutableStateOf<Pair<String, String>?>(null) }

    var showLiveQualitySelection by remember { mutableStateOf(false) }
    var showVideoQualitySelection by remember { mutableStateOf(false) }
    var showPickupGenreSelection by remember { mutableStateOf(false) }
    var showPickupTimeSelection by remember { mutableStateOf(false) }
    var showStartupTabSelection by remember { mutableStateOf(false) }
    var showThemeSelection by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    val categories = listOf(
        Category("接続設定", Icons.Default.CastConnected),
        Category("再生設定", Icons.Default.PlayCircle),
        Category("表示設定", Icons.Default.Dashboard),
        Category("コメント表示設定", Icons.Default.Tv),
        Category("アプリ情報", Icons.Default.Info)
    )
    val categoryFocusRequesters = remember { List(categories.size) { FocusRequester() } }
    var isSidebarFocused by remember { mutableStateOf(true) }

    val kIpFocusRequester = remember { FocusRequester() }
    val kPortFocusRequester = remember { FocusRequester() }
    val mIpFocusRequester = remember { FocusRequester() }
    val mPortFocusRequester = remember { FocusRequester() }

    val liveQFocusRequester = remember { FocusRequester() }
    val videoQFocusRequester = remember { FocusRequester() }

    val themeFocusRequester = remember { FocusRequester() }
    val genreFocusRequester = remember { FocusRequester() }
    val timeFocusRequester = remember { FocusRequester() }
    val exPaidFocusRequester = remember { FocusRequester() }
    val startTabFocusRequester = remember { FocusRequester() }

    val cDefaultDisplayFocusRequester = remember { FocusRequester() }
    val cSpeedFocusRequester = remember { FocusRequester() }
    val cSizeFocusRequester = remember { FocusRequester() }
    val cOpacityFocusRequester = remember { FocusRequester() }
    val cMaxLinesFocusRequester = remember { FocusRequester() }

    val appInfoLicenseRequester = remember { FocusRequester() }

    var restoreFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var restoreCategoryIndex by remember { mutableIntStateOf(-1) }

    // ★追加: コンテンツエリアのスクロール状態をカテゴリごとにリセットするためのキー
    val scrollState = rememberScrollState()
    LaunchedEffect(selectedCategoryIndex) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(Unit) {
        delay(300)
        if (restoreFocusRequester == null) { categoryFocusRequesters.getOrNull(selectedCategoryIndex)?.safeRequestFocus() }
    }

    LaunchedEffect(editingItem, showLiveQualitySelection, showVideoQualitySelection, showPickupGenreSelection, showPickupTimeSelection, showStartupTabSelection, showThemeSelection, showLicenses) {
        if (editingItem == null && !showLiveQualitySelection && !showVideoQualitySelection && !showPickupGenreSelection && !showPickupTimeSelection && !showStartupTabSelection && !showThemeSelection && !showLicenses) {
            delay(250)
            if (restoreFocusRequester != null) {
                if (restoreCategoryIndex != -1 && selectedCategoryIndex != restoreCategoryIndex) { selectedCategoryIndex = restoreCategoryIndex; delay(50) }
                restoreFocusRequester?.safeRequestFocus(); restoreFocusRequester = null; restoreCategoryIndex = -1
            } else if (isSidebarFocused) { categoryFocusRequesters.getOrNull(selectedCategoryIndex)?.safeRequestFocus() }
        }
    }

    val getRightTargetRequester: (Int) -> FocusRequester = { index ->
        when (index) {
            0 -> kIpFocusRequester
            1 -> liveQFocusRequester
            2 -> themeFocusRequester
            3 -> cDefaultDisplayFocusRequester
            4 -> appInfoLicenseRequester
            else -> FocusRequester.Default
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(colors.surface).onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK || keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) {
            if (!isSidebarFocused) { categoryFocusRequesters.getOrNull(selectedCategoryIndex)?.safeRequestFocus(); return@onKeyEvent true }
        }
        false
    }) {
        // --- サイドバー ---
        Column(modifier = Modifier.width(280.dp).fillMaxHeight().background(colors.background).padding(vertical = 48.dp, horizontal = 24.dp).onFocusChanged { isSidebarFocused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp, start = 8.dp)) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "設定", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
            categories.forEachIndexed { index, category ->
                val targetRequester = getRightTargetRequester(index)
                CategoryItem(
                    title = category.name,
                    icon = category.icon,
                    isSelected = selectedCategoryIndex == index,
                    onFocused = { selectedCategoryIndex = index },
                    onClick = { targetRequester.safeRequestFocus() },
                    modifier = Modifier
                        .focusRequester(categoryFocusRequesters[index])
                        .focusProperties { right = targetRequester }
                )
            }
            Spacer(modifier = Modifier.weight(1f))

            val activeRightTarget = getRightTargetRequester(selectedCategoryIndex)
            CategoryItem(
                title = "ホームに戻る",
                icon = Icons.Default.Home,
                isSelected = false,
                onFocused = { },
                onClick = onBack,
                modifier = Modifier.focusProperties { right = activeRightTarget }
            )
        }

        // --- メインコンテンツ (スクロール対応) ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 48.dp, horizontal = 64.dp)
                .focusProperties {
                    left = categoryFocusRequesters.getOrNull(selectedCategoryIndex) ?: FocusRequester.Default
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState) // ★追加: これで溢れた項目もスクロールで表示されます
            ) {
                when (selectedCategoryIndex) {
                    0 -> ConnectionSettingsContent(konomiIp, konomiPort, mirakurunIp, mirakurunPort, { t, v -> editingItem = t to v }, kIpFocusRequester, kPortFocusRequester, mIpFocusRequester, mPortFocusRequester, { restoreFocusRequester = it; restoreCategoryIndex = 0 })
                    1 -> PlaybackSettingsContent(liveQuality, videoQuality, liveQFocusRequester, videoQFocusRequester, { showLiveQualitySelection = true }, { showVideoQualitySelection = true }, { restoreFocusRequester = it; restoreCategoryIndex = 1 })
                    2 -> HomeDisplaySettingsContent(currentThemeName, pickupGenre, excludePaid, pickupTime, startupTab, themeFocusRequester, genreFocusRequester, exPaidFocusRequester, timeFocusRequester, startTabFocusRequester, { showThemeSelection = true }, { showPickupGenreSelection = true }, { scope.launch { repository.saveString(SettingsRepository.EXCLUDE_PAID_BROADCASTS, if (excludePaid == "ON") "OFF" else "ON") } }, { showPickupTimeSelection = true }, { showStartupTabSelection = true }, { restoreFocusRequester = it; restoreCategoryIndex = 2 })
                    3 -> DisplaySettingsContent(commentDefaultDisplay, commentSpeed, commentFontSize, commentOpacity, commentMaxLines, { t, v -> editingItem = t to v }, { scope.launch { repository.saveString(SettingsRepository.COMMENT_DEFAULT_DISPLAY, if (commentDefaultDisplay == "ON") "OFF" else "ON") } }, cDefaultDisplayFocusRequester, cSpeedFocusRequester, cSizeFocusRequester, cOpacityFocusRequester, cMaxLinesFocusRequester, { restoreFocusRequester = it; restoreCategoryIndex = 3 })
                    4 -> AppInfoContent({ showLicenses = true }, appInfoLicenseRequester, { restoreFocusRequester = it; restoreCategoryIndex = 4 })
                }
                // 下部に余白を設けて、最後の項目が埋もれないようにする
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ダイアログ類
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

    if (showThemeSelection) {
        val themeOptions = AppTheme.entries.map { it.label to it.name }
        SelectionDialog("アプリテーマを選択", themeOptions, currentThemeName, { showThemeSelection = false }, { scope.launch { repository.saveString(SettingsRepository.APP_THEME, it) }; showThemeSelection = false })
    }

    if (showPickupGenreSelection) {
        val genres = listOf("アニメ", "映画", "ドラマ", "スポーツ", "音楽", "バラエティ", "ドキュメンタリー")
        SelectionDialog("ピックアップジャンルを選択", genres.map { it to it }, pickupGenre, { showPickupGenreSelection = false }, { scope.launch { repository.saveString(SettingsRepository.HOME_PICKUP_GENRE, it) }; showPickupGenreSelection = false })
    }
    if (showPickupTimeSelection) {
        val times = listOf("自動" to "自動", "朝 (5:00 - 11:00)" to "朝", "昼 (11:00 - 18:00)" to "昼", "夜 (18:00 - 5:00)" to "夜")
        SelectionDialog("ピックアップ時間帯を選択", times, pickupTime, { showPickupTimeSelection = false }, { scope.launch { repository.saveString(SettingsRepository.HOME_PICKUP_TIME, it) }; showPickupTimeSelection = false })
    }
    if (showStartupTabSelection) {
        val tabs = listOf("ホーム" to "ホーム", "ライブ" to "ライブ", "ビデオ" to "ビデオ", "番組表" to "番組表", "録画予約" to "録画予約")
        SelectionDialog("起動時のデフォルトタブを選択", tabs, startupTab, { showStartupTabSelection = false }, { scope.launch { repository.saveString(SettingsRepository.STARTUP_TAB, it) }; showStartupTabSelection = false })
    }

    if (showLicenses) {
        OpenSourceLicensesScreen(onBack = { showLicenses = false })
    }
}

// --- 各セクション用コンポーネント (配色更新版) ---

@Composable
fun ConnectionSettingsContent(kIp: String, kPort: String, mIp: String, mPort: String, onEdit: (String, String) -> Unit, kIpR: FocusRequester, kPortR: FocusRequester, mIpR: FocusRequester, mPortR: FocusRequester, onClick: (FocusRequester) -> Unit) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("接続設定", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
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

@Composable
fun PlaybackSettingsContent(liveQ: String, videoQ: String, liveR: FocusRequester, videoR: FocusRequester, onL: () -> Unit, onV: () -> Unit, onClick: (FocusRequester) -> Unit) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("再生設定", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        SettingsSection("画質設定") {
            SettingItem("ライブ視聴画質", StreamQuality.fromValue(liveQ).label, modifier = Modifier.focusRequester(liveR), onClick = { onClick(liveR); onL() })
            SettingItem("録画視聴画質", StreamQuality.fromValue(videoQ).label, modifier = Modifier.focusRequester(videoR), onClick = { onClick(videoR); onV() })
        }
    }
}

@Composable
fun HomeDisplaySettingsContent(
    theme: String, genre: String, excludePaid: String, pickupTime: String, startupTab: String,
    themeR: FocusRequester, genreR: FocusRequester, exPaidR: FocusRequester, timeR: FocusRequester, startTabR: FocusRequester,
    onTheme: () -> Unit, onG: () -> Unit, onExPaid: () -> Unit, onTime: () -> Unit, onStartTab: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    val colors = KomorebiTheme.colors
    val currentThemeLabel = runCatching { AppTheme.valueOf(theme).label }.getOrDefault(theme)

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("表示設定", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        SettingsSection("全般") {
            SettingItem("アプリテーマ", currentThemeLabel, Icons.Default.Palette, modifier = Modifier.focusRequester(themeR), onClick = { onClick(themeR); onTheme() })
            SettingItem("起動時のデフォルトタブ", startupTab, Icons.Default.Home, modifier = Modifier.focusRequester(startTabR), onClick = { onClick(startTabR); onStartTab() })
        }
        SettingsSection("ホーム画面設定") {
            SettingItem("ピックアップジャンル", genre, Icons.Default.Category, modifier = Modifier.focusRequester(genreR), onClick = { onClick(genreR); onG() })
            SettingItem("ピックアップ時間帯", pickupTime, Icons.Default.Schedule, modifier = Modifier.focusRequester(timeR), onClick = { onClick(timeR); onTime() })
            // ★修正点: 長いラベルでもレイアウトが崩れないよう調整されます
            SettingItem("ピックアップから有料放送を除外する", if (excludePaid == "ON") "ON (除外する)" else "OFF (除外しない)", Icons.Default.Block, modifier = Modifier.focusRequester(exPaidR), onClick = { onClick(exPaidR); onExPaid() })
        }
    }
}

@Composable
fun DisplaySettingsContent(def: String, speed: String, size: String, opacity: String, max: String, onEdit: (String, String) -> Unit, onT: () -> Unit, defR: FocusRequester, spR: FocusRequester, szR: FocusRequester, opR: FocusRequester, mxR: FocusRequester, onClick: (FocusRequester) -> Unit) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("コメント表示設定", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
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
    val colors = KomorebiTheme.colors
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Komorebi", style = MaterialTheme.typography.displayMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Text("Version 0.4.0 beta", style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
        Spacer(Modifier.height(48.dp))
        SettingItem("オープンソースライセンス", "", modifier = Modifier.width(400.dp).focusRequester(licR), onClick = { onClick(licR); onShow() })
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = colors.textSecondary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryItem(title: String, icon: ImageVector, isSelected: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    Surface(selected = isSelected, onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocused() },
        colors = SelectableSurfaceDefaults.colors(containerColor = Color.Transparent, selectedContainerColor = colors.textPrimary.copy(0.1f), focusedContainerColor = colors.textPrimary.copy(0.2f), contentColor = colors.textSecondary, selectedContentColor = colors.textPrimary, focusedContentColor = colors.textPrimary), shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium), scale = SelectableSurfaceDefaults.scale(focusedScale = 1.05f)) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = if (isSelected || isFocused) colors.textPrimary else colors.textSecondary)
            Spacer(Modifier.width(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.weight(1f))
            if (isSelected) Box(Modifier.width(4.dp).height(20.dp).background(colors.accent, MaterialTheme.shapes.small))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingItem(title: String, value: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.textPrimary.copy(0.05f), focusedContainerColor = colors.textPrimary.copy(0.9f), contentColor = colors.textPrimary, focusedContentColor = if (colors.isDark) Color.Black else Color.White), shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)) {
        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(24.dp), tint = if (isFocused) Color.Transparent.copy(0.7f) else colors.textPrimary.copy(0.7f)); Spacer(Modifier.width(16.dp)) }
            // ★修正: title 側に weight(1f) を持たせることで、長いテキストでも改行を許容し、右側の value を押し出さないようにします
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f));
            Text(value, textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge, color = if (isFocused) Color.Unspecified else colors.textSecondary)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SelectionDialog(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val colors = KomorebiTheme.colors
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)).onKeyEvent { if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) { onDismiss(); true } else false }, contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = colors.surface), modifier = Modifier.width(400.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 400.dp)) {
                    items(options) { (label, value) ->
                        val isSelected = value == current
                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect(isSelected) { if (isSelected) { delay(100); focusRequester.safeRequestFocus() } }
                        SelectionDialogItem(label, isSelected, { onSelect(value) }, Modifier.focusRequester(focusRequester))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss, colors = ButtonDefaults.colors(containerColor = colors.textPrimary.copy(0.1f), contentColor = colors.textPrimary), modifier = Modifier.fillMaxWidth()) { Text("キャンセル") }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SelectionDialogItem(label: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(containerColor = if (isSelected) colors.textPrimary.copy(0.1f) else Color.Transparent, focusedContainerColor = colors.accent, contentColor = colors.textPrimary, focusedContentColor = if (colors.isDark) Color.Black else Color.White), shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
            if (isSelected) { Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp), tint = if (isFocused) Color.Unspecified else colors.textPrimary) }
        }
    }
}

data class Category(val name: String, val icon: ImageVector)