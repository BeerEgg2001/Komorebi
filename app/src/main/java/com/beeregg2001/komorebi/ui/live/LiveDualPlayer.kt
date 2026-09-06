@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package com.beeregg2001.komorebi.ui.live

import android.os.Build
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.ChannelLogoUrlCache
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionOverlay
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

/**
 * 映像の表示アスペクト比（横 / 縦）を求める。
 *
 * ExoPlayer が通知するピクセルアスペクト比（1440x1080 のアナモルフィック等）を加味する。
 * 映像サイズがまだ取得できていない場合は 16:9 とみなす。
 */
private fun calcVideoAspectRatio(width: Int, height: Int, pixelRatio: Float): Float {
    if (width <= 0 || height <= 0) return 16f / 9f
    val par = if (pixelRatio > 0f) pixelRatio else 1f
    val ratio = (width.toFloat() * par) / height.toFloat()
    return if (ratio.isFinite() && ratio > 0f) ratio else 16f / 9f
}

// ==============================================
// 本物のプレイヤーを配置した DualDisplayPlayer コンポーネント
// ==============================================
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DualDisplayPlayer(
    state: LivePlayerState,
    leftChannel: Channel,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    isMiniListOpen: Boolean,
    isUiVisible: Boolean,
    mainPlayer: ExoPlayer?,
    mainVideoWidth: Int,
    mainVideoHeight: Int,
    mainPixelRatio: Float,
    mainCaptionCue: NativeCaptionCue?,
    dualPlayer: ExoPlayer?,
    dualVideoWidth: Int,
    dualVideoHeight: Int,
    dualPixelRatio: Float,
    dualCaptionCue: NativeCaptionCue?,
    isSubtitleEnabled: Boolean,
    // ★ 追加: 二画面表示中のフォーカス受け皿。単画面時のPlayerView(AndroidView)と同じ役割で、
    // 呼び出し側からFocusRequesterを結び付けられるようにする。
    modifier: Modifier = Modifier,
    // ★ 追加: ミニリスト/サブメニュー表示中はプレイヤー本体をフォーカス対象から外す(単画面時と同じ挙動)
    isFocusable: Boolean = true
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

    val showInfo = !isIdle || isMiniListOpen

    // 映像の表示アスペクト比。取得できるまでは 16:9 とみなす
    val mainAspectRatio = calcVideoAspectRatio(mainVideoWidth, mainVideoHeight, mainPixelRatio)
    val dualAspectRatio = calcVideoAspectRatio(dualVideoWidth, dualVideoHeight, dualPixelRatio)

    Row(
        modifier = modifier
            .fillMaxSize()
            .focusable(isFocusable)
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
            if (mainPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        AspectRatioFrameLayout(ctx).apply {
                            // ビュー自体のサイズを Compose 側 (aspectRatio) で映像の矩形そのものに
                            // 合わせるため、ビュー内部でのレターボックス計算は行わない (FILL)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                            keepScreenOn = true
                            addView(
                                SurfaceView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            )
                        }
                    },
                    update = { view ->
                        // 全画面表示から二画面表示へ切り替えた直後に、映像の出力先が
                        // 全画面用の古い Surface に残ったままになると、映像が全画面サイズの
                        // まま描画され一部だけを切り出したズーム表示になる。
                        // 毎回この二画面用の SurfaceView へ明示的に結び付け直して防ぐ。
                        val surfaceView = view.getChildAt(0) as SurfaceView
                        mainPlayer.setVideoSurfaceView(surfaceView)
                    },
                    // ★ 修正2: 破棄時に参照を外す
                    onRelease = { view ->
                        (view.getChildAt(0) as? SurfaceView)?.let {
                            mainPlayer.clearVideoSurfaceView(it)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .aspectRatio(mainAspectRatio)
                )

                var isMainBuffering by remember { mutableStateOf(false) }
                DisposableEffect(mainPlayer) {
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isMainBuffering =
                                (playbackState == androidx.media3.common.Player.STATE_BUFFERING)
                        }
                    }
                    mainPlayer.addListener(listener)
                    isMainBuffering =
                        mainPlayer.playbackState == androidx.media3.common.Player.STATE_BUFFERING
                    onDispose { mainPlayer.removeListener(listener) }
                }

                val showMainLoading = if (state.currentStreamSource == StreamSource.KONOMITV) {
                    state.sseStatus == "Standby" || state.sseStatus == "Offline"
                } else {
                    isMainBuffering
                }
                val mainLoadingText =
                    if (state.currentStreamSource == StreamSource.KONOMITV) state.sseDetail else AppStrings.STATUS_LOADING

                androidx.compose.animation.AnimatedVisibility(
                    visible = showMainLoading,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = colors.textPrimary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = mainLoadingText,
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = colors.textPrimary,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
            }

            if (isSubtitleEnabled) {
                // 字幕は映像の矩形に重ねる (枠いっぱいに広げるとレターボックス分だけ縦に伸びてしまう)
                NativeCaptionOverlay(
                    cue = mainCaptionCue,
                    visible = !isUiVisible,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .aspectRatio(mainAspectRatio)
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showInfo,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(500)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                DualChannelInfoOverlay(
                    channel = leftChannel,
                    isFocused = state.activeDualPlayerIndex == 0,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo
                )
            }
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
                if (dualPlayer != null) {
                    AndroidView(
                        factory = { ctx ->
                            AspectRatioFrameLayout(ctx).apply {
                                // 左画面と同様、レターボックスは Compose 側で行う
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                                keepScreenOn = true
                                addView(
                                    SurfaceView(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                )
                            }
                        },
                        update = { view ->
                            val surfaceView = view.getChildAt(0) as SurfaceView
                            dualPlayer.setVideoSurfaceView(surfaceView)
                        },
                        // ★ 修正2: 破棄時に参照を外す
                        onRelease = { view ->
                            (view.getChildAt(0) as? SurfaceView)?.let {
                                dualPlayer.clearVideoSurfaceView(it)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .aspectRatio(dualAspectRatio)
                    )

                    var isDualBuffering by remember { mutableStateOf(false) }
                    DisposableEffect(dualPlayer) {
                        val listener = object : androidx.media3.common.Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                isDualBuffering =
                                    (playbackState == androidx.media3.common.Player.STATE_BUFFERING)
                            }
                        }
                        dualPlayer.addListener(listener)
                        isDualBuffering =
                            dualPlayer.playbackState == androidx.media3.common.Player.STATE_BUFFERING
                        onDispose { dualPlayer.removeListener(listener) }
                    }

                    val showDualLoading = if (state.currentStreamSource == StreamSource.KONOMITV) {
                        state.dualSseStatus == "Standby" || state.dualSseStatus == "Offline"
                    } else {
                        isDualBuffering
                    }
                    val dualLoadingText =
                        if (state.currentStreamSource == StreamSource.KONOMITV) state.dualSseDetail else AppStrings.STATUS_LOADING

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showDualLoading,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = colors.textPrimary,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = dualLoadingText,
                                color = colors.textPrimary,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = colors.textPrimary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }

                if (isSubtitleEnabled) {
                    // 左画面と同様、字幕は映像の矩形に重ねる
                    NativeCaptionOverlay(
                        cue = dualCaptionCue,
                        visible = !isUiVisible,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .aspectRatio(dualAspectRatio)
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showInfo,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(500)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    DualChannelInfoOverlay(
                        channel = state.dualRightChannel!!,
                        isFocused = state.activeDualPlayerIndex == 1,
                        getLogoUrl = getLogoUrl,
                        shouldCropLogo = shouldCropLogo
                    )
                }
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
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
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

    val showInfo = !isIdle || isMiniListOpen

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

            androidx.compose.animation.AnimatedVisibility(
                visible = state.currentStreamSource == StreamSource.KONOMITV && (state.sseStatus == "Standby" || state.sseStatus == "Offline"),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
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

            androidx.compose.animation.AnimatedVisibility(
                visible = showInfo,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(500)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                DualChannelInfoOverlay(
                    channel = leftChannel,
                    isFocused = state.activeDualPlayerIndex == 0,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo
                )
            }
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

            androidx.compose.animation.AnimatedVisibility(
                visible = state.currentStreamSource == StreamSource.KONOMITV && (state.dualSseStatus == "Standby" || state.dualSseStatus == "Offline"),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
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
                androidx.compose.animation.AnimatedVisibility(
                    visible = showInfo,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(500)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    DualChannelInfoOverlay(
                        channel = state.dualRightChannel!!,
                        isFocused = state.activeDualPlayerIndex == 1,
                        getLogoUrl = getLogoUrl,
                        shouldCropLogo = shouldCropLogo
                    )
                }
            }
        }
    }
}

@Composable
fun DualChannelInfoOverlay(
    channel: Channel,
    isFocused: Boolean,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier
) {
    // ★ 最適化: 共有キャッシュから同期的に初期値を取得(チラつき・再解決の防止)
    var logoUrl by remember(channel.id) {
        mutableStateOf(ChannelLogoUrlCache.peek(channel.id) ?: "")
    }

    LaunchedEffect(channel.id) {
        if (logoUrl.isEmpty()) logoUrl = getLogoUrl(channel.id)
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
            contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
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
