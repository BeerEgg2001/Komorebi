@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LastWatchedChannelCard(
    channel: Channel,
    liveChannel: Channel?,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val typeLabels =
        mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")

    var logoUrl by remember(channel.id) { mutableStateOf("") }
    LaunchedEffect(channel.id) {
        logoUrl = getLogoUrl(channel.id)
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(220.dp)
            .height(96.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus()
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.textPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                )
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                val programTitle = liveChannel?.programPresent?.title
                if (!programTitle.isNullOrEmpty()) {
                    Text(
                        text = programTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                    )
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                    )
                    Text(
                        text = "${typeLabels[channel.type] ?: channel.type} ${channel.channelNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun HotChannelCard(
    uiState: UiChannelState,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors

    var logoUrl by remember(uiState.channel.id) { mutableStateOf("") }
    LaunchedEffect(uiState.channel.id) {
        logoUrl = getLogoUrl(uiState.channel.id)
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(260.dp)
            .height(106.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus()
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.textPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(Color(0xFFE53935), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${uiState.jikkyoForce ?: 0} コメ/分",
                        color = if (isFocused) Color(0xFFE53935) else Color(0xFFE53935).copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textPrimary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = uiState.programTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun WatchHistoryCard(
    history: KonomiHistoryProgram,
    matchedProgram: RecordedProgram?, // ★ 追加: サムネイル用URL解決のために追加
    konomiIp: String,
    konomiPort: String,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    onClick: () -> Unit,
    onFocus: (progress: Float, thumbnailUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val program = history.program
    val backendType by settingViewModel.backendType.collectAsState()

    // ★ 修正: DB連携されたRecordedProgramがある場合はそれを使用し、フォールバック処理を実装
    val fallbackUrl = matchedProgram?.apiThumbnailUrl ?: UrlBuilder.getThumbnailUrl(
        backendType,
        konomiIp,
        konomiPort,
        program.id.toString()
    )
    val primaryUrl = matchedProgram?.directThumbnailUrl ?: fallbackUrl
    var currentThumbnailUrl by remember(program.id, primaryUrl) { mutableStateOf(primaryUrl) }

    val progress = remember(history) {
        runCatching {
            val start = Instant.parse(program.start_time).epochSecond
            val end = Instant.parse(program.end_time).epochSecond
            val total = (end - start).toDouble()
            if (total > 0) (history.playback_position / total).toFloat().coerceIn(0f, 1f) else 0f
        }.getOrDefault(0f)
    }

    // ★ 追加: サムネイルURLがエラー等で切り替わった際に、フォーカス中であればHeroDashboardを更新する
    LaunchedEffect(currentThumbnailUrl, isFocused) {
        if (isFocused) onFocus(progress, currentThumbnailUrl)
    }

    val context = LocalContext.current
    val imageRequest = remember(currentThumbnailUrl) {
        ImageRequest.Builder(context)
            .data(currentThumbnailUrl)
            .crossfade(true)
            .memoryCacheKey(currentThumbnailUrl)
            .diskCacheKey(currentThumbnailUrl)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(260.dp)
            .height(146.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                // LaunchedEffect側でも呼ぶが、フォーカス時も確実に呼ぶ
                if (it.isFocused) onFocus(progress, currentThumbnailUrl)
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // ★ 追加: 画像読み込み失敗時のフォールバック処理
                onError = {
                    if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                        currentThumbnailUrl = fallbackUrl
                    }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                                Color.Black.copy(alpha = if (isFocused) 0.95f else 0.85f)
                            ),
                            startY = 0f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    program.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayArrow,
                        null,
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "続きから再生",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.accent
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(if (isFocused) colors.accent else colors.accent.copy(alpha = 0.6f))
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun UpcomingReserveCard(
    reserve: ReserveItem,
    onClick: () -> Unit,
    onFocus: (startFormat: String) -> Unit,
    modifier: Modifier = Modifier,
    timeFormat: String
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val start = OffsetDateTime.parse(reserve.program.startTime)

    val startFormat = remember(start, timeFormat) {
        val pattern = if (timeFormat == "12H") "MM/dd a h:mm" else "MM/dd HH:mm"
        start.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))
    }

    val displayTime = remember(start, timeFormat) {
        val pattern = if (timeFormat == "12H") "a h:mm" else "HH:mm"
        start.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(240.dp)
            .height(106.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(startFormat)
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = if (isFocused) 1f else 0.6f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textPrimary.copy(0.7f)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .background(
                            if (isFocused) colors.accent.copy(alpha = 0.2f) else colors.textPrimary.copy(
                                0.05f
                            ),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "P${reserve.recordSettings.priority}",
                        fontSize = 10.sp,
                        color = if (isFocused) colors.accent else colors.textPrimary.copy(0.8f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                reserve.program.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(0.9f),
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                reserve.channel.name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textPrimary.copy(0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GenrePickupCard(
    program: EpgProgram,
    channelName: String,
    timeSlot: String,
    onClick: () -> Unit,
    onFocus: (startFormat: String) -> Unit,
    modifier: Modifier = Modifier,
    timeFormat: String
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val start = OffsetDateTime.parse(program.start_time)

    val startFormat = remember(start, timeFormat) {
        val pattern = if (timeFormat == "12H") "MM/dd a h:mm" else "MM/dd HH:mm"
        start.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))
    }

    val baseAlpha = if (isFocused) 1f else 0.6f
    val gradientStartColor = when (timeSlot) {
        "朝" -> Color(0xFFE65100).copy(alpha = 0.2f * baseAlpha)
        "昼" -> Color(0xFF006064).copy(alpha = 0.2f * baseAlpha)
        else -> Color(0xFF1A237E).copy(alpha = 0.2f * baseAlpha)
    }
    val timeColor = when (timeSlot) {
        "朝" -> if (colors.isDark) Color(0xFFFFCC80) else Color(0xFFE65100)
        "昼" -> if (colors.isDark) Color(0xFF81D4FA) else Color(0xFF0277BD)
        else -> if (colors.isDark) Color(0xFFB39DDB) else Color(0xFF311B92)
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(260.dp)
            .height(116.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(startFormat)
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = baseAlpha),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(gradientStartColor, Color.Transparent)))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "$startFormat - $channelName",
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor.copy(alpha = if (isFocused) 1f else 0.8f),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}