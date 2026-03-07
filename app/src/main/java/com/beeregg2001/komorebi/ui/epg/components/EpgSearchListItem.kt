package com.beeregg2001.komorebi.ui.epg.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.viewmodel.UiSearchResultItem
import com.beeregg2001.komorebi.data.util.EpgUtils
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpgSearchListItem(
    resultItem: UiSearchResultItem,
    reserveItem: ReserveItem?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val program = resultItem.program
    val channel = resultItem.channel
    val logoUrl = resultItem.logoUrl
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val isReserved = reserveItem != null
    val isRecording = reserveItem?.isRecordingInProgress == true

    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val context = LocalContext.current

    val imageRequest = remember(logoUrl) {
        ImageRequest.Builder(context)
            .data(logoUrl)
            .size(180, 100)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val primaryTextColor = if (isFocused) inverseColor else colors.textPrimary
    val secondaryTextColor =
        if (isFocused) inverseColor.copy(alpha = 0.8f) else colors.textSecondary

    val displayDate = remember(program.start_time, program.end_time) {
        try {
            val startZdt = OffsetDateTime.parse(program.start_time)
            val endZdt = OffsetDateTime.parse(program.end_time)
            val formatter = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPANESE)
            val endFormatter = DateTimeFormatter.ofPattern("HH:mm")
            "${startZdt.format(formatter)} - ${endZdt.format(endFormatter)}"
        } catch (e: Exception) {
            ""
        }
    }

    val channelTypeLabel = when (channel.type) {
        "GR" -> "地デジ"
        else -> channel.type
    }

    val majorGenre = program.genres?.firstOrNull()?.major
    val genreColor = EpgUtils.getGenreColor(majorGenre)

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(width = 2.dp, color = colors.accent),
                shape = RoundedCornerShape(4.dp)
            )
        )
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // 左側: ロゴエリア
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .fillMaxHeight()
                    .background(Color.White), // 白背景
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    // ★修正: 枠いっぱいに広げてCrop(上下を切り落とす)することで16:9にする
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 1.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (majorGenre != null) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .background(genreColor, RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = majorGenre,
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = primaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (isFocused) Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    repeatDelayMillis = 1000
                                ) else Modifier
                            )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                ) {
                    Text(
                        text = "$displayDate | $channelTypeLabel ${channel.channel_number ?: "---"} ${channel.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (isRecording) {
                        Text(
                            text = "録画中",
                            color = if (isFocused) inverseColor else Color(0xFFE53935),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else if (isReserved) {
                        Text(
                            text = "予約済",
                            color = if (isFocused) inverseColor else colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            if (isFocused) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "詳細を見る",
                    tint = inverseColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 4.dp)
                )
            }
        }
    }
}