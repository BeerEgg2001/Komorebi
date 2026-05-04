package com.beeregg2001.komorebi.ui.video.smb

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.*
import com.beeregg2001.komorebi.viewmodel.SmbViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbLibraryScreen(
    onBack: () -> Unit,
    onFileClick: (SmbItem) -> Unit,
    viewModel: SmbViewModel = hiltViewModel(),
    isReturningFromPlayer: Boolean = false,
    lastPlayedPath: String? = null,
    onReturnFocusConsumed: () -> Unit = {}
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val fileList by viewModel.fileList.collectAsState()
    val pinnedFolders by viewModel.pinnedFolders.collectAsState()
    val drives by viewModel.drives.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.errorMessage.collectAsState()

    val focuses = rememberRecordListFocusRequesters()
    val ticketManager = rememberFocusTicketManager()

    var isListView by remember { mutableStateOf(true) }

    var isRightMenuOpen by remember { mutableStateOf(false) }
    var isDetailOpen by remember { mutableStateOf(false) }
    var selectedItemForMenu by remember { mutableStateOf<SmbItem?>(null) }
    var isLeftMenuOverlayOpen by remember { mutableStateOf(false) }
    var lastFocusedPath by remember { mutableStateOf<String?>(null) }

    var toastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(3000)
            toastMessage = null
        }
    }

    val isAnyOverlayOpen = isRightMenuOpen || isDetailOpen || isLeftMenuOverlayOpen
    val topBarDownRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (isReturningFromPlayer && lastPlayedPath != null) {
            viewModel.initSmb(lastPlayedPath)
            ticketManager.issue(FocusTicket.TARGET_ID, path = lastPlayedPath)
            onReturnFocusConsumed()
        } else {
            viewModel.initSmb()
            ticketManager.issue(FocusTicket.LIST_TOP)
        }
    }

    val handleBackPress: () -> Unit = {
        when {
            isDetailOpen -> {
                isDetailOpen = false
                if (lastFocusedPath != null) ticketManager.issue(
                    FocusTicket.TARGET_ID,
                    path = lastFocusedPath
                )
            }

            isRightMenuOpen -> {
                isRightMenuOpen = false
                if (lastFocusedPath != null) ticketManager.issue(
                    FocusTicket.TARGET_ID,
                    path = lastFocusedPath
                )
            }

            isLeftMenuOverlayOpen -> {
                isLeftMenuOverlayOpen = false
                if (lastFocusedPath != null) ticketManager.issue(
                    FocusTicket.TARGET_ID,
                    path = lastFocusedPath
                )
            }

            else -> {
                focuses.loadingSafeHouse.safeRequestFocus("Back_SafeHouse")
                if (!viewModel.navigateUp()) {
                    onBack()
                } else {
                    ticketManager.issue(FocusTicket.LIST_TOP)
                }
            }
        }
    }
    BackHandler { handleBackPress() }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(colors.background)) {

        Box(
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focuses.loadingSafeHouse)
                .focusable()
        )

        Column(modifier = Modifier
            .fillMaxSize()
            .padding(top = 88.dp)) {
            Row(modifier = Modifier.fillMaxSize()) {

                // --- 左メニュー (ナビゲーションペイン: 常設) ---
                if (isListView) {
                    Box(
                        modifier = Modifier
                            .zIndex(2f)
                            .width(240.dp)
                            .fillMaxHeight()
                            .padding(start = 28.dp, bottom = 20.dp)
                        // ★ 修正: ここでのクリップや背景塗りを削除し、中のSurfaceに任せる
                    ) {
                        SmbNavigationPaneContent(
                            drives = drives,
                            pinnedFolders = pinnedFolders,
                            onFolderClick = { path ->
                                viewModel.loadDirectory(path)
                                ticketManager.issue(FocusTicket.LIST_TOP)
                            },
                            focuses = focuses,
                            isOverlay = false,
                            onRightKey = {
                                if (lastFocusedPath != null) ticketManager.issue(
                                    FocusTicket.TARGET_ID,
                                    path = lastFocusedPath
                                )
                                else ticketManager.issue(FocusTicket.LIST_TOP)
                            }
                        )
                    }
                }

                // --- メインコンテンツエリア ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (isListView) 16.dp else 28.dp,
                            end = 28.dp,
                            bottom = 20.dp
                        )
                        .focusProperties {
                            if (isAnyOverlayOpen) {
                                up = FocusRequester.Cancel; down = FocusRequester.Cancel
                                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            }
                        }
                ) {
                    if (isListView) {
                        SmbListContent(
                            items = fileList,
                            onItemClick = { item ->
                                if (item.isDirectory) {
                                    focuses.loadingSafeHouse.safeRequestFocus("DirClick")
                                    viewModel.loadDirectory(item.path)
                                    ticketManager.issue(FocusTicket.LIST_TOP)
                                } else onFileClick(item)
                            },
                            focuses = focuses,
                            ticketManager = ticketManager,
                            onOpenRightMenu = { selectedItemForMenu = it; isRightMenuOpen = true },
                            onLeftKey = { if (!isAnyOverlayOpen) focuses.navPane.safeRequestFocus("MoveToNav") },
                            onFocusedItemChanged = { lastFocusedPath = it.path },
                            isMenuOpen = isAnyOverlayOpen,
                            onBackPress = { handleBackPress() },
                            onTopBarDownRequesterChanged = { topBarDownRequester.safeRequestFocus() }
                        )
                    } else {
                        SmbGridContent(
                            items = fileList,
                            onItemClick = { item ->
                                if (item.isDirectory) {
                                    focuses.loadingSafeHouse.safeRequestFocus("GridDirClick")
                                    viewModel.loadDirectory(item.path)
                                    ticketManager.issue(FocusTicket.LIST_TOP)
                                } else onFileClick(item)
                            },
                            focuses = focuses,
                            ticketManager = ticketManager,
                            onOpenRightMenu = { selectedItemForMenu = it; isRightMenuOpen = true },
                            onLeftKey = { if (!isAnyOverlayOpen) isLeftMenuOverlayOpen = true },
                            onFocusedItemChanged = { lastFocusedPath = it.path },
                            isMenuOpen = isAnyOverlayOpen,
                            onBackPress = { handleBackPress() },
                            onTopBarDownRequesterChanged = { topBarDownRequester.safeRequestFocus() }
                        )
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isLoading, enter = fadeIn(), exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colors.background.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                    }
                }
            }
        }

        SmbTopBar(
            path = currentPath,
            isListView = isListView,
            onViewToggle = { isListView = !isListView; ticketManager.issue(FocusTicket.LIST_TOP) },
            onBack = handleBackPress,
            focuses = focuses,
            onFocusDown = { ticketManager.issue(FocusTicket.LIST_TOP) }
        )

        // --- オーバーレイ群 ---
        SmbRightMenuOverlay(
            isOpen = isRightMenuOpen,
            item = selectedItemForMenu,
            isPinned = selectedItemForMenu?.let { viewModel.isPinned(it.path) } ?: false,
            onClose = { handleBackPress() },
            onPlayClick = { item ->
                isRightMenuOpen = false
                if (item.isDirectory) {
                    viewModel.loadDirectory(item.path)
                    ticketManager.issue(FocusTicket.LIST_TOP)
                } else onFileClick(item)
            },
            onDetailClick = { isRightMenuOpen = false; isDetailOpen = true },
            onPinToggle = { item ->
                val added = viewModel.togglePin(item)
                toastMessage = if (added) "ピン留めしました" else "ピン留めを解除しました"
            }
        )

        SmbFileDetailOverlay(
            isOpen = isDetailOpen,
            item = selectedItemForMenu,
            onClose = { handleBackPress() }
        )

        if (!isListView) {
            SmbLeftNavPaneOverlay(
                isOpen = isLeftMenuOverlayOpen,
                drives = drives,
                pinnedFolders = pinnedFolders,
                onClose = { handleBackPress() },
                onFolderClick = { path ->
                    isLeftMenuOverlayOpen = false
                    viewModel.loadDirectory(path)
                    ticketManager.issue(FocusTicket.LIST_TOP)
                },
                focuses = focuses
            )
        }

        GlobalToast(message = toastMessage)
    }
}

// ======================================================================================
// ナビゲーションペインのコンテンツ部分
// ======================================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbNavigationPaneContent(
    drives: List<SmbItem>,
    pinnedFolders: List<SmbItem>,
    onFolderClick: (String) -> Unit,
    focuses: RecordListFocusRequesters,
    isOverlay: Boolean,
    onRightKey: () -> Unit = {}
) {
    val colors = KomorebiTheme.colors
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isOverlay) Modifier.focusRequester(focuses.navPane) else Modifier),
        colors = SurfaceDefaults.colors(
            // ★ 修正: 透明度を調整して視認性とテーマの統一感を向上
            containerColor = colors.surface.copy(alpha = if (isOverlay) 0.98f else 0.85f),
            contentColor = colors.textPrimary
        ),
        shape = RoundedCornerShape(
            topEnd = 16.dp,
            bottomEnd = 16.dp,
            topStart = if (isOverlay) 16.dp else 0.dp,
            bottomStart = if (isOverlay) 16.dp else 0.dp
        ),
        border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            var isFirstFocusableSet = false

            Column(modifier = Modifier.verticalScroll(scrollState)) {
                // --- ドライブ一覧セクション ---
                Text(
                    text = "ドライブ",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp, start = 24.dp)
                )

                drives.forEach { drive ->
                    val reqModifier = if (!isFirstFocusableSet) {
                        isFirstFocusableSet = true
                        Modifier.focusRequester(focuses.navPane)
                    } else Modifier

                    SmbMenuButton(
                        label = drive.name,
                        icon = Icons.Default.Storage,
                        onClick = { onFolderClick(drive.path) },
                        modifier = reqModifier.onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && !isOverlay) {
                                onRightKey(); true
                            } else false
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(16.dp))

                // --- ピン留めフォルダセクション ---
                Text(
                    text = "ピン留めフォルダ",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp, start = 24.dp)
                )

                if (pinnedFolders.isEmpty()) {
                    val reqModifier = if (!isFirstFocusableSet) {
                        isFirstFocusableSet = true
                        Modifier.focusRequester(focuses.navPane)
                    } else Modifier

                    Box(
                        modifier = reqModifier
                            .fillMaxWidth()
                            .focusable()
                            .padding(horizontal = 24.dp)
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && !isOverlay) {
                                    onRightKey(); true
                                } else false
                            }
                    ) {
                        Text(
                            text = "ピン留めはありません",
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    pinnedFolders.forEach { folder ->
                        val reqModifier = if (!isFirstFocusableSet) {
                            isFirstFocusableSet = true
                            Modifier.focusRequester(focuses.navPane)
                        } else Modifier

                        SmbMenuButton(
                            label = folder.name,
                            icon = Icons.Default.Folder,
                            onClick = { onFolderClick(folder.path) },
                            modifier = reqModifier.onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && !isOverlay) {
                                    onRightKey(); true
                                } else false
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

// ======================================================================================
// 左メニュー用ボタン (フォーカス時の色反転を明示的に制御)
// ======================================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SmbMenuButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    // ★ 修正: isFocused に応じて色を明示的に計算する
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val contentColor = if (isFocused) inverseColor else colors.textPrimary

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ★ 修正: 計算した contentColor を明示的に適用
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                fontSize = 15.sp,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor, // ★ 修正: ここにも明示適用
                modifier = Modifier.then(if (isFocused) Modifier.basicMarquee() else Modifier)
            )
        }
    }
}

// ======================================================================================
// 右サブメニュー用ボタン (フォーカス時の色反転を明示的に制御)
// ======================================================================================
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SmbSideMenuItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    // ★ 修正: isFocused に応じて色を明示的に計算する
    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val contentColor = if (isFocused) inverseColor else colors.textPrimary

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ★ 修正: 計算した contentColor を明示的に適用
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                maxLines = 1,
                color = contentColor, // ★ 修正: ここにも明示適用
                modifier = Modifier.then(if (isFocused) Modifier.basicMarquee() else Modifier)
            )
        }
    }
}

// ======================================================================================
// オーバーレイ群
// ======================================================================================
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalAnimationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.SmbFileDetailOverlay(isOpen: Boolean, item: SmbItem?, onClose: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            delay(150); try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(20f)
    ) {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)))
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .zIndex(21f)) {
        if (item != null) {
            Surface(
                modifier = Modifier
                    .width(350.dp)
                    .fillMaxHeight()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && (it.key == Key.Back || it.key == Key.Escape || it.key == Key.DirectionLeft)) {
                            onClose(); true
                        } else false
                    },
                colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.98f)),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 36.dp, top = 32.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    Text(
                        text = if (item.isDirectory) "フォルダ情報" else "ファイル情報",
                        color = colors.textSecondary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(modifier = Modifier.alpha(0.1f), color = colors.textSecondary)
                    Spacer(Modifier.height(24.dp))
                    DetailRow(label = "フルパス", value = item.path.replace("smb://", ""))
                    if (!item.isDirectory) DetailRow(
                        label = "サイズ",
                        value = formatFileSize(item.size)
                    )
                    val date =
                        Instant.ofEpochMilli(item.lastModified).atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                    DetailRow(
                        label = "更新日時",
                        value = date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = KomorebiTheme.colors
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = colors.textPrimary,
            lineHeight = 18.sp,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.SmbRightMenuOverlay(
    isOpen: Boolean,
    item: SmbItem?,
    isPinned: Boolean,
    onClose: () -> Unit,
    onPlayClick: (SmbItem) -> Unit,
    onDetailClick: () -> Unit,
    onPinToggle: (SmbItem) -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            delay(150); try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(10f)
    ) {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)))
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .zIndex(11f)) {
        Surface(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.Back || it.key == Key.Escape)) {
                        onClose(); true
                    } else false
                },
            colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.98f)),
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f)))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = colors.textPrimary.copy(alpha = 0.4f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(24.dp)
                )
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 28.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        SmbSideMenuItem(
                            icon = if (item?.isDirectory == true) Icons.Default.FolderOpen else Icons.Default.PlayArrow,
                            label = if (item?.isDirectory == true) "開く" else "再生する",
                            onClick = { item?.let { onPlayClick(it) } },
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                        Spacer(Modifier.height(12.dp))
                        SmbSideMenuItem(
                            icon = Icons.Default.Info,
                            label = "ファイル詳細",
                            onClick = onDetailClick
                        )
                        Spacer(Modifier.height(12.dp))
                        SmbSideMenuItem(
                            icon = Icons.Default.PushPin,
                            label = if (isPinned) "ピン留め解除" else "ピン留めする",
                            onClick = { item?.let { onPinToggle(it) } })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.SmbLeftNavPaneOverlay(
    isOpen: Boolean,
    drives: List<SmbItem>,
    pinnedFolders: List<SmbItem>,
    onClose: () -> Unit,
    onFolderClick: (String) -> Unit,
    focuses: RecordListFocusRequesters
) {
    LaunchedEffect(isOpen) {
        if (isOpen) {
            delay(150); try {
                focuses.navPane.requestFocus()
            } catch (e: Exception) {
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(10f)
    ) {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)))
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .zIndex(11f)) {
        Box(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .padding(start = 28.dp, bottom = 20.dp, top = 88.dp)
                .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionRight || it.key == Key.Back || it.key == Key.Escape)) {
                        onClose(); true
                    } else false
                }
        ) {
            SmbNavigationPaneContent(
                drives = drives,
                pinnedFolders = pinnedFolders,
                onFolderClick = onFolderClick,
                focuses = focuses,
                isOverlay = true
            )
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var digit = size.toDouble();
    var unitIndex = 0
    while (digit >= 1024 && unitIndex < units.size - 1) {
        digit /= 1024; unitIndex++
    }
    return "%.1f %s".format(digit, units[unitIndex])
}