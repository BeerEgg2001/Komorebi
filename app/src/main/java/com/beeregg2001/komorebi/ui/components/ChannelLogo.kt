package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.viewmodel.Channel

@Composable
fun ChannelLogo(
    channel: Channel,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent
) {
    val isKonomiMode = isKonomiTvMode(mirakurunIp)
    val logoUrl = channel.getLogoUrl(mirakurunIp, mirakurunPort, konomiIp, konomiPort)

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
                .crossfade(true)
                .build(),
            contentDescription = "${channel.name} Logo",
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
        )
    }
}