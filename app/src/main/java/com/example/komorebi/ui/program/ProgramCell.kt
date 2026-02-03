package com.example.komorebi.ui.program

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.util.EpgUtils
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ProgramCell(
    program: EpgProgram,
    baseTime: OffsetDateTime,
    canGoUpToTab: Boolean,
    focusRequester: FocusRequester?,
    tabFocusRequester: FocusRequester,
    onProgramClick: (EpgProgram) -> Unit,
    vScrollPos: Int,
    isLastFocused: Boolean = false,
    onFocused: (String) -> Unit
) {
    val config = LocalEpgConfig.current
    val density = LocalDensity.current

    val internalFocusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var contentHeightDp by remember { mutableStateOf(0.dp) }

    // ★ 追加: レイアウトが確定（アタッチ）されたかどうかのフラグ
    var isAttached by remember { mutableStateOf(false) }

    // フォーカス復旧ロジック
    LaunchedEffect(isLastFocused, isAttached) {
        if (isLastFocused && isAttached) {
            try {
                // アタッチ後、さらに少し待ってから要求
                delay(150)
                internalFocusRequester.requestFocus()
            } catch (e: Exception) {
                android.util.Log.e("ProgramCell", "Focus recovery failed: ${program.title}")
            }
        }
    }

    val cellData = remember(program.id, baseTime) {
        val startTime = OffsetDateTime.parse(program.start_time)
        val minutesFromBase = Duration.between(baseTime, startTime).toMinutes()
        val durationMin = program.duration / 60
        object {
            val top = (minutesFromBase * config.dpPerMinute).dp
            val height = (durationMin * config.dpPerMinute).dp
            val startTimeStr = EpgUtils.formatTime(program.start_time)
            val genreColor = EpgUtils.getGenreColor(program.majorGenre)
            val isPast = startTime.plusSeconds(program.duration.toLong()).isBefore(OffsetDateTime.now())
        }
    }

    val stickyPaddingDp = remember(vScrollPos, cellData.top) {
        val topPx = with(density) { cellData.top.toPx() }
        val heightPx = with(density) { cellData.height.toPx() }
        if (vScrollPos > topPx) {
            val diffPx = vScrollPos - topPx
            val maxPushPx = heightPx * 0.8f
            with(density) { diffPx.coerceAtMost(maxPushPx).toDp() }
        } else {
            0.dp
        }
    }

    val expansionAmount = if (isFocused) {
        (contentHeightDp - cellData.height).coerceAtLeast(0.dp)
    } else 0.dp

    Box(
        modifier = Modifier
            .offset(y = cellData.top)
            .width(config.channelWidth)
            .height(cellData.height)
            .zIndex(if (isFocused) 100f else 1f)
            // ★ 重要修正1: 準備ができるまでキーイベントを完全に遮断する
            .onKeyEvent { !isAttached }
            // ★ 重要修正2: レイアウトが配置されたらフラグを立てる
            .onGloballyPositioned { isAttached = true }
            // ★ 重要修正3: focusRequesterを適用するタイミングをisAttachedに同期させる
            .then(if (isAttached) Modifier.focusRequester(internalFocusRequester) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onFocused(program.id)
                }
            }
            .focusProperties {
                if (canGoUpToTab) {
                    up = tabFocusRequester
                }
            }
            // ★ 重要修正4: アタッチされるまでシステムフォーカス検索の対象外にする
            .focusable(enabled = isAttached)
            .clickable(enabled = isAttached) { onProgramClick(program) }
            .graphicsLayer { clip = false }
            .drawBehind {
                val fullHeight = size.height + expansionAmount.toPx()
                val bgColor = if (cellData.isPast) Color(0xFF151515) else Color(0xFF222222)
                val borderColor = Color(0xFF333333)
                drawRect(color = bgColor, size = size.copy(height = fullHeight))
                drawRect(
                    color = if (cellData.isPast) Color.Gray else cellData.genreColor,
                    size = size.copy(width = 3.dp.toPx(), height = fullHeight)
                )
                drawLine(borderColor, Offset(size.width, 0f), Offset(size.width, fullHeight), 1.dp.toPx())
                drawLine(borderColor, Offset(0f, fullHeight), Offset(size.width, fullHeight), 1.dp.toPx())
                if (isFocused) {
                    drawRect(
                        color = Color.White,
                        style = Stroke(width = 2.dp.toPx()),
                        size = size.copy(height = fullHeight)
                    )
                }
            }
    ) {
        if (cellData.height > 12.dp || isFocused) {
            Column(
                modifier = Modifier
                    .width(config.channelWidth)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .onGloballyPositioned { coords ->
                        if (isFocused) {
                            contentHeightDp = with(density) { coords.size.height.toDp() }
                        }
                    }
                    .padding(start = 8.dp, top = 2.dp + stickyPaddingDp, end = 6.dp, bottom = 4.dp)
            ) {
                val textAlpha = if (cellData.isPast) 0.5f else 1.0f
                Text(
                    text = cellData.startTimeStr,
                    fontSize = 9.sp,
                    color = Color.LightGray.copy(alpha = textAlpha),
                    maxLines = 1
                )
                Text(
                    text = program.title,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = textAlpha),
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = if (isFocused) 10 else 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )
                if (isFocused || cellData.height > 60.dp) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = program.description ?: "",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = if (isFocused) 0.8f else 0.5f * textAlpha),
                        lineHeight = 11.sp,
                        maxLines = if (isFocused) 10 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}