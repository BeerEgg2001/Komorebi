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
import com.beeregg2001.komorebi.data.ChannelLogoUrlCache
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

    // ★ 最適化: 解決済みの URL があれば同期的に初期値として使う。
    //   従来は必ず "" から始めて LaunchedEffect で解決していたため、
    //   リスト内でこの Composable が作り直されるたびに
    //   (1) コルーチンの往復が発生し
    //   (2) "" -> URL の 2 段階再コンポーズでロゴが一瞬消えてから出る「チラつき」
    //   が起きていた。共有キャッシュにヒットすれば最初のフレームから正しい URL で描画される。
    var logoUrl by remember(channel.id) {
        mutableStateOf(ChannelLogoUrlCache.peek(channel.id) ?: "")
    }
    LaunchedEffect(channel.id) {
        // 同期取得できた場合は再解決不要
        if (logoUrl.isEmpty()) {
            logoUrl = getLogoUrl(channel.id)
        }
    }

    // KonomiTVモード（元画像が正方形）の場合はCropして16:9枠に合わせる
    // Mirakurunモード（元画像が透過PNG等）の場合はFitで全体を収める
    val contentScale = if (isKonomiMode) ContentScale.Crop else ContentScale.Fit

    val context = LocalContext.current
    // ★ 最適化: ImageRequest は URL が変わったときだけ組み立て直す。
    //   毎回の再コンポーズで Builder を回すと AsyncImage 側が「別リクエスト」とみなし、
    //   キャッシュヒットでも余計な状態遷移が走る。
    val request = remember(context, logoUrl) {
        ImageRequest.Builder(context)
            .data(logoUrl)
            // ★最適化: TVデバイスで激しい処理落ちを引き起こすcrossfadeを無効化
            .crossfade(false)
            .build()
    }

    Box(
        modifier = modifier.background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
        )
    }
}