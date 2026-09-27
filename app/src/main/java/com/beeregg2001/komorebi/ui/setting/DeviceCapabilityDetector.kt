package com.beeregg2001.komorebi.ui.setting

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager

// 端末の再生能力(映像デコーダー・HDR・音声出力)を MediaCodec / Display / AudioManager から検出する。
//
// 参考実装: Komorebi のフォークである Honorebi (makeding/Honorebi, h-dev ブランチ) の
// `ui/setting/DeviceCapabilityDetector.kt`。HEVC のみを対象としていた同実装をベースに、
// Komorebi では地上波(MPEG-2)・H.264・AV1 の判定と、ハードウェアデコーダーの有無、
// 音声パススルー可否の検出を追加している。
//
// 注意: ここで得られるのは「端末がコーデックとして対応を申告しているか」であり、
// 実際に再生できるかとは別。同時に使えるデコーダー数の上限や、Media3 側のフォーマット
// 判定で失敗することがあるため、判定結果は「見込み」として扱うこと。

/** 解像度とフレームレートの組。デコーダーがこの組を再生できるかを問い合わせる単位。 */
data class VideoMode(val width: Int, val height: Int, val fps: Double) {
    val label: String get() = "${width}x${height} @ ${fps.toInt()}fps"
}

/** 検出した個々のデコーダー。 */
data class DetectedDecoder(
    val name: String,
    /** ハードウェアデコーダーか。API 29 未満では名前の慣習から推定する。 */
    val isHardwareAccelerated: Boolean
)

/** 1つの映像コーデックについての検出結果。 */
data class VideoDecoderCapability(
    /** 画面表示用の名前（「MPEG-2」など）。 */
    val label: String,
    val mimeType: String,
    val decoders: List<DetectedDecoder>,
    /** 再生できると申告されたモード。[DeviceCapabilityDetector] の探索順(高解像度→低解像度)を保つ。 */
    val supportedModes: List<VideoMode>,
    /** 10bit 深度のプロファイル(HEVC Main10、H.264 High10、AV1 Main10)に対応しているか。 */
    val supports10Bit: Boolean
) {
    /** デコーダーが1つ以上見つかったか。 */
    val isAvailable: Boolean get() = decoders.isNotEmpty()

    /** ハードウェアデコーダーがあるか。無い場合はソフトウェアデコードになり、低スペック機では実用にならないことがある。 */
    val hasHardwareDecoder: Boolean get() = decoders.any { it.isHardwareAccelerated }

    /** 再生できると申告された最大のモード。デコーダーが無い、または探索した全モードが非対応なら null。 */
    val maxVerifiedMode: VideoMode? get() = supportedModes.firstOrNull()

    fun supports(mode: VideoMode): Boolean = supportedModes.contains(mode)
}

data class DeviceCapabilityReport(
    val deviceName: String,
    val androidVersion: String,
    val videoCodecs: List<VideoDecoderCapability>,
    val hdrTypes: List<String>,
    /** 音声出力デバイスが申告する最大チャンネル数。 */
    val maxReportedAudioChannels: Int?,
    /** 音声出力デバイスが受け付ける符号化形式(パススルー可否の目安)。 */
    val audioEncodings: List<String>
) {
    private fun codec(mimeType: String): VideoDecoderCapability? =
        videoCodecs.firstOrNull { it.mimeType == mimeType }

    val mpeg2: VideoDecoderCapability? get() = codec(MediaFormat.MIMETYPE_VIDEO_MPEG2)
    val h264: VideoDecoderCapability? get() = codec(MediaFormat.MIMETYPE_VIDEO_AVC)
    val hevc: VideoDecoderCapability? get() = codec(MediaFormat.MIMETYPE_VIDEO_HEVC)
    val av1: VideoDecoderCapability? get() = codec(DeviceCapabilityDetector.MIMETYPE_VIDEO_AV1)

    /**
     * 地上波・BS/CS(MPEG-2)をそのまま再生できる見込みか。
     *
     * 地上波の 1440x1080@30 を基準にしている。BS の 1920x1080 ではなく 1440x1080 を見るのは、
     * MPEG-2 デコーダーの申告する対応サイズが 1920 幅のアライメント条件で false になる端末があり、
     * 実際には再生できるものを「非対応」と誤って表示してしまうのを避けるため。
     */
    val supportsBroadcastDirect: Boolean
        get() = mpeg2?.supports(DeviceCapabilityDetector.MODE_1440_1080P30) == true

    /** BS4K(3840x2160@60 / HEVC Main10)をそのまま再生できる見込みか。 */
    val supportsBs4kDirect: Boolean
        get() = hevc?.let { it.supports(DeviceCapabilityDetector.MODE_4K60) && it.supports10Bit } == true

    /** BS8K(7680x4320@60 / HEVC Main10)をそのまま再生できる見込みか。 */
    val supportsBs8kDirect: Boolean
        get() = hevc?.let { it.supports(DeviceCapabilityDetector.MODE_8K60) && it.supports10Bit } == true
}

