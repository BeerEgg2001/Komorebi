package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.ui.theme.NotoSansJP
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramDetailScreen(
    program: EpgProgram,
    onPlayClick: (EpgProgram) -> Unit,
    onRecordClick: (EpgProgram) -> Unit,
    onBackClick: () -> Unit,
    initialFocusRequester: FocusRequester // 親から渡されるものに一本化
) {
    val now = OffsetDateTime.now()
    val startTime = OffsetDateTime.parse(program.start_time)
    val endTime = OffsetDateTime.parse(program.end_time)

    val isPast = endTime.isBefore(now)
    val isBroadcasting = now.isAfter(startTime) && now.isBefore(endTime)
    val isFuture = startTime.isAfter(now)

    // isReady は詳細情報の遅延表示用として維持
    var isReady by remember { mutableStateOf(false) }

    var isClickEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(program) {
        isClickEnabled = false // 初期値は無効
        yield()
        try {
            initialFocusRequester.requestFocus()
        } catch (e: Exception) {
            Log.e("Detail", "Focus request failed", e)
        }
        isReady = true

        // 300〜500ms程度待機してからクリックを有効にする
        delay(500)
        isClickEnabled = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .focusGroup()
    ) {
        // 背景装飾（左上から右下へのグラデーション）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF1E1E1E), Color.Black),
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
                if (isBroadcasting) {
                    Button(
                        onClick = { if (isClickEnabled) onPlayClick(program) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(initialFocusRequester) // 親からのRequesterを使用
                    ) {
                        Text("視聴する", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                    }
                } else if (isFuture) {
                    Button(
                        onClick = { onRecordClick(program) },
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(initialFocusRequester) // 予約ボタンがあるならここ
                    ) {
                        Text("録画予約（実装中）", fontFamily = NotoSansJP, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = {
                        // 連打によるクラッシュ防止：一度だけ実行されるように
                        if (isClickEnabled) onBackClick()

                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // ボタンが1つしかない（過去番組）場合は、ここがフォーカスを受け取る
                        .then(if (isPast) Modifier.focusRequester(initialFocusRequester) else Modifier)
                ) {
                    Text("戻る", fontFamily = NotoSansJP)
                }
            }

            Spacer(modifier = Modifier.width(56.dp))

            // --- 右側：番組詳細情報領域 ---
            Column(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .focusable()
            ) {
                // 放送時間・日付
                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) HH:mm")
                Text(
                    text = "${startTime.format(formatter)} ～ ${endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.LightGray,
                    fontFamily = NotoSansJP
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 番組タイトル
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        lineHeight = 46.sp
                    ),
                    color = Color.White,
                    fontFamily = NotoSansJP
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 番組説明（メイン）
                Text(
                    text = "番組概要",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontFamily = NotoSansJP,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = program.description ?: "説明はありません。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.LightGray,
                    fontFamily = NotoSansJP,
                    lineHeight = 28.sp
                )

                if (isReady) {
                    ProgramDetailedInfo(program)
                }

                // スクロール時に一番下が隠れないように余白を追加
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
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontFamily = NotoSansJP,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray,
                    fontFamily = NotoSansJP,
                    lineHeight = 24.sp
                )
            }
        }
    }
}