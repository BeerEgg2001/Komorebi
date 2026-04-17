package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.data.model.Channel

@Composable
fun ChannelLogo(
    channel: Channel,
    mirakurunIp: String,
    getLogoUrl: suspend (String) -> String, // ★ 修正: URL生成コールバックを受け取る
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent
) {
    val isKonomiMode = isKonomiTvMode(mirakurunIp)
    // ★ 修正: 非同期でロゴURLを取得する
    var logoUrl by remember(channel.id) { mutableStateOf<String>("") }
    LaunchedEffect(channel.id) {
        logoUrl = getLogoUrl(channel.id)
    }

    // KonomiTVモード（元画像が正方形）の場合はCropして16:9枠に合わせる
    // Mirakurunモード（元画像が透過PNG等）の場合はFitで全体を収める
    val contentScale = if (isKonomiMode) ContentScale.Crop else ContentScale.Fit

    Box(
        modifier = modifier.background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(logoUrl)
                // ★最適化: TVデバイスで激しい処理落ちを引き起こすcrossfadeを無効化
                .crossfade(false)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
        )
    }
}