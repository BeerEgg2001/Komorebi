package com.beeregg2001.komorebi.ui.live

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.viewmodel.Channel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StatusOverlay(
    channel: Channel,
    mirakurunIp: String?,
    mirakurunPort: String?,
    konomiIp: String,
    konomiPort: String
) {
    var currentTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while(true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    val isMirakurunAvailable = !mirakurunIp.isNullOrBlank() && !mirakurunPort.isNullOrBlank()
    val logoUrl = if (isMirakurunAvailable) {
        UrlBuilder.getMirakurunLogoUrl(mirakurunIp ?: "", mirakurunPort ?: "", channel.networkId, channel.serviceId)
    } else {
        UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(0.8f), RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(2.dp)).background(Color.White),
                contentScale = if (isMirakurunAvailable) ContentScale.Fit else ContentScale.Crop
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "${formatChannelType(channel.type)}${channel.channelNumber}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = currentTime,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
                color = Color.White
            )
        }
    }
}

@Composable
fun LiveOverlayUI(
    channel: Channel,
    programTitle: String,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    showDesc: Boolean,
    isRecording: Boolean, // ★追加
    scrollState: ScrollState
) {
    val program = channel.programPresent
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var progress by remember { mutableFloatStateOf(-1f) }
    val isMirakurunAvailable = mirakurunIp.isNotBlank() && mirakurunPort.isNotBlank()
    val logoUrl = if (isMirakurunAvailable) {
        UrlBuilder.getMirakurunLogoUrl(mirakurunIp, mirakurunPort, channel.networkId, channel.serviceId)
    } else {
        UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.displayChannelId)
    }

    LaunchedEffect(program) {
        if (program != null && !program.startTime.isNullOrEmpty() && !program.endTime.isNullOrEmpty()) {
            val startMs = sdf.parse(program.startTime)?.time ?: 0L
            val endMs = sdf.parse(program.endTime)?.time ?: 0L
            val total = endMs - startMs
            if (total > 0) {
                while (System.currentTimeMillis() < endMs) {
                    progress = ((System.currentTimeMillis() - startMs).toFloat() / total).coerceIn(0f, 1f)
                    delay(5000)
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f))))
                .padding(horizontal = 64.dp, vertical = 48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                    contentScale = if (isMirakurunAvailable) ContentScale.Fit else ContentScale.Crop
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    text = "${formatChannelType(channel.type)}${channel.channelNumber}  ${channel.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(0.8f),
                    modifier = Modifier.weight(1f) // 放送局名を左に寄せる
                )

                // ★追加: 🔴 録画中インジケーター
                if (isRecording) {
                    RecordingIndicator()
                }
            }
            Text(
                text = programTitle,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                color = Color.White,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            if (showDesc && program != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        if (!program.description.isNullOrEmpty()) {
                            Text(
                                text = program.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(0.9f),
                                modifier = Modifier.padding(bottom = 20.dp)
                            )
                        }
                        program.detail?.forEach { (k, v) ->
                            Column(Modifier.padding(bottom = 14.dp)) {
                                Text(
                                    text = "◆ $k",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(0.5f))
                                )
                                Text(
                                    text = v,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(0.85f),
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (progress >= 0f) {
                val start = program?.startTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""
                val end = program?.endTime?.let { sdf.parse(it) }?.let { timeFormat.format(it) } ?: ""
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        text = start,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.5f)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                            .height(4.dp)
                            .background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }
                    Text(
                        text = end,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.5f)
                    )
                }
            }
        }
    }
}

// ★追加: 録画中インジケーター用コンポーネント
@Composable
fun RecordingIndicator() {
    Box(
        modifier = Modifier
            .background(Color.Black.copy(0.5f), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFFF5252).copy(0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFFFF5252), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "録画中",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ... 以下のコンポーネントは変更なし
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveErrorDialog(errorMessage: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val retryButtonFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryButtonFocusRequester.requestFocus() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)),
            modifier = Modifier.width(450.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(48.dp).padding(bottom = 16.dp)
                )
                Text(
                    text = AppStrings.LIVE_PLAYER_ERROR_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(AppStrings.BUTTON_BACK)
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.weight(1f).focusRequester(retryButtonFocusRequester)
                    ) {
                        Text(AppStrings.BUTTON_RETRY)
                    }
                }
            }
        }
    }
}

@Composable
fun LiveToast(message: String?) {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(0.85f), RoundedCornerShape(32.dp))
                    .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    text = message ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }
    }
}

fun formatChannelType(type: String): String = when (type.uppercase()) {
    "GR" -> "地デジ"
    "BS" -> "BS"
    "CS" -> "CS"
    else -> type
}