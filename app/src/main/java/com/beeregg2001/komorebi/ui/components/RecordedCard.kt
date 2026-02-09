// ui/components/RecordedCard.kt
package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.data.model.RecordedProgram

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordedCard(
    program: RecordedProgram,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    // サムネイルURLを構築
    val thumbnailUrl = "$konomiIp:$konomiPort/api/videos/${program.recordedVideo.id}/thumbnail"

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(185.dp)
            .height(104.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.DarkGray.copy(alpha = 0.4f),
            focusedContainerColor = Color.White
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // --- サムネイル画像 ---
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = program.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // 読み込み中やエラー時のプレースホルダーを設定すると親切です
                // error = painterResource(R.drawable.error_image)
            )
            //Log.d("RecordedCard", "Thumbnail URL: $thumbnailUrl")

            // --- 下部の情報オーバーレイ ---
            // フォーカス時は少し背景を濃くして文字を読みやすく
            val overlayAlpha = if (isFocused) 0.8f else 0.5f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = overlayAlpha))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = if (isFocused) TextOverflow.Visible else TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (isFocused) {
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE, // 無限に繰り返す
                                repeatDelayMillis = 1000,   // 端まで行ったら1秒待機
                                velocity = 40.dp            // スクロール速度
                            )
                        } else {
                            Modifier
                        }
                    )
                )

                // 録画時間（分）などを表示するとさらに便利
                Text(
                    text = "${(program.duration / 60).toInt()}分",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}