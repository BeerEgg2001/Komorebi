@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package com.beeregg2001.komorebi.ui.live

import android.os.Build
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

// ==============================================
// 本物のプレイヤーを配置した DualDisplayPlayer コンポーネント
// ==============================================
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DualDisplayPlayer(
    state: LivePlayerState,
    leftChannel: Channel,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    isMiniListOpen: Boolean,
    isUiVisible: Boolean,
    mainPlayer: ExoPlayer,
    mainVideoWidth: Int,
    mainVideoHeight: Int,
    mainPixelRatio: Float,
    mainWebViewRef: MutableState<WebView?>,
    dualPlayer: ExoPlayer,
    dualVideoWidth: Int,
    dualVideoHeight: Int,
    dualPixelRatio: Float,
    dualWebViewRef: MutableState<WebView?>,
    isSubtitleEnabled: Boolean
) {
    val colors = KomorebiTheme.colors
    val animatedLeftWeight by animateFloatAsState(
        targetValue = state.leftScreenWeight,
        label = "leftWeight"
    )
    val animatedRightWeight by animateFloatAsState(
        targetValue = state.rightScreenWeight,
        label = "rightWeight"
    )

    var isIdle by remember { mutableStateOf(false) }

    LaunchedEffect(state.lastInteractionTime) {
        isIdle = false
        delay(5000L) // 5秒間操作がなければ idle 状態へ
        isIdle = true
    }

    val leftBorderColor by animateColorAsState(
        targetValue = if (state.activeDualPlayerIndex == 0) {
            if (isIdle && !isMiniListOpen) colors.accent.copy(alpha = 0.3f) else colors.accent
        } else {
            Color.Transparent
        },
        animationSpec = tween(500),
        label = "leftBorderColor"
    )

    val rightBorderColor by animateColorAsState(
        targetValue = if (state.activeDualPlayerIndex == 1) {
            if (isIdle && !isMiniListOpen) colors.accent.copy(alpha = 0.3f) else colors.accent
        } else {
            Color.Transparent
        },
        animationSpec = tween(500),
        label = "rightBorderColor"
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .focusable()
    ) {
        // --- 左画面 (メインプレイヤー) ---
        Box(
            modifier = Modifier
                .weight(animatedLeftWeight)
                .fillMaxHeight()
                .padding(2.dp)
                .background(Color.Black)
                .border(4.dp, leftBorderColor)
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        // [解説: プレイヤーの初期紐付け]
                        // STATE_IDLEのチェックを外し、最初からプレイヤーを紐付けておくことで、
                        // 準備完了と同時にスムーズに映像が出力されるようにします。
                        player = mainPlayer
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    // [解説: プレイヤーの更新]
                    if (view.player != mainPlayer) {
                        view.player = mainPlayer
                    }
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                onRelease = { view ->
                    // [解説: メモリリークとデッドロックの防止]
                    // ComposeのツリーからAndroidViewが破棄される際（1画面に戻る等）、
                    // PlayerViewとExoPlayerの紐付けを確実に解除し、OSのリソース回収を助けます。
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )

            if (state.currentStreamSource == StreamSource.KONOMITV && (state.sseStatus == "Standby" || state.sseStatus == "Offline")) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = colors.textPrimary,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.sseDetail,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isSubtitleEnabled) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                            loadUrl("file:///android_asset/subtitle_renderer.html")
                            mainWebViewRef.value = this
                        }
                    },
                    update = { view ->
                        view.visibility =
                            if (!isUiVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            DualChannelInfoOverlay(
                channel = leftChannel,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                isFocused = state.activeDualPlayerIndex == 0,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // --- 右画面 (サブプレイヤー) ---
        Box(
            modifier = Modifier
                .weight(animatedRightWeight)
                .fillMaxHeight()
                .padding(2.dp)
                .background(Color.Black)
                .border(4.dp, rightBorderColor)
        ) {
            if (state.dualRightChannel != null) {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            // [解説: サブプレイヤーの初期紐付け]
                            player = dualPlayer
                            useController = false
                            keepScreenOn = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view ->
                        // [解説: サブプレイヤーの更新]
                        if (view.player != dualPlayer) {
                            view.player = dualPlayer
                        }
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    },
                    onRelease = { view ->
                        // [解説: サブプレイヤー解放時の安全なデタッチ]
                        view.player = null
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (state.currentStreamSource == StreamSource.KONOMITV && (state.dualSseStatus == "Standby" || state.dualSseStatus == "Offline")) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = colors.textPrimary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = state.dualSseDetail,
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isSubtitleEnabled) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                settings.apply {
                                    javaScriptEnabled = true; domStorageEnabled = true
                                }
                                loadUrl("file:///android_asset/subtitle_renderer.html")
                                dualWebViewRef.value = this
                            }
                        },
                        update = { view ->
                            view.visibility =
                                if (!isUiVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                DualChannelInfoOverlay(
                    channel = state.dualRightChannel!!,
                    mirakurunIp = mirakurunIp,
                    mirakurunPort = mirakurunPort,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    isFocused = state.activeDualPlayerIndex == 1,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (isMiniListOpen) AppStrings.DUAL_RIGHT_SELECTING else AppStrings.DUAL_RIGHT_UNSELECTED,
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
        }
    }
}

@Composable
fun DualDisplayMock(
    state: LivePlayerState,
    leftChannel: Channel,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    isMiniListOpen: Boolean
) {
    val colors = KomorebiTheme.colors
    val animatedLeftWeight by animateFloatAsState(
        targetValue = state.leftScreenWeight,
        label = "leftWeight"
    )
    val animatedRightWeight by animateFloatAsState(
        targetValue = state.rightScreenWeight,
        label = "rightWeight"
    )

    var isIdle by remember { mutableStateOf(false) }

    LaunchedEffect(state.lastInteractionTime) {
        isIdle = false
        delay(5000L)
        isIdle = true
    }

    val leftBorderColor by animateColorAsState(
        targetValue = if (state.activeDualPlayerIndex == 0) {
            if (isIdle && !isMiniListOpen) colors.accent.copy(alpha = 0.3f) else colors.accent
        } else {
            Color.Transparent
        },
        animationSpec = tween(500),
        label = "leftBorderColor"
    )

    val rightBorderColor by animateColorAsState(
        targetValue = if (state.activeDualPlayerIndex == 1) {
            if (isIdle && !isMiniListOpen) colors.accent.copy(alpha = 0.3f) else colors.accent
        } else {
            Color.Transparent
        },
        animationSpec = tween(500),
        label = "rightBorderColor"
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .focusable()
    ) {
        Box(
            modifier = Modifier
                .weight(animatedLeftWeight)
                .fillMaxHeight()
                .padding(2.dp)
                .background(Color.Black)
                .border(4.dp, leftBorderColor)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    AppStrings.DUAL_MOCK_LEFT,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            if (state.currentStreamSource == StreamSource.KONOMITV && (state.sseStatus == "Standby" || state.sseStatus == "Offline")) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = colors.textPrimary,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.sseDetail,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            DualChannelInfoOverlay(
                channel = leftChannel,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                isFocused = state.activeDualPlayerIndex == 0,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        Box(
            modifier = Modifier
                .weight(animatedRightWeight)
                .fillMaxHeight()
                .padding(2.dp)
                .background(Color.Black)
                .border(4.dp, rightBorderColor)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val rightText = when {
                    state.activeDualPlayerIndex == 1 && isMiniListOpen -> AppStrings.DUAL_MOCK_RIGHT_SELECTING
                    state.dualRightChannel != null -> AppStrings.DUAL_MOCK_RIGHT_SELECTED
                    else -> AppStrings.DUAL_MOCK_RIGHT_UNSELECTED
                }
                Text(
                    rightText,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            if (state.currentStreamSource == StreamSource.KONOMITV && (state.dualSseStatus == "Standby" || state.dualSseStatus == "Offline")) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = colors.textPrimary,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.dualSseDetail,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (state.dualRightChannel != null) {
                DualChannelInfoOverlay(
                    channel = state.dualRightChannel!!,
                    mirakurunIp = mirakurunIp,
                    mirakurunPort = mirakurunPort,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    isFocused = state.activeDualPlayerIndex == 1,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
fun DualChannelInfoOverlay(
    channel: Channel,
    mirakurunIp: String, mirakurunPort: String, konomiIp: String, konomiPort: String,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val logoUrl = if (mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()) {
        UrlBuilder.getMirakurunLogoUrl(
            mirakurunIp,
            mirakurunPort,
            channel.networkId,
            channel.serviceId
        )
    } else {
        UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
    }

    val displayType =
        if (channel.type.uppercase() == "GR") AppStrings.CHANNEL_TYPE_GR else channel.type

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.85f)
                    )
                )
            )
            .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        coil.compose.AsyncImage(
            model = logoUrl,
            contentDescription = null,
            modifier = Modifier
                .width(56.dp)
                .height(31.5.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                androidx.tv.material3.Text(
                    text = "$displayType ${channel.channelNumber}",
                    style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                androidx.tv.material3.Text(
                    text = channel.name,
                    style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            androidx.tv.material3.Text(
                text = channel.programPresent?.title ?: AppStrings.PROGRAM_INFO_NONE,
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.then(if (isFocused) Modifier.basicMarquee(initialDelayMillis = 1500) else Modifier)
            )
        }
    }
}