object DeviceCapabilityDetector {

    // MediaFormat.MIMETYPE_VIDEO_AV1 は API 29 で追加された定数のため、文字列で持つ。
    // (コンパイル時に埋め込まれる定数であり、minSdk 24 の端末でも参照そのものは問題ない)
    const val MIMETYPE_VIDEO_AV1 = "video/av01"

    val MODE_8K60 = VideoMode(7680, 4320, 60.0)
    val MODE_8K30 = VideoMode(7680, 4320, 30.0)
    val MODE_DCI4K60 = VideoMode(4096, 2160, 60.0)
    val MODE_4K60 = VideoMode(3840, 2160, 60.0)
    val MODE_4K30 = VideoMode(3840, 2160, 30.0)
    val MODE_1080P60 = VideoMode(1920, 1080, 60.0)
    val MODE_1080P30 = VideoMode(1920, 1080, 30.0)
    val MODE_1440_1080P30 = VideoMode(1440, 1080, 30.0)

    // 高解像度から順に試し、最初に通ったものを「最大確認モード」として扱う。
    // 地上波(1440x1080)まで含めるのは、MPEG-2 デコーダーが 1920 幅で false を返す端末を拾うため。
    private val probeModes = listOf(
        MODE_8K60,
        MODE_8K30,
        MODE_DCI4K60,
        MODE_4K60,
        MODE_4K30,
        MODE_1080P60,
        MODE_1080P30,
        MODE_1440_1080P30
    )

    // 検出対象のコーデック。表示順もこの順。
    private val targetCodecs = listOf(
        MediaFormat.MIMETYPE_VIDEO_MPEG2 to "MPEG-2",
        MediaFormat.MIMETYPE_VIDEO_AVC to "H.264 / AVC",
        MediaFormat.MIMETYPE_VIDEO_HEVC to "H.265 / HEVC",
        MIMETYPE_VIDEO_AV1 to "AV1"
    )

    fun detect(context: Context): DeviceCapabilityReport {
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filterNot { it.isEncoder }
        }.getOrDefault(emptyList())

        val videoCodecs = targetCodecs.map { (mimeType, label) ->
            detectVideoCodec(codecInfos, mimeType, label)
        }

        val hdrTypes = runCatching {
            displayFor(context)?.hdrCapabilities?.supportedHdrTypes
                ?.let(::hdrTypeLabels)
                .orEmpty()
        }.getOrDefault(emptyList())

        val audioManager = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }.getOrNull()
        val outputDevices = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val maxAudioChannels = runCatching {
            outputDevices.flatMap { it.channelCounts.asIterable() }.maxOrNull()
        }.getOrNull()

        val audioEncodings = runCatching {
            outputDevices.flatMap { it.encodings.asIterable() }
                .distinct()
                .mapNotNull(::audioEncodingLabel)
                .distinct()
        }.getOrDefault(emptyList())

        return DeviceCapabilityReport(
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            videoCodecs = videoCodecs,
            hdrTypes = hdrTypes,
            maxReportedAudioChannels = maxAudioChannels,
            audioEncodings = audioEncodings
        )
    }

    private fun detectVideoCodec(
        codecInfos: List<MediaCodecInfo>,
        mimeType: String,
        label: String
    ): VideoDecoderCapability {
        // 同じ MIME に複数のデコーダーが載っていることがあるため、全て集めて OR で判定する。
        val matched = codecInfos.mapNotNull { codecInfo ->
            val supportedType = codecInfo.supportedTypes.firstOrNull {
                it.equals(mimeType, ignoreCase = true)
            } ?: return@mapNotNull null
            runCatching {
                codecInfo to codecInfo.getCapabilitiesForType(supportedType)
            }.getOrNull()
        }

        val decoders = matched.map { (codecInfo, _) ->
            DetectedDecoder(
                name = codecInfo.name,
                isHardwareAccelerated = isHardwareAccelerated(codecInfo)
            )
        }.distinctBy { it.name }

        val supportedModes = probeModes.filter { mode ->
            matched.any { (_, capabilities) ->
                runCatching {
                    capabilities.videoCapabilities
                        ?.areSizeAndRateSupported(mode.width, mode.height, mode.fps) == true
                }.getOrDefault(false)
            }
        }

        val profiles10Bit = profilesFor10Bit(mimeType)
        val supports10Bit = profiles10Bit.isNotEmpty() && matched.any { (_, capabilities) ->
            runCatching {
                capabilities.profileLevels.any { it.profile in profiles10Bit }
            }.getOrDefault(false)
        }

        return VideoDecoderCapability(
            label = label,
            mimeType = mimeType,
            decoders = decoders,
            supportedModes = supportedModes,
            supports10Bit = supports10Bit
        )
    }

    /**
     * ハードウェアデコーダーかを判定する。
     *
     * API 29 以降は [MediaCodecInfo.isHardwareAccelerated] を使う。それ以前は判定APIが無いため、
     * ソフトウェア実装に使われる名前の慣習(`OMX.google.` / `c2.android.`)で推定する。
     */
    private fun isHardwareAccelerated(codecInfo: MediaCodecInfo): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            codecInfo.isHardwareAccelerated
        } else {
            val name = codecInfo.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
    }.getOrDefault(false)

    /** コーデックごとの 10bit プロファイル。10bit が規格上存在しない MPEG-2 は空を返す。 */
    private fun profilesFor10Bit(mimeType: String): Set<Int> = when (mimeType) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> buildSet {
            add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            }
        }

        MediaFormat.MIMETYPE_VIDEO_AVC -> setOf(
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        )

        MIMETYPE_VIDEO_AV1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setOf(
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            )
        } else {
            emptySet()
        }

        else -> emptySet()
    }

    private fun displayFor(context: Context): Display? {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
        return display?.takeIf(Display::isValid)
    }

    internal fun hdrTypeLabels(supportedHdrTypes: IntArray): List<String> = buildList {
        supportedHdrTypes.forEach { hdrType ->
            val label = when (hdrType) {
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                else -> null
            }
            if (label != null && label !in this) add(label)
        }
    }

    /**
     * 音声の符号化形式をラベルにする。パススルーできるかの目安として表示する。
     * リニアPCM(端末内でデコードした音声の出力先)は情報にならないため除外する。
     */
    internal fun audioEncodingLabel(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_AC3 -> "Dolby Digital (AC-3)"
        AudioFormat.ENCODING_E_AC3 -> "Dolby Digital Plus (E-AC-3)"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        AudioFormat.ENCODING_AAC_LC -> "AAC-LC"
        AudioFormat.ENCODING_AAC_HE_V1 -> "HE-AAC v1"
        AudioFormat.ENCODING_AAC_HE_V2 -> "HE-AAC v2"
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                encoding == AudioFormat.ENCODING_E_AC3_JOC -> "Dolby Atmos (E-AC-3 JOC)"

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                encoding == AudioFormat.ENCODING_AC4 -> "Dolby AC-4"

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                encoding == AudioFormat.ENCODING_DOLBY_MAT -> "Dolby MAT"

            else -> null
        }
    }
}
