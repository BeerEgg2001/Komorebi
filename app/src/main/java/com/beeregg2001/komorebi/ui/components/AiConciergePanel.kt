@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.main.AiFocusTicket
import com.beeregg2001.komorebi.ui.main.AiFocusTicketManager
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AiConciergePanel(
    isOpen: Boolean,
    chatHistory: List<ChatMessage>,
    isSpeechSupported: Boolean,
    isRecording: Boolean,
    ticketManager: AiFocusTicketManager,
    apiKey: String,
    onGoToSettings: () -> Unit,
    onClose: () -> Unit,
    onMicLongPressStart: () -> Unit,
    onMicLongPressEnd: () -> Unit,
    onKeyboardClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val micFocusRequester = remember { FocusRequester() }
    val keyboardFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    val scrollState = rememberScrollState()
    var isScrollAreaFocused by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // ★ 修正: APIキー未設定の場合はタイピング処理をブロックしないように条件を追加
    var isGreetingTyping by remember(isOpen, apiKey) {
        mutableStateOf(isOpen && chatHistory.isEmpty() && apiKey.isNotBlank())
    }

    val defaultRequester = if (isSpeechSupported) micFocusRequester else keyboardFocusRequester

    // パネルが開いて、かつ履歴がある（2回目以降）の時の即時フォーカス復帰
    LaunchedEffect(isOpen) {
        if (isOpen && chatHistory.isNotEmpty()) {
            delay(150); defaultRequester.safeRequestFocusWithRetry("AiPanel_Open")
        }
    }

    // MainRootScreenからチケット（司令）が飛んできた時の確実な処理
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime, isOpen) {
        if (isOpen && ticketManager.currentTicket == AiFocusTicket.PANEL_DEFAULT) {
            delay(150)
            // 挨拶のタイピング中でなければ、指示通りフォーカスを戻す
            if (!isGreetingTyping) {
                defaultRequester.safeRequestFocusWithRetry("AiPanelDefault")
            }
            ticketManager.consume(AiFocusTicket.PANEL_DEFAULT)
        }
    }

    LaunchedEffect(chatHistory.size, isRecording) {
        delay(100); scrollState.animateScrollTo(scrollState.maxValue)
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        BackHandler(enabled = isOpen) { onClose() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.key == Key.Back || event.key == Key.Escape) return@onKeyEvent false
                    val isDpad = event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                            event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                            event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    isDpad
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.35f)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                colors.background.copy(alpha = 0.85f),
                                colors.background.copy(alpha = 0.98f)
                            )
                        )
                    )
                    .border(width = 1.dp, color = colors.textPrimary.copy(alpha = 0.1f))
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .focusGroup()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "AI",
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "AI コンシェルジュ",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                if (apiKey.isBlank()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "AIコンシェルジュを利用するには\nAPIキーの設定が必要です。",
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = onGoToSettings,
                            colors = ButtonDefaults.colors(
                                containerColor = colors.accent,
                                contentColor = if (colors.isDark) Color.Black else Color.White
                            ),
                            modifier = Modifier.focusRequester(defaultRequester) // パネル開閉時のデフォルトフォーカスをボタンに当てる
                        ) {
                            Text("設定画面へ進む", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .border(
                                width = 2.dp,
                                color = if (isScrollAreaFocused) colors.accent.copy(alpha = 0.6f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(contentFocusRequester)
                                .onFocusChanged {
                                    isScrollAreaFocused = it.isFocused || it.hasFocus
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && isScrollAreaFocused) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                if (scrollState.value > 0) {
                                                    coroutineScope.launch {
                                                        scrollState.animateScrollTo(
                                                            scrollState.value - 400
                                                        )
                                                    }
                                                    return@onKeyEvent true
                                                }
                                            }

                                            Key.DirectionDown -> {
                                                if (scrollState.value < scrollState.maxValue - 5) {
                                                    coroutineScope.launch {
                                                        scrollState.animateScrollTo(
                                                            scrollState.value + 400
                                                        )
                                                    }
                                                    return@onKeyEvent true
                                                } else {
                                                    runCatching { defaultRequester.requestFocus() }
                                                    return@onKeyEvent true
                                                }
                                            }
                                        }
                                    }
                                    false
                                }
                                .verticalScroll(scrollState)
                                .focusable()
                                .padding(8.dp)
                        ) {
                            if (chatHistory.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Start)
                                        .background(
                                            colors.textPrimary.copy(alpha = 0.05f),
                                            RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    val greetingText = if (isSpeechSupported) {
                                        "こんにちは！何かお手伝いできることはありますか？\n\n・マイクボタンを「長押し」して話す\n・文字入力ボタンでキーボードを使う\n\nどちらでも対応可能です！"
                                    } else {
                                        "こんにちは！何かお手伝いできることはありますか？\n\n文字入力ボタンからキーボードを開いて、質問や指示を入力してください！"
                                    }

                                    TypingText(
                                        text = greetingText,
                                        scrollState = scrollState,
                                        onTypingFinished = {
                                            coroutineScope.launch {
                                                delay(800)
                                                isGreetingTyping = false

                                                // エミュレータ（マイク非対応）の場合のみ、純粋にキーボードクリックのイベントを発火させる
                                                if (!isSpeechSupported) {
                                                    onKeyboardClick()
                                                } else {
                                                    // マイク対応端末は、何もしない（チケット処理に任せる）だけで自然にフォーカスが当たる
                                                    defaultRequester.safeRequestFocusWithRetry("Greeting_Finish_Fallback")
                                                }
                                            }
                                        }
                                    )
                                }
                            } else {
                                chatHistory.forEach { msg ->
                                    key(msg.id) {
                                        Box(
                                            modifier = Modifier
                                                .align(if (msg.isUser) Alignment.End else Alignment.Start)
                                                .background(
                                                    if (msg.isUser) colors.textPrimary.copy(alpha = 0.1f) else colors.accent.copy(
                                                        alpha = 0.15f
                                                    ),
                                                    if (msg.isUser) RoundedCornerShape(
                                                        16.dp,
                                                        16.dp,
                                                        4.dp,
                                                        16.dp
                                                    ) else RoundedCornerShape(
                                                        16.dp,
                                                        16.dp,
                                                        16.dp,
                                                        4.dp
                                                    )
                                                )
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                        ) {
                                            if (msg.isThinking) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    CircularProgressIndicator(
                                                        color = colors.accent,
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        "考え中...",
                                                        color = colors.textPrimary,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            } else {
                                                if (msg.isUser) {
                                                    Text(
                                                        msg.text,
                                                        color = colors.textPrimary,
                                                        fontSize = 14.sp
                                                    )
                                                } else {
                                                    val parsedText = parseSimpleMarkdown(msg.text)
                                                    TypingAnnotatedText(parsedText, scrollState)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (isRecording) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .background(
                                            colors.textPrimary.copy(alpha = 0.1f),
                                            RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = null,
                                            tint = Color(0xFFFF5252),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "録音中...",
                                            color = colors.textPrimary,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    var isMicLongPressHandled by remember { mutableStateOf(false) }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        // --- 音声入力ボタン ---
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                onClick = { /* クリック時は何もしない */ },
                                enabled = isSpeechSupported && !isGreetingTyping,
                                modifier = Modifier
                                    .size(44.dp)
                                    .focusRequester(micFocusRequester)
                                    .onPreviewKeyEvent { event ->
                                        val isCenterKey =
                                            event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                                        if (isCenterKey) {
                                            if (event.type == KeyEventType.KeyDown) {
                                                if (event.nativeKeyEvent.isLongPress && !isMicLongPressHandled) {
                                                    isMicLongPressHandled = true
                                                    onMicLongPressStart()
                                                    return@onPreviewKeyEvent true
                                                }
                                                if (isMicLongPressHandled) return@onPreviewKeyEvent true
                                            } else if (event.type == KeyEventType.KeyUp) {
                                                if (isMicLongPressHandled) {
                                                    isMicLongPressHandled = false
                                                    onMicLongPressEnd()
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                        }

                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionUp -> {
                                                    runCatching { contentFocusRequester.requestFocus() }
                                                    return@onPreviewKeyEvent true
                                                }

                                                Key.DirectionRight -> {
                                                    runCatching { keyboardFocusRequester.requestFocus() }
                                                    return@onPreviewKeyEvent true
                                                }
                                            }
                                        }
                                        false
                                    },
                                shape = ClickableSurfaceDefaults.shape(CircleShape),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isRecording) Color(0xFFFF5252) else if (isSpeechSupported) colors.accent.copy(
                                        alpha = 0.2f
                                    ) else colors.textPrimary.copy(alpha = 0.05f),
                                    focusedContainerColor = if (isRecording) Color(0xFFFF5252) else colors.accent,
                                    disabledContainerColor = colors.textPrimary.copy(alpha = 0.05f),
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = "音声",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (isSpeechSupported) Color.White else colors.textPrimary.copy(
                                            alpha = 0.3f
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isSpeechSupported) "音声入力(長押し)" else "非対応",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = colors.textSecondary
                            )
                        }

                        // --- 文字入力ボタン ---
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                onClick = { if (!isGreetingTyping) onKeyboardClick() },
                                enabled = !isGreetingTyping,
                                modifier = Modifier
                                    .size(44.dp)
                                    .focusRequester(keyboardFocusRequester)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionUp -> {
                                                    runCatching { contentFocusRequester.requestFocus() }
                                                    return@onPreviewKeyEvent true
                                                }

                                                Key.DirectionLeft -> {
                                                    if (isSpeechSupported) {
                                                        runCatching { micFocusRequester.requestFocus() }
                                                        return@onPreviewKeyEvent true
                                                    }
                                                }
                                            }
                                        }
                                        false
                                    },
                                shape = ClickableSurfaceDefaults.shape(CircleShape),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                    focusedContainerColor = colors.textPrimary
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Default.Keyboard,
                                        contentDescription = "文字入力",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (colors.isDark) Color.Black else Color.White
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "文字入力",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TypingText(text: String, scrollState: ScrollState, onTypingFinished: () -> Unit = {}) {
    var displayedText by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        displayedText = ""
        for (i in text.indices) {
            displayedText += text[i]
            delay(15)
            scrollState.scrollTo(scrollState.maxValue)
        }
        onTypingFinished()
    }
    Text(
        text = displayedText,
        color = KomorebiTheme.colors.textPrimary,
        fontSize = 14.sp,
        lineHeight = 22.sp
    )
}

@Composable
fun parseSimpleMarkdown(text: String): AnnotatedString {
    val colors = KomorebiTheme.colors

    val listProcessed = text.replace(Regex("(?m)^[\\*\\-]\\s+"), "• ")

    return buildAnnotatedString {
        val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
        var lastIndex = 0

        boldRegex.findAll(listProcessed).forEach { matchResult ->
            append(listProcessed.substring(lastIndex, matchResult.range.first))

            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = colors.accent)) {
                append(matchResult.groupValues[1])
            }
            lastIndex = matchResult.range.last + 1
        }
        append(listProcessed.substring(lastIndex))
    }
}

@Composable
fun TypingAnnotatedText(
    annotatedString: AnnotatedString,
    scrollState: ScrollState,
    onTypingFinished: () -> Unit = {}
) {
    var length by remember { mutableIntStateOf(0) }

    LaunchedEffect(annotatedString) {
        length = 0
        for (i in 0..annotatedString.length) {
            length = i
            delay(15)
            scrollState.scrollTo(scrollState.maxValue)
        }
        onTypingFinished()
    }

    Text(
        text = annotatedString.subSequence(0, length),
        color = KomorebiTheme.colors.textPrimary,
        fontSize = 14.sp,
        lineHeight = 22.sp
    )
}