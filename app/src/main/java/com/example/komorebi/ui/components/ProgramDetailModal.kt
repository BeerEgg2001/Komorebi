package com.example.komorebi.ui.program

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.util.EpgUtils
import com.example.komorebi.ui.theme.NotoSansJP
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramDetailModal(
    program: EpgProgram,
    onPrimaryAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val firstButtonFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val rightContentFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // 誤爆防止: 表示から一定時間は全ての入力を受け付けない
    var isInputLocked by remember { mutableStateOf(true) }

    // 放送中判定
    val isLive = remember(program) {
        val now = OffsetDateTime.now()
        val start = OffsetDateTime.parse(program.start_time)
        val end = start.plusSeconds(program.duration.toLong())
        now.isAfter(start) && now.isBefore(end)
    }

    // 表示時の初期化処理
    LaunchedEffect(Unit) {
        // 500msロックすることで、前の画面の「決定キー」の残響を完全に無視させる
        delay(500)
        isInputLocked = false
        // ロック解除後にフォーカスを当てる
        firstButtonFocusRequester.requestFocus()
    }

    val subTextColor = Color.Gray
    val mainTextColor = Color.White
    val forcedJapanStyle = TextStyle(
        fontFamily = NotoSansJP,
        fontWeight = FontWeight.Medium,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1000f)
            .background(Color(0xFF080808))
            // 背面のクリックイベント等を完全に遮断
            .pointerInput(Unit) {}
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 70.dp, vertical = 60.dp)
        ) {
            // --- 左カラム (操作エリア) ---
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = program.majorGenre ?: "番組情報",
                    color = subTextColor,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = program.title,
                    style = forcedJapanStyle.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp),
                    color = mainTextColor
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${EpgUtils.formatTime(program.start_time)} 〜 ${EpgUtils.formatEndTime(program)}",
                    style = forcedJapanStyle.copy(fontSize = 18.sp, color = Color.LightGray)
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(modifier = Modifier.padding(bottom = 10.dp)) {
                    // 視聴する / 録画予約ボタン
                    Button(
                        modifier = Modifier
                            .focusRequester(firstButtonFocusRequester)
                            .focusProperties { right = backButtonFocusRequester },
                        // 入力ロック中は enabled = false にして波紋エフェクトすら出さない
                        enabled = isLive && !isInputLocked,
                        onClick = { if (!isInputLocked) onPrimaryAction(program.channel_id) },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isLive) Color.White else Color(0xFF222222),
                            contentColor = if (isLive) Color.Black else Color.Gray,
                            disabledContainerColor = if (isLive && isInputLocked) Color.White.copy(0.5f) else Color(0xFF151515),
                            disabledContentColor = Color.Gray
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = if (isLive) "視聴する" else "録画予約（準備中）",
                            style = forcedJapanStyle.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    // 戻るボタン
                    Button(
                        modifier = Modifier
                            .focusRequester(backButtonFocusRequester)
                            .focusProperties {
                                left = firstButtonFocusRequester
                                right = rightContentFocusRequester
                            },
                        enabled = !isInputLocked,
                        onClick = { if (!isInputLocked) onDismiss() },
                        border = ButtonDefaults.border(
                            border = Border(BorderStroke(1.dp, Color.White.copy(0.5f)), shape = RoundedCornerShape(8.dp)),
                            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp))
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp))
                    ) {
                        Text(text = "戻る", style = forcedJapanStyle.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }

            Spacer(modifier = Modifier.width(90.dp))

            // --- 右カラム (番組詳細・スクロールエリア) ---
            Column(modifier = Modifier.weight(1.3f)) {
                Text(
                    text = "番組詳細 (上下キーでスクロール)",
                    style = forcedJapanStyle.copy(fontSize = 14.sp, color = subTextColor)
                )
                Spacer(modifier = Modifier.height(20.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(rightContentFocusRequester)
                        .focusProperties { left = backButtonFocusRequester }
                        // DPAD上下キーを検知してスクロール位置を強制操作する
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.nativeKeyEvent.action == NativeKeyEvent.ACTION_DOWN) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        coroutineScope.launch { scrollState.scrollBy(150f) }
                                        true
                                    }
                                    NativeKeyEvent.KEYCODE_DPAD_UP -> {
                                        coroutineScope.launch { scrollState.scrollBy(-150f) }
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        },
                    onClick = { /* フォーカス取得用ダミークリック */ },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(0.05f),
                        focusedContainerColor = Color.White.copy(0.12f)
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(12.dp))
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = program.description,
                            style = forcedJapanStyle.copy(fontSize = 18.sp, lineHeight = 34.sp),
                            color = mainTextColor
                        )

                        if (!program.detail.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(32.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.1f)))
                            Spacer(modifier = Modifier.height(32.dp))

                            program.detail?.forEach { (key, value) ->
                                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                                    Text(
                                        text = key,
                                        style = forcedJapanStyle.copy(fontSize = 14.sp, color = subTextColor, fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = value,
                                        style = forcedJapanStyle.copy(fontSize = 16.sp, lineHeight = 26.sp, color = mainTextColor)
                                    )
                                }
                            }
                        }
                        // スクロール末尾の余白
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }
            }
        }
    }
}