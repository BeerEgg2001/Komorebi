@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.video.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.AudioMode
import kotlinx.coroutines.delay
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

/** サブメニュー内の遷移先と、遷移前後のフォーカス位置を保持する。 */
@Stable
internal class SubMenuNavigationState<T : Any> {
    var destination by mutableStateOf<T?>(null)
        private set

    private var returnFocusRequester: FocusRequester? = null
    private var destinationFocusRequester: FocusRequester? = null

    fun navigateTo(
        destination: T,
        returnFocusRequester: FocusRequester,
        destinationFocusRequester: FocusRequester
    ) {
        this.returnFocusRequester = returnFocusRequester
        this.destinationFocusRequester = destinationFocusRequester
        this.destination = destination
    }

    fun navigateBack() {
        destination = null
        destinationFocusRequester = null
    }

    fun currentDestinationFocusRequester(): FocusRequester? = destinationFocusRequester

    fun consumeReturnFocusRequester(): FocusRequester? {
        return returnFocusRequester.also { returnFocusRequester = null }
    }
}

@Composable
internal fun <T : Any> rememberSubMenuNavigationState(): SubMenuNavigationState<T> =
    remember { SubMenuNavigationState() }

@Composable
fun VideoTopSubMenuUI(
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    isSubtitleEnabled: Boolean,
    currentQuality: StreamQuality,
    isCommentEnabled: Boolean,
    isLCropEnabled: Boolean,
    isAutoCmSkipEnabled: Boolean,
    availableQualities: List<StreamQuality>,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    onLCropToggle: () -> Unit,
    onAutoCmSkipToggle: () -> Unit,
    // ★ 追加: 各機能のサポート状況を受け取るフラグ (既存に影響しないようデフォルトは true)
    isAudioSupported: Boolean = true,
    isQualitySupported: Boolean = true,
    isCommentSupported: Boolean = true,
    isSubtitleSupported: Boolean = true,
    isAutoCmSkipSupported: Boolean = true
) {
    val colors = KomorebiTheme.colors
    var selectedCategory by remember { mutableStateOf<SubMenuCategory?>(null) }
    val qualityButtonRequester = remember { FocusRequester() }
    val qualityListRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == SubMenuCategory.QUALITY) {
            delay(100)
            try {
                qualityListRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.background.copy(alpha = 0.9f), Color.Transparent)
                )
            )
            .padding(top = 24.dp, bottom = 48.dp)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE)
                ) {
                    if (selectedCategory != null) {
                        selectedCategory = null
                        try {
                            qualityButtonRequester.requestFocus()
                        } catch (e: Exception) {
                        }
                        true
                    } else false
                } else false
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                VideoMenuTileItem(
                    title = "音声切替",
                    icon = Icons.Default.Audiotrack,
                    subtitle = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                    onClick = onAudioToggle,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = isAudioSupported // ★ 適用
                )
                VideoMenuTileItem(
                    title = "再生速度",
                    icon = Icons.Default.Speed,
                    subtitle = "${currentSpeed}x",
                    onClick = onSpeedToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = true // 速度は常に利用可能
                )
                VideoMenuTileItem(
                    title = "字幕",
                    icon = Icons.Default.Subtitles,
                    subtitle = if (isSubtitleEnabled) "表示" else "非表示",
                    onClick = onSubtitleToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = isSubtitleSupported // ★ 適用
                )
                VideoMenuTileItem(
                    title = "L字クロップ",
                    icon = Icons.Default.Crop,
                    subtitle = if (isLCropEnabled) "有効" else "設定",
                    onClick = onLCropToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (isLCropEnabled) colors.accent else colors.textPrimary,
                    enabled = true // L字クロップは常に利用可能
                )

                VideoMenuTileItem(
                    title = "自動CMスキップ",
                    icon = Icons.Default.FastForward,
                    subtitle = if (isAutoCmSkipEnabled) "有効" else "無効",
                    onClick = onAutoCmSkipToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (isAutoCmSkipEnabled) colors.accent else colors.textPrimary,
                    enabled = isAutoCmSkipSupported // ★ 適用
                )

                VideoMenuTileItem(
                    title = "実況コメント",
                    icon = Icons.Default.Chat,
                    subtitle = if (isCommentEnabled) "表示" else "非表示",
                    onClick = onCommentToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = isCommentSupported // ★ 適用
                )
                VideoMenuTileItem(
                    title = "画質",
                    icon = Icons.Default.HighQuality,
                    subtitle = currentQuality.label,
                    onClick = {
                        if (isQualitySupported && availableQualities.isNotEmpty()) { // ★ 修正: 無効時は無視
                            selectedCategory =
                                if (selectedCategory == SubMenuCategory.QUALITY) null else SubMenuCategory.QUALITY
                        }
                    },
                    modifier = Modifier
                        .focusRequester(qualityButtonRequester)
                        .focusProperties {
                            if (selectedCategory != SubMenuCategory.QUALITY) down =
                                FocusRequester.Cancel
                        },
                    contentColor = colors.textPrimary,
                    enabled = isQualitySupported && availableQualities.isNotEmpty() // ★ 適用
                )
            }

            AnimatedVisibility(
                visible = selectedCategory == SubMenuCategory.QUALITY,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(2.dp)
                            .background(colors.textPrimary.copy(alpha = 0.2f))
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 8.dp)
                    ) {
                        availableQualities.forEach { quality ->
                            val isSelected = currentQuality.value == quality.value

                            VideoMenuTileItem(
                                title = quality.label,
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                                subtitle = if (isSelected) "選択中" else "",
                                onClick = {
                                    onQualitySelect(quality)
                                    selectedCategory = null
                                    try {
                                        qualityButtonRequester.requestFocus()
                                    } catch (e: Exception) {
                                    }
                                },
                                width = 160.dp,
                                height = 100.dp,
                                modifier = Modifier
                                    .then(
                                        if (isSelected) Modifier.focusRequester(
                                            qualityListRequester
                                        ) else Modifier
                                    )
                                    .focusProperties {
                                        up = qualityButtonRequester
                                        down = FocusRequester.Cancel
                                    },
                                contentColor = colors.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoMenuTileItem(
    title: String,
    icon: ImageVector,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 160.dp,
    height: Dp = 100.dp,
    contentColor: Color = Color.White
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.1f),
            contentColor = if (enabled) contentColor else colors.textPrimary.copy(alpha = 0.3f),
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier
            .size(width, height)
            // ★ 追加: 非対応項目は半透明にしてグレーアウトを強調
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    ExperimentalAnimationApi::class
)
@Composable
fun AnimatedVisibilityScope.ModernVideoSettingsOverlay(
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    isSubtitleEnabled: Boolean,
    currentQuality: StreamQuality,
    isCommentEnabled: Boolean,
    isLCropEnabled: Boolean,
    isAutoCmSkipEnabled: Boolean,
    availableQualities: List<StreamQuality>,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    onLCropToggle: () -> Unit,
    onAutoCmSkipToggle: () -> Unit,
    // ★ 追加: 各機能のサポート状況を受け取るフラグ
    isAudioSupported: Boolean = true,
    isQualitySupported: Boolean = true,
    isCommentSupported: Boolean = true,
    isSubtitleSupported: Boolean = true,
    isAutoCmSkipSupported: Boolean = true,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val navigationState = rememberSubMenuNavigationState<SubMenuCategory>()
    val selectedCategory = navigationState.destination
    val initialFocusRequester = remember { FocusRequester() }
    val qualityButtonRequester = remember { FocusRequester() }
    val qualityListRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        try {
            initialFocusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory != null) {
            navigationState.currentDestinationFocusRequester()
                ?.safeRequestFocusWithRetry("ModernVideoSettings_Destination")
        } else {
            navigationState.consumeReturnFocusRequester()
                ?.safeRequestFocusWithRetry("ModernVideoSettings_Return")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown &&
                    (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)
                ) {
                    if (selectedCategory != null) {
                        navigationState.navigateBack()
                        true
                    } else {
                        onClose()
                        true
                    }
                } else false
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .animateEnterExit(
                    enter = slideInHorizontally { fullWidth -> fullWidth },
                    exit = slideOutHorizontally { fullWidth -> fullWidth }
                )
                .fillMaxHeight()
                .width(360.dp)
                .background(colors.surface.copy(alpha = 0.95f))
                .border(1.dp, colors.textPrimary.copy(alpha = 0.1f))
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = colors.textPrimary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (selectedCategory == SubMenuCategory.QUALITY) "画質の選択" else "プレイヤー設定",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(targetState = selectedCategory, label = "SettingsMenu") { category ->
                if (category == null) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ModernSettingRow(
                            title = "音声切替",
                            value = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                            icon = Icons.Default.Audiotrack,
                            onClick = onAudioToggle,
                            modifier = Modifier.focusRequester(initialFocusRequester),
                            enabled = isAudioSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "再生速度",
                            value = "${currentSpeed}x",
                            icon = Icons.Default.Speed,
                            onClick = onSpeedToggle,
                            enabled = true
                        )
                        ModernSettingRow(
                            title = "字幕",
                            value = if (isSubtitleEnabled) "表示" else "非表示",
                            icon = Icons.Default.Subtitles,
                            onClick = onSubtitleToggle,
                            enabled = isSubtitleSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "画質",
                            value = currentQuality.label,
                            icon = Icons.Default.HighQuality,
                            onClick = {
                                if (isQualitySupported) {
                                    navigationState.navigateTo(
                                        destination = SubMenuCategory.QUALITY,
                                        returnFocusRequester = qualityButtonRequester,
                                        destinationFocusRequester = qualityListRequester
                                    )
                                }
                            }, // ★ 無効時は開かない
                            modifier = Modifier.focusRequester(qualityButtonRequester),
                            enabled = isQualitySupported && availableQualities.isNotEmpty() // ★ 適用
                        )
                        ModernSettingRow(
                            title = "自動CMスキップ",
                            value = if (isAutoCmSkipEnabled) "有効" else "無効",
                            icon = Icons.Default.FastForward,
                            onClick = onAutoCmSkipToggle,
                            highlight = isAutoCmSkipEnabled,
                            enabled = isAutoCmSkipSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "実況コメント",
                            value = if (isCommentEnabled) "表示" else "非表示",
                            icon = Icons.Default.Chat,
                            onClick = onCommentToggle,
                            enabled = isCommentSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "L字クロップ",
                            value = if (isLCropEnabled) "有効" else "設定",
                            icon = Icons.Default.Crop,
                            onClick = onLCropToggle,
                            highlight = isLCropEnabled,
                            enabled = true
                        )
                    }
                } else if (category == SubMenuCategory.QUALITY) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableQualities.forEach { quality ->
                            val isSelected = currentQuality.value == quality.value
                            ModernSettingRow(
                                title = quality.label,
                                value = if (isSelected) "✓" else "",
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                                onClick = {
                                    onQualitySelect(quality)
                                    navigationState.navigateBack()
                                },
                                highlight = isSelected,
                                modifier = if (isSelected) Modifier.focusRequester(
                                    qualityListRequester
                                ) else Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ModernSettingRow(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    enabled: Boolean = true
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = if (enabled) ClickableSurfaceDefaults.scale(focusedScale = 1.05f) else ClickableSurfaceDefaults.scale(
            focusedScale = 1f
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlight && enabled) colors.accent.copy(alpha = 0.1f) else Color.Transparent,
            focusedContainerColor = if (enabled) colors.accent else Color.White.copy(alpha = 0.1f),
            contentColor = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.5f),
            focusedContentColor = if (enabled) (if (colors.isDark) Color.Black else Color.White) else colors.textSecondary.copy(
                alpha = 0.5f
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            // ★ 追加: 非対応項目は半透明にしてグレーアウトを強調
            .alpha(if (enabled) 1f else 0.4f)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isFocused) Color.Unspecified else if (highlight && enabled) colors.accent else colors.textSecondary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) Color.Unspecified else colors.textSecondary
            )
        }
    }
}