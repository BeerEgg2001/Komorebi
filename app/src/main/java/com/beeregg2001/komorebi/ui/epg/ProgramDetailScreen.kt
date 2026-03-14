@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
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
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.reserve.EpgReserveDialog
import com.beeregg2001.komorebi.util.TitleNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class ProgramDetailMode {
    EPG, RESERVE
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramDetailScreen(
    program: EpgProgram,
    mode: ProgramDetailMode = ProgramDetailMode.EPG,
    isReserved: Boolean = false,
    isReadOnly: Boolean = false,
    onPlayClick: (EpgProgram) -> Unit = {},
    onRecordClick: (EpgProgram) -> Unit = {},
    onRecordDetailClick: (EpgProgram) -> Unit = {},
    // ★修正: 引数を大幅に追加して詳細設定を受け取れるように
    onEpgReserveClick: (
        program: EpgProgram, keyword: String, daysOfWeek: Set<Int>, startHour: Int, startMin: Int, endHour: Int, endMin: Int,
        excludeKeyword: String, isTitleOnly: Boolean, broadcastType: String,
        isFuzzySearch: Boolean, duplicateScope: String, priority: Int,
        isEventRelay: Boolean, isExactRecord: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onEditReserveClick: (EpgProgram) -> Unit = {},
    onDeleteReserveClick: (EpgProgram) -> Unit = {},
    onBackClick: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    val now = OffsetDateTime.now()
    val startTime = try {
        OffsetDateTime.parse(program.start_time)
    } catch (e: Exception) {
        now
    }
    val endTime = try {
        OffsetDateTime.parse(program.end_time)
    } catch (e: Exception) {
        now
    }
    val colors = KomorebiTheme.colors

    val isPast = endTime.isBefore(now)
    val isBroadcasting = now.isAfter(startTime) && now.isBefore(endTime)
    val isFuture = startTime.isAfter(now)

    val recordRed = Color(0xFFC62828)
    val recordDarkRed = Color(0xFF421C1C)
    val seriesReserveOrange = Color(0xFFE65100)

    var isReady by remember { mutableStateOf(false) }
    var isClickEnabled by remember { mutableStateOf(false) }
    var showEpgReserveDialog by remember { mutableStateOf(false) }

    val epgReserveButtonRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(program) {
        isClickEnabled = false
        yield()
        delay(100)
        initialFocusRequester.safeRequestFocus("ProgramDetail")
        isReady = true
        delay(400)
        isClickEnabled = true
    }

    BackHandler(enabled = isClickEnabled) {
        if (showEpgReserveDialog) {
            showEpgReserveDialog = false
            coroutineScope.launch {
                delay(100)
                epgReserveButtonRequester.safeRequestFocus("ProgramDetail")
            }
        } else {
            onBackClick()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .focusGroup()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(colors.surface, colors.background)
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (!isReadOnly) {
                    if (mode == ProgramDetailMode.RESERVE) {
                        Button(
                            onClick = { if (isClickEnabled) onEditReserveClick(program) },
                            colors = ButtonDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                contentColor = colors.textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(initialFocusRequester)
                        ) {
                            Text(
                                "予約設定変更",
                                fontFamily = NotoSansJP,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { if (isClickEnabled) onDeleteReserveClick(program) },
                            colors = ButtonDefaults.colors(
                                containerColor = recordRed,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "予約を削除",
                                fontFamily = NotoSansJP,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        if (isBroadcasting) {
                            Button(
                                onClick = { if (isClickEnabled) onPlayClick(program) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(initialFocusRequester),
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.textPrimary,
                                    contentColor = if (colors.isDark) Color.Black else Color.White
                                )
                            ) {
                                Text(
                                    "視聴する",
                                    fontFamily = NotoSansJP,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (isReserved) {
                                Button(
                                    onClick = { if (isClickEnabled) onEditReserveClick(program) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                        contentColor = colors.textPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "予約設定変更",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = { if (isClickEnabled) onDeleteReserveClick(program) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = recordRed,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "予約を削除",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { if (isClickEnabled) onRecordClick(program) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.colors(
                                        containerColor = recordRed,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(
                                        "録画する",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = { if (isClickEnabled) showEpgReserveDialog = true },
                                    colors = ButtonDefaults.colors(
                                        containerColor = seriesReserveOrange,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(epgReserveButtonRequester)
                                ) {
                                    Text(
                                        "EPG予約する",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = { if (isClickEnabled) onRecordDetailClick(program) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = recordDarkRed,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("録画する（詳細設定）", fontFamily = NotoSansJP) }
                            }

                        } else if (isFuture) {
                            if (isReserved) {
                                Button(
                                    onClick = { if (isClickEnabled) onEditReserveClick(program) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                        contentColor = colors.textPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(initialFocusRequester)
                                ) {
                                    Text(
                                        "予約設定変更",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = { if (isClickEnabled) onDeleteReserveClick(program) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = recordRed,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "予約を削除",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { if (isClickEnabled) onRecordClick(program) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(initialFocusRequester),
                                    colors = ButtonDefaults.colors(
                                        containerColor = recordRed,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(
                                        "録画予約する",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = { if (isClickEnabled) showEpgReserveDialog = true },
                                    colors = ButtonDefaults.colors(
                                        containerColor = seriesReserveOrange,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(epgReserveButtonRequester)
                                ) {
                                    Text(
                                        "EPG予約する",
                                        fontFamily = NotoSansJP,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Button(
                                    onClick = { if (isClickEnabled) onRecordDetailClick(program) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = recordDarkRed,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("録画予約（詳細設定）", fontFamily = NotoSansJP) }
                            }
                        } else {
                            Button(
                                onClick = {},
                                enabled = false,
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.textSecondary.copy(0.3f),
                                    contentColor = colors.textSecondary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(initialFocusRequester)
                            ) {
                                Text(
                                    "終了した番組",
                                    fontFamily = NotoSansJP,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { if (isClickEnabled) onBackClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isReadOnly || (isPast && mode == ProgramDetailMode.EPG)) Modifier.focusRequester(
                                initialFocusRequester
                            ) else Modifier
                        ),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = colors.textPrimary,
                        focusedContainerColor = colors.textPrimary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.5f))),
                        focusedBorder = Border(BorderStroke(2.dp, colors.accent))
                    )
                ) { Text("戻る", fontFamily = NotoSansJP) }
            }

            Spacer(modifier = Modifier.width(56.dp))

            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.value + 300) }; true
                                }

                                Key.DirectionUp -> {
                                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.value - 300) }; true
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
                    text = "${startTime.format(formatter)} ～ ${
                        endTime.format(
                            DateTimeFormatter.ofPattern(
                                "HH:mm"
                            )
                        )
                    }",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                    fontFamily = NotoSansJP
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        lineHeight = 46.sp
                    ),
                    color = colors.textPrimary, fontFamily = NotoSansJP
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    "番組概要",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontFamily = NotoSansJP,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = program.description ?: "説明はありません。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    fontFamily = NotoSansJP,
                    lineHeight = 28.sp
                )

                if (isReady) {
                    ProgramDetailedInfo(program)
                }
                Spacer(modifier = Modifier.height(60.dp))
            }
        }

        if (showEpgReserveDialog) {
            EpgReserveDialog(
                initialKeyword = TitleNormalizer.extractDisplayTitle(program.title),
                initialStartTime = startTime,
                initialEndTime = endTime,
                // ★修正: 引数を追加
                onConfirm = { keyword, daysOfWeek, sH, sM, eH, eM, exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->
                    showEpgReserveDialog = false
                    onEpgReserveClick(
                        program,
                        keyword,
                        daysOfWeek,
                        sH,
                        sM,
                        eH,
                        eM,
                        exc,
                        tOnly,
                        bType,
                        fuzzy,
                        dup,
                        pri,
                        relay,
                        exact
                    )
                },
                onDismiss = {
                    showEpgReserveDialog = false
                    coroutineScope.launch {
                        delay(100)
                        epgReserveButtonRequester.safeRequestFocus("ProgramDetail")
                    }
                }
            )
        }
    }
}

@Composable
fun ProgramDetailedInfo(program: EpgProgram) {
    val colors = KomorebiTheme.colors
    Column {
        program.detail?.forEach { (label, content) ->
            if (content.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontFamily = NotoSansJP,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    fontFamily = NotoSansJP,
                    lineHeight = 24.sp
                )
            }
        }
    }
}