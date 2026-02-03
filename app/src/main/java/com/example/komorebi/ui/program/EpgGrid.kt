package com.example.komorebi.ui.program

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.komorebi.data.model.EpgChannelWrapper
import com.example.komorebi.data.model.EpgProgram
import com.example.komorebi.viewmodel.EpgViewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.OffsetDateTime

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EpgGrid(
    channels: List<EpgChannelWrapper>,
    baseTime: OffsetDateTime,
    viewModel: EpgViewModel,
    onProgramClick: (EpgProgram) -> Unit,
    firstCellFocusRequester: FocusRequester,
    tabFocusRequester: FocusRequester,
    skipAnimation: Boolean = false,
    lastFocusedId: String?,       // ★ 追加
    onFocusProgram: (String) -> Unit // ★ 追加
) {
    val config = LocalEpgConfig.current
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    var loadedChannelCount by remember(channels) {
        mutableIntStateOf(if (skipAnimation) channels.size else 9)
    }
    var isReadyToRender by remember(channels) { mutableStateOf(skipAnimation) }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    var lastFocusedProgramId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(channels, baseTime) {
        val now = OffsetDateTime.now()
        if (baseTime.toLocalDate() == now.toLocalDate()) {
            val minutesFromBase = Duration.between(baseTime, now).toMinutes()
            val targetScroll = ((minutesFromBase - 30) * config.dpPerMinute).dp
            verticalScrollState.scrollTo(with(density) { targetScroll.roundToPx() })
        }

        if (!skipAnimation) {
            delay(16)
            isReadyToRender = true
            while (loadedChannelCount < channels.size) {
                delay(16)
                loadedChannelCount = (loadedChannelCount + 10).coerceAtMost(channels.size)
            }
        } else {
            isReadyToRender = true
        }
    }

    if (!isReadyToRender) return

    Column(modifier = Modifier.fillMaxSize()) {
        // --- ヘッダー行 (日付 + 各局ロゴ) ---
        Row(modifier = Modifier.fillMaxWidth().zIndex(4f)) {
            // EpgHeaders.kt に定義されている関数名と引数に合わせる
            DateHeaderBox(baseTime, config.timeColumnWidth, config.headerHeight)

            Row(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                channels.forEachIndexed { index, wrapper ->
                    if (index < loadedChannelCount) {
                        // 【修正】ChannelHeaderCell -> EpgChannelHeader
                        EpgChannelHeader(
                            channel = wrapper.channel,
                            logoUrl = viewModel.getMirakurunLogoUrl(wrapper.channel)
                        )
                    } else {
                        Spacer(Modifier.width(config.channelWidth).height(config.headerHeight))
                    }
                }
            }
        }

        // --- メイン格子 (時間軸 + 番組表本体) ---
        Row(modifier = Modifier.fillMaxSize()) {
            // 左端の時間軸
            Column(modifier = Modifier
                .width(config.timeColumnWidth)
                .verticalScroll(verticalScrollState)
                .background(Color(0xFF111111))
                .zIndex(2f)) {
                // 【修正】TimeColumnContent -> EpgTimeColumn (EpgHeaders.kt に定義)
                EpgTimeColumn(baseTime)
            }

            // 右側の番組エリア
            Box(modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .horizontalScroll(horizontalScrollState)
                .focusGroup()) {
                Row {
                    channels.forEachIndexed { channelIndex, wrapper ->
                        if (channelIndex < loadedChannelCount) {
                            // 【修正】ProgramContainer ループ -> EpgChannelColumn 1つに任せる
                            // (EpgChannelColumn.kt に定義されているものを使用)
                            EpgChannelColumn(
                                channelWrapper = wrapper,
                                baseTime = baseTime,
                                channelIndex = channelIndex,
                                tabFocusRequester = tabFocusRequester,
                                firstCellFocusRequester = firstCellFocusRequester,
                                onProgramClick = onProgramClick,
                                vScrollPos = verticalScrollState.value,
                                screenHeightPx = screenHeightPx,
                                lastFocusedId = lastFocusedId,     // ★ そのまま渡す
                                onFocusProgram = onFocusProgram,    // ★ そのまま渡す
                                now = OffsetDateTime.now()
                            )
                        } else {
                            Spacer(Modifier.width(config.channelWidth).height(config.hourHeight * 24))
                        }
                    }
                }
                // 現在時刻の赤線 (EpgComponents.kt に定義)
                CurrentTimeIndicator(baseTime, config.channelWidth * channels.size, verticalScrollState.value)
            }
        }
    }
}