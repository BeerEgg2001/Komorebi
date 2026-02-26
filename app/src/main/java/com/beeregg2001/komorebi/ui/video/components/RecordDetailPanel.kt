package com.beeregg2001.komorebi.ui.video.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
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
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val scrollState = rememberScrollState()

    if (program == null) return

    val thumbnailUrl = UrlBuilder.getThumbnailUrl(konomiIp, konomiPort, program.id.toString())

    val displayDate = remember(program.startTime) {
        try {
            val zdt = ZonedDateTime.parse(program.startTime)
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) a h:mm", Locale.JAPANESE)
            zdt.format(formatter)
        } catch (e: Exception) {
            program.startTime.take(16).replace("-", "/")
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .focusRequester(focusRequester)
            .focusable()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(thumbnailUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .width(220.dp)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = program.title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            lineHeight = 26.sp,
            fontSize = 17.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = displayDate,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.accent,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Text(
            text = program.channel?.name ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(modifier = Modifier.alpha(0.1f), color = colors.textSecondary)
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "番組概要",
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = program.description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            lineHeight = 20.sp,
            fontSize = 14.sp
        )

        // ★追加: 番組詳細のループ表示
        if (!program.detail.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            program.detail.forEach { (key, value) ->
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    text = value,
                    color = colors.textPrimary,
                    lineHeight = 20.sp,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}