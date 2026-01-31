package com.example.komorebi.ui.program

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.data.util.EpgUtils

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramDetailModal(
    program: EpgProgram,
    onPrimaryAction: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val firstButtonFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val rightContentFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // 1. 戻るキーを確実にフックし、親の挙動をブロックする
//    BackHandler(enabled = true) {
//        onDismiss()
//    }

    // 2. 起動時に視聴ボタンへフォーカスを当てる
    LaunchedEffect(Unit) {
        firstButtonFocusRequester.requestFocus()
    }

    val forcedJapanStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )

    // Box で全画面を覆い、zIndex 1000f で最前面へ
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1000f)
            .background(Color(0xFF080808))
            // クリックを吸い取って背後の要素に反応させない
            .clickable(enabled = false) {}
    ) {
        // Surface を使って文字色(contentColor)を強制的に白にする
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            ),
            shape = androidx.compose.ui.graphics.RectangleShape
        ) {
            CompositionLocalProvider(
                LocalTextStyle provides forcedJapanStyle,
                LocalContentColor provides Color.White
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 70.dp, vertical = 60.dp)
                ) {
                    // --- 左カラム ---
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = program.majorGenre ?: "番組情報",
                            color = EpgUtils.getGenreColor(program.majorGenre),
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = program.title,
                            style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "${EpgUtils.formatTime(program.start_time)} 〜 ${EpgUtils.formatEndTime(program)}",
                            style = TextStyle(fontSize = 18.sp, color = Color.LightGray)
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        Row(modifier = Modifier.padding(bottom = 10.dp)) {
                            Button(
                                modifier = Modifier
                                    .focusRequester(firstButtonFocusRequester)
                                    .focusProperties { right = backButtonFocusRequester },
                                onClick = onPrimaryAction,
                                colors = ButtonDefaults.colors(containerColor = Color.White, contentColor = Color.Black),
                                shape = ButtonDefaults.shape(RoundedCornerShape(8.dp))
                            ) {
                                Text("視聴する", fontWeight = FontWeight.Bold)
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
                                onClick = { onDismiss() }, // ★確実に onDismiss を呼ぶ
                                border = ButtonDefaults.border(
                                    border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), shape = RoundedCornerShape(8.dp)),
                                    focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp))
                                ),
                                colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White),
                                shape = ButtonDefaults.shape(RoundedCornerShape(8.dp))
                            ) {
                                Text("戻る", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(90.dp))

                    // --- 右カラム ---
                    Column(modifier = Modifier.weight(1.3f)) {
                        Text(text = "番組詳細", style = TextStyle(fontSize = 14.sp, color = Color.Gray, letterSpacing = 2.sp))
                        Spacer(modifier = Modifier.height(20.dp))

                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(rightContentFocusRequester)
                                .focusProperties { left = backButtonFocusRequester },
                            onClick = {},
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = Color.White.copy(alpha = 0.05f)
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                Text(
                                    text = program.description,
                                    style = forcedJapanStyle.copy(fontSize = 18.sp, lineHeight = 34.sp),
                                    color = Color.White
                                )
                                if (!program.detail.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                                    Spacer(modifier = Modifier.height(32.dp))
                                    program.detail?.forEach { (key, value) ->
                                        Column(modifier = Modifier.padding(bottom = 24.dp)) {
                                            Text(text = key, style = forcedJapanStyle.copy(fontSize = 14.sp, color = EpgUtils.getGenreColor(program.majorGenre), fontWeight = FontWeight.Bold))
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(text = value, style = forcedJapanStyle.copy(fontSize = 16.sp, lineHeight = 26.sp, color = Color.LightGray))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(40.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}