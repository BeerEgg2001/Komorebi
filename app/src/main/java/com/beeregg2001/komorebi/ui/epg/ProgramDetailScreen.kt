@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class ProgramDetailMode {
    EPG, // 通常の番組表詳細
    RESERVE // 予約確認・削除モード
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramDetailScreen(
    program: EpgProgram,
    // モード指定 (デフォルトはEPG)
    mode: ProgramDetailMode = ProgramDetailMode.EPG,
    onPlayClick: (EpgProgram) -> Unit = {},
    onRecordClick: (EpgProgram) -> Unit = {},
    // 予約削除時のアクション
    onDeleteReserveClick: (EpgProgram) -> Unit = {},
    onBackClick: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    val now = OffsetDateTime.now()
    val startTime = try { OffsetDateTime.parse(program.start_time) } catch (e: Exception) { now }
    val endTime = try { OffsetDateTime.parse(program.end_time) } catch (e: Exception) { now }

    val isPast = endTime.isBefore(now)
    val isBroadcasting = now.isAfter(startTime) && now.isBefore(endTime)
    val isFuture = startTime.isAfter(now)

    var isReady by remember { mutableStateOf(false) }
    var isClickEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(program) {
        isClickEnabled = false
        yield()
        delay(100)
        initialFocusRequester.safeRequestFocus("ProgramDetail")
        isReady = true
        delay(400)
        isClickEnabled = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .focusGroup()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(colors = listOf(Color(0xFF1E1E1E), Color.Black)))
        )

        Row(modifier = Modifier.fillMaxSize().padding(48.dp)) {
            Column(
                modifier = Modifier.weight(0.3f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // --- モードによるボタンの出し分け ---
                if (mode == ProgramDetailMode.RESERVE) {
                    // 予約削除モード
                    Button(
                        onClick = { if (isClickEnabled) onDeleteReserveClick(program) },
                        colors = ButtonDefaults.colors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester)
                    ) {
                        Text("予約を削除", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // 通常EPGモード
                    if (isBroadcasting) {
                        Button(
                            onClick = { if (isClickEnabled) onPlayClick(program) },
                            modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester)
                        ) {
                            Text("視聴する", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                        }
                    } else if (isFuture) {
                        Button(
                            onClick = { onRecordClick(program) },
                            enabled = false, // EPGからの予約は別途実装
                            modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester)
                        ) {
                            Text("録画予約（実装中）", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { if (isClickEnabled) onBackClick() },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (isPast && mode == ProgramDetailMode.EPG) Modifier.focusRequester(initialFocusRequester) else Modifier)
                ) {
                    Text("戻る", fontFamily = NotoSansJP)
                }
            }

            Spacer(modifier = Modifier.width(56.dp))

            val scrollState = rememberScrollState()
            val coroutineScope = rememberCoroutineScope()

            Column(
                modifier = Modifier
                    .weight(0.7f).fillMaxHeight()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.value + 300) }
                                    true
                                }
                                Key.DirectionUp -> {
                                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.value - 300) }
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .focusable()
                    .verticalScroll(scrollState)
            ) {
                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) HH:mm")
                Text(
                    text = "${startTime.format(formatter)} ～ ${endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = MaterialTheme.typography.labelLarge, color = Color.LightGray, fontFamily = NotoSansJP
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, lineHeight = 46.sp),
                    color = Color.White, fontFamily = NotoSansJP
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text("番組概要", style = MaterialTheme.typography.titleMedium, color = Color.White, fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = program.description ?: "説明はありません。",
                    style = MaterialTheme.typography.bodyLarge, color = Color.LightGray, fontFamily = NotoSansJP, lineHeight = 28.sp
                )

                if (isReady) { ProgramDetailedInfo(program) }
                Spacer(modifier = Modifier.height(60.dp))
            }
        }
    }
}

@Composable
fun ProgramDetailedInfo(program: EpgProgram) {
    Column {
        program.detail?.forEach { (label, content) ->
            if (content.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = label, style = MaterialTheme.typography.titleMedium, color = Color.White, fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = content, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray, fontFamily = NotoSansJP, lineHeight = 24.sp)
            }
        }
    }
}