package com.example.komorebi.ui.program

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import java.time.Duration
import java.time.OffsetDateTime

@Composable
fun BroadcastingTypeTabs(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    tabFocusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    firstCellFocusRequester: FocusRequester,
    categories: List<String>,
    onJumpToDateClick: () -> Unit
) {
    val typeLabels = mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー")
    val jumpButtonFocusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 16.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
    ) {
        // 日時指定ボタン
        Surface(
            onClick = onJumpToDateClick,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(100.dp)
                .height(36.dp)
                .focusRequester(jumpButtonFocusRequester)
                .focusProperties { down = firstCellFocusRequester },
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = Color.Gray,
                focusedContentColor = Color.Black
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(text = "日時指定", fontSize = 15.sp)
            }
        }

        // 放送波タブ
        Row(
            modifier = Modifier.align(Alignment.Center).focusGroup(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { code ->
                val isSelected = selectedType == code
                Surface(
                    onClick = { onTypeSelected(code) },
                    modifier = Modifier
                        .width(100.dp)
                        .height(36.dp)
                        .padding(horizontal = 2.dp)
                        .then(if (isSelected) Modifier.focusRequester(tabFocusRequester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused && selectedType != code) onTypeSelected(code)
                        }
                        .focusProperties { down = firstCellFocusRequester },
                    shape = ClickableSurfaceDefaults.shape(RectangleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.White,
                        contentColor = if (isSelected) Color.White else Color.Gray,
                        focusedContentColor = Color.Black
                    )
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = typeLabels[code] ?: code,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CurrentTimeIndicator(baseTime: OffsetDateTime, totalWidth: Dp, scrollOffset: Int) {
    val config = LocalEpgConfig.current
    var nowOffsetMinutes by remember {
        mutableLongStateOf(Duration.between(baseTime, OffsetDateTime.now()).toMinutes())
    }

    LaunchedEffect(baseTime) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowOffsetMinutes = Duration.between(baseTime, OffsetDateTime.now()).toMinutes()
        }
    }

    val density = LocalDensity.current
    val absoluteLineOffsetDp = (nowOffsetMinutes * config.dpPerMinute).dp
    val scrollOffsetDp = with(density) { scrollOffset.toDp() }
    val finalOffset = absoluteLineOffsetDp - scrollOffsetDp

    // 画面外（上すぎる）ときは描画しない
    if (finalOffset > (-2).dp) {
        Box(modifier = Modifier.width(totalWidth).offset(y = finalOffset).zIndex(10f)) {
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.Red))
        }
    }
}