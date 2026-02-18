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
    RESERVE // 予約リストからの確認モード
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramDetailScreen(
    program: EpgProgram,
    mode: ProgramDetailMode = ProgramDetailMode.EPG,
    isReserved: Boolean = false,
    onPlayClick: (EpgProgram) -> Unit = {},
    // 通常予約（即時）
    onRecordClick: (EpgProgram) -> Unit = {},
    // 詳細予約（設定ダイアログ表示）
    onRecordDetailClick: (EpgProgram) -> Unit = {},
    // 予約設定変更
    onEditReserveClick: (EpgProgram) -> Unit = {},
    // 予約削除
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

    // カラー定義：モノトーンに合う「録画」を象徴する赤色
    val recordRed = Color(0xFFC62828)      // メインの録画用赤色 (Material Red 800)
    val recordDarkRed = Color(0xFF421C1C)  // 詳細設定用：落ち着いた暗い赤色

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
                if (mode == ProgramDetailMode.RESERVE) {
                    // 予約リストモード (基本は設定変更と削除)
                    Button(
                        onClick = { if (isClickEnabled) onEditReserveClick(program) },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester)
                    ) {
                        Text("予約設定変更", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { if (isClickEnabled) onDeleteReserveClick(program) },
                        colors = ButtonDefaults.colors(containerColor = recordRed, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("予約を削除", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // EPGモード
                    if (isBroadcasting) {
                        Button(
                            onClick = { if (isClickEnabled) onPlayClick(program) },
                            modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester)
                        ) {
                            Text("視聴する", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                        }

                        if (isReserved) {
                            Button(
                                onClick = { if (isClickEnabled) onEditReserveClick(program) },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.1f),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("予約設定変更", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { if (isClickEnabled) onDeleteReserveClick(program) },
                                colors = ButtonDefaults.colors(containerColor = recordRed, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("予約を削除", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { if (isClickEnabled) onRecordClick(program) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.colors(containerColor = recordRed, contentColor = Color.White)
                            ) {
                                Text("録画する", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                            }
                            // 詳細設定ボタン：メインの赤より一段階暗くし、モノトーンに馴染ませる
                            Button(
                                onClick = { if (isClickEnabled) onRecordDetailClick(program) },
                                colors = ButtonDefaults.colors(containerColor = recordDarkRed, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("録画する（詳細設定）", fontFamily = NotoSansJP)
                            }
                        }

                    } else if (isFuture) {
                        if (isReserved) {
                            Button(
                                onClick = { if (isClickEnabled) onEditReserveClick(program) },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.1f),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester)
                            ) {
                                Text("予約設定変更", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { if (isClickEnabled) onDeleteReserveClick(program) },
                                colors = ButtonDefaults.colors(containerColor = recordRed, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("予約を削除", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { if (isClickEnabled) onRecordClick(program) },
                                modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester),
                                colors = ButtonDefaults.colors(containerColor = recordRed, contentColor = Color.White)
                            ) {
                                Text("録画予約する", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                            }
                            // 詳細設定ボタン
                            Button(
                                onClick = { if (isClickEnabled) onRecordDetailClick(program) },
                                colors = ButtonDefaults.colors(containerColor = recordDarkRed, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("録画予約（詳細設定）", fontFamily = NotoSansJP)
                            }
                        }
                    } else {
                        // 終了した番組
                        Button(
                            onClick = {},
                            enabled = false,
                            colors = ButtonDefaults.colors(containerColor = Color.Gray.copy(0.3f), contentColor = Color.LightGray),
                            modifier = Modifier.fillMaxWidth().focusRequester(initialFocusRequester)
                        ) {
                            Text("終了した番組", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
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