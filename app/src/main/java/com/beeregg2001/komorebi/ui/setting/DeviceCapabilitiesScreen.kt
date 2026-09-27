@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

// 端末の再生能力を表示する画面。設定の「アプリ情報」から開く。
//
// 参考実装: Honorebi (makeding/Honorebi, h-dev) の `ui/setting/DeviceCapabilitiesScreen.kt`。
// HEVC のみを扱っていた同実装に対し、Komorebi では地上波(MPEG-2)の直接再生判定と、
// コーデック別(MPEG-2 / H.264 / HEVC / AV1)の詳細表示を追加している。

private const val TAG = "DeviceCapabilitiesScreen"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DeviceCapabilitiesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 端末の能力は実行中に変化しないため、画面を開いたときに一度だけ検出する。
    val report = remember { DeviceCapabilityDetector.detect(context) }
    val colors = KomorebiTheme.colors
    val closeRequester = remember { FocusRequester() }
    val detailScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, remember { LocalTime.now() })

    LaunchedEffect(Unit) {
        delay(120)
        closeRequester.safeRequestFocus(TAG)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .background(backgroundBrush)
            .padding(horizontal = 56.dp, vertical = 38.dp)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    // 検出結果は縦に長くなるため、上下キーを閉じるボタンのフォーカス移動ではなく
                    // 右カードのスクロールに割り当てる。
                    Key.DirectionDown -> {
                        if (detailScrollState.canScrollForward) {
                            scope.launch {
                                detailScrollState.animateScrollTo(
                                    (detailScrollState.value + 220).coerceAtMost(detailScrollState.maxValue)
                                )
                            }
                        }
                        true
                    }

                    Key.DirectionUp -> {
                        if (detailScrollState.canScrollBackward) {
                            scope.launch {
                                detailScrollState.animateScrollTo(
                                    (detailScrollState.value - 220).coerceAtLeast(0)
                                )
                            }
                        }
                        true
                    }

                    Key.Back, Key.Escape -> {
                        onBack()
                        true
                    }

                    else -> false
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = colors.accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    "テレビ再生能力",
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text("${report.deviceName} / ${report.androidVersion}", color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            CapabilityCard("放送の直接再生", Modifier.weight(0.9f).fillMaxHeight()) {
                CapabilityVerdict(
                    title = "地上波・BS・CS (MPEG-2)",
                    supported = report.supportsBroadcastDirect,
                    supportedText = "直接再生できる見込みです",
                    unsupportedText = "MPEG-2 デコーダーが見つかりません"
                )
                Spacer(Modifier.height(22.dp))
                CapabilityVerdict(
                    title = "BS4K (3840x2160 / HEVC Main10)",
                    supported = report.supportsBs4kDirect,
                    supportedText = "直接再生できる見込みです",
                    unsupportedText = "直接再生の要件を満たしていません"
                )
                Spacer(Modifier.height(22.dp))
                CapabilityVerdict(
                    title = "BS8K (7680x4320 / HEVC Main10)",
                    supported = report.supportsBs8kDirect,
                    supportedText = "直接再生できる見込みです",
                    unsupportedText = "このテレビでは直接再生できません"
                )

                Spacer(Modifier.height(20.dp))
                if (!report.supportsBs4kDirect) {
                    Text(
                        "BS4K が非対応の場合は、サーバー側で変換した画質を選んでください。",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    "ここに表示されるのは端末が申告している対応状況です。同時に使えるデコーダー数の上限などにより、実際の再生結果と一致しないことがあります。",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            CapabilityCard("検出結果", Modifier.weight(1.1f).fillMaxHeight()) {
                Column(
                    modifier = Modifier.verticalScroll(detailScrollState),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    report.videoCodecs.forEach { codec ->
                        CodecSection(codec)
                    }

                    SectionTitle("ディスプレイ・音声")
                    CapabilityDetail(
                        "ディスプレイ HDR",
                        report.hdrTypes.ifEmpty { listOf("未検出") }.joinToString(" / ")
                    )
                    CapabilityDetail(
                        "音声出力の申告",
                        report.maxReportedAudioChannels?.let { "最大 ${it}ch" } ?: "不明"
                    )
                    CapabilityDetail(
                        "音声パススルー",
                        report.audioEncodings.ifEmpty { listOf("リニアPCMのみ") }.joinToString("\n")
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (detailScrollState.maxValue > 0) {
                Text("↑↓  詳細をスクロール", color = colors.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onBack,
                modifier = Modifier.width(180.dp).focusRequester(closeRequester)
            ) { Text("閉じる") }
        }
    }
}

/** コーデック1種類分の検出結果。 */
@Composable
private fun CodecSection(codec: VideoDecoderCapability) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(codec.label)
        CapabilityDetail(
            "最大確認モード",
            codec.maxVerifiedMode?.label
                ?: if (codec.isAvailable) "解像度を確認できません" else "デコーダー未検出"
        )
        if (codec.isAvailable) {
            CapabilityDetail(
                "デコード方式",
                if (codec.hasHardwareDecoder) "ハードウェア" else "ソフトウェアのみ"
            )
            // MPEG-2 には 10bit プロファイルが存在しないため、その行は表示しない。
            if (codec.mimeType != android.media.MediaFormat.MIMETYPE_VIDEO_MPEG2) {
                CapabilityDetail("10bit (Main10 等)", yesNo(codec.supports10Bit))
            }
            CapabilityDetail("デコーダー", codec.decoders.joinToString("\n") { it.name })
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = KomorebiTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = colors.accent,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun CapabilityCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = KomorebiTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.92f))
    ) {
        Column(modifier = Modifier.padding(28.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))
            content()
        }
    }
}

@Composable
private fun CapabilityVerdict(
    title: String,
    supported: Boolean,
    supportedText: String,
    unsupportedText: String
) {
    val colors = KomorebiTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (supported) Icons.Default.CheckCircle else Icons.Default.Error,
            null,
            tint = if (supported) Color(0xFF4CAF50) else Color(0xFFFF5252),
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                title,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(if (supported) supportedText else unsupportedText, color = colors.textSecondary)
        }
    }
}

@Composable
private fun CapabilityDetail(label: String, value: String) {
    val colors = KomorebiTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.textSecondary, modifier = Modifier.weight(0.48f))
        Text(
            value,
            color = colors.textPrimary,
            modifier = Modifier.weight(0.52f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun yesNo(value: Boolean): String = if (value) "対応" else "非対応"
