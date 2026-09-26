package com.beeregg2001.komorebi.ui.home.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

data class HomeHeroInfo(
    val title: String,
    val subtitle: String,
    val description: String = "",
    val imageUrl: String? = null,
    val channelId: String? = null, // ★ 追加: チャンネルロゴを非同期で解決するためのID
    val isThumbnail: Boolean = false,
    val tag: String = "",
    val progress: Float? = null
)

@Composable
fun HomeHeroDashboard(
    state: HomeHeroInfo, // ★ 修正: 呼び出し元の引数名に合わせて info -> state に変更
    getLogoUrl: suspend (String) -> String, // ★ 追加: コールバックを受け取る
    shouldCropLogo: Boolean,                // ★ 追加: クロップフラグを受け取る
    modifier: Modifier = Modifier           // ★ 追加: 呼び出し元からの modifier を受け取る
) {
    val colors = KomorebiTheme.colors

    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label = "HomeHeroTransition",
        modifier = modifier.fillMaxSize() // ★ 修正: modifier を適用
    ) { targetState -> // ★ アニメーション中の状態 (targetState) に対して処理を行う

        // ★ 追加: アニメーション状態の targetState に対して非同期でURLを解決する
        var resolvedImageUrl by remember(targetState.imageUrl, targetState.channelId, targetState.isThumbnail) {
            mutableStateOf(targetState.imageUrl)
        }

        LaunchedEffect(targetState.channelId, targetState.imageUrl, targetState.isThumbnail) {
            if (!targetState.isThumbnail && targetState.channelId != null) {
                resolvedImageUrl = getLogoUrl(targetState.channelId)
            } else {
                resolvedImageUrl = targetState.imageUrl
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {

            if (targetState.tag == "Welcome") {
                val welcomeImageRes =
                    if (colors.isDark) R.drawable.dark_image else R.drawable.light_image

                Image(
                    painter = painterResource(id = welcomeImageRes),
                    contentDescription = "Welcome Background",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    colors.background,
                                    colors.background.copy(alpha = 0.8f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            } else if (targetState.isThumbnail && resolvedImageUrl != null) {
                AsyncImage(
                    model = resolvedImageUrl, // ★ 修正: 解決済みのURLを使用
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.4f)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    colors.background,
                                    colors.background.copy(alpha = 0.6f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            } else if (!targetState.isThumbnail && resolvedImageUrl != null) {
                AsyncImage(
                    model = resolvedImageUrl, // ★ 修正: 解決済みのURLを使用
                    contentDescription = null,
                    // ★ 修正: 背景の透かしロゴもフラグに従ってスケールを調整
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.35f)
                        .aspectRatio(16f / 9f)
                        .alpha(0.12f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.7f),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (targetState.tag != "Welcome" && !targetState.isThumbnail && resolvedImageUrl != null) {
                        Box(
                            modifier = Modifier
                                .size(64.dp, 36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.textPrimary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = resolvedImageUrl, // ★ 修正: 解決済みのURLを使用
                                contentDescription = "Logo",
                                modifier = Modifier.fillMaxSize(),
                                // ★ 修正: 前景のロゴをフラグに従ってスケール調整
                                contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = targetState.tag,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.accent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = targetState.title,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1500,
                        spacing = MarqueeSpacing(48.dp)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = targetState.subtitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.heightIn(min = 72.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (targetState.description.isNotEmpty()) {
                        Text(
                            text = targetState.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            maxLines = if (targetState.progress != null) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 24.sp
                        )
                        if (targetState.progress != null) Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (targetState.progress != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(280.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.textSecondary.copy(0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(targetState.progress)
                                        .fillMaxHeight()
                                        .background(colors.accent)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "続きから再生",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}