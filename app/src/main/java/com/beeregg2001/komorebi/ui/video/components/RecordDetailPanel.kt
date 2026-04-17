package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordDetailPanel(
    program: RecordedProgram?,
    konomiIp: String,
    konomiPort: String,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    focusRequester: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    timeFormat: String = "24H"
) {
    val colors = KomorebiTheme.colors
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val backendType by settingViewModel.backendType.collectAsState()

    if (program == null) return

    // ★ 修正: UrlBuilderの直接呼び出しをやめ、モデルが持つURLを使ってフォールバック処理を実装
    val fallbackUrl = program.apiThumbnailUrl ?: UrlBuilder.getThumbnailUrl(
        backendType,
        konomiIp,
        konomiPort,
        program.id.toString()
    )
    val primaryUrl = program.directThumbnailUrl ?: fallbackUrl
    var currentThumbnailUrl by remember(program.id, primaryUrl) { mutableStateOf(primaryUrl) }

    val displayDate = remember(program.startTime, timeFormat) {
        try {
            val zdt = ZonedDateTime.parse(program.startTime)
            val pattern = if (timeFormat == "12H") "yyyy/MM/dd(E) a h:mm" else "yyyy/MM/dd(E) HH:mm"
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE)
            zdt.format(formatter)
        } catch (e: Exception) {
            program.startTime.take(16).replace("-", "/")
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft, Key.Back, Key.Escape -> {
                            onClose()
                            true
                        }

                        Key.DirectionDown -> {
                            coroutineScope.launch { scrollState.animateScrollTo(scrollState.value + 200) }
                            true
                        }

                        Key.DirectionUp -> {
                            coroutineScope.launch { scrollState.animateScrollTo(scrollState.value - 200) }
                            true
                        }

                        else -> false
                    }
                } else false
            }
            .focusable()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 36.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(currentThumbnailUrl)
                        .crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier
                        .width(160.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    // ★ 修正: DIRECT画像が無くてエラーになった場合、自動的にAPI(fallback)に切り替える
                    onError = {
                        if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                            currentThumbnailUrl = fallbackUrl
                        }
                    }
                )
            }

            Text(
                text = program.title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayDate,
                color = colors.accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(text = program.channel?.name ?: "", color = colors.textSecondary, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(modifier = Modifier.alpha(0.1f), color = colors.textSecondary)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "番組概要",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            val descText = program.description.takeIf { it.isNotEmpty() } ?: "番組概要がありません"
            Text(
                text = descText,
                color = colors.textPrimary.copy(alpha = if (program.description.isEmpty()) 0.5f else 1f),
                lineHeight = 18.sp,
                fontSize = 13.sp
            )

            if (!program.detail.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                program.detail.forEach { (key, value) ->
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Text(
                        text = value,
                        color = colors.textPrimary,
                        lineHeight = 18.sp,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            } else if (program.description.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = colors.textSecondary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}