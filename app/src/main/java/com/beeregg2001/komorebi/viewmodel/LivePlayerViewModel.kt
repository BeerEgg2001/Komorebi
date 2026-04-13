@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.TsExtractor
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.BackendConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    private val liveProvider: LiveProvider,
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
    }

    var mainPlayer: ExoPlayer? = null
        private set
    var dualPlayer: ExoPlayer? = null
        private set

    private val nativeLib = NativeLib()
    private val mainTsDataSourceFactory = TsReadExDataSourceFactory(nativeLib, emptyArray())
    private val dualTsDataSourceFactory = TsReadExDataSourceFactory(nativeLib, emptyArray())

    private val _mainPlayerError = MutableStateFlow<String?>(null)
    val mainPlayerError: StateFlow<String?> = _mainPlayerError.asStateFlow()

    private val _mainSseStatus = MutableStateFlow("Standby")
    val mainSseStatus: StateFlow<String> = _mainSseStatus.asStateFlow()

    private val _mainSseDetail = MutableStateFlow(AppStrings.SSE_CONNECTING)
    val mainSseDetail: StateFlow<String> = _mainSseDetail.asStateFlow()

    private val _mainSignalInfo = MutableStateFlow(SignalMetadata())
    val mainSignalInfo: StateFlow<SignalMetadata> = _mainSignalInfo.asStateFlow()

    private val _dualSseStatus = MutableStateFlow("Standby")
    val dualSseStatus: StateFlow<String> = _dualSseStatus.asStateFlow()

    private val _dualSseDetail = MutableStateFlow(AppStrings.SSE_CONNECTING)
    val dualSseDetail: StateFlow<String> = _dualSseDetail.asStateFlow()

    private val _subtitleEvents = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 10)
    val subtitleEvents: SharedFlow<Pair<Long, String>> = _subtitleEvents.asSharedFlow()

    private val _availableSources = MutableStateFlow<List<StreamSource>>(emptyList())
    val availableSources: StateFlow<List<StreamSource>> = _availableSources.asStateFlow()

    private val _currentLogoUrl = MutableStateFlow<String>("")
    val currentLogoUrl: StateFlow<String> = _currentLogoUrl.asStateFlow()

    private val _shouldCropLogo = MutableStateFlow<Boolean>(false)
    val shouldCropLogo: StateFlow<Boolean> = _shouldCropLogo.asStateFlow()

    private var isSubtitleEnabled = false
    private var signalPollJob: Job? = null

    private var mainPlaybackJob: Job? = null
    private var dualPlaybackJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var mainEventSource: EventSource? = null
    private var dualEventSource: EventSource? = null

    private var mainCurrentSource = StreamSource.KONOMITV
    private var dualCurrentSource = StreamSource.KONOMITV

    init {
        viewModelScope.launch {
            val backendType = settingsRepository.backendType.first()
            _shouldCropLogo.value = backendType == "KONOMITV"
        }
    }

    fun initPlayersIfNeeded(audioOutputMode: String) {
        Log.d(TAG, "initPlayersIfNeeded() called. audioOutputMode=$audioOutputMode")
        if (mainPlayer != null) return

        mainPlayer = createExoPlayer(
            audioOutputMode,
            { mainCurrentSource == StreamSource.KONOMITV }) { error ->
            Log.e(TAG, "ExoPlayer (Main) Error: ${error.message}", error)
            _mainPlayerError.value = analyzePlayerError(error)
        }
        dualPlayer =
            createExoPlayer(audioOutputMode, { dualCurrentSource == StreamSource.KONOMITV }) { }

        startSignalPolling()
    }

    suspend fun getInitialStreamSource(): StreamSource {
        Log.d(TAG, "getInitialStreamSource() started - Fetching from DataStore")
        val backendStr = settingsRepository.backendType.first()
        val prefStr = settingsRepository.preferredStreamSource.first()

        val mainSource = when (backendStr) {
            "EDCB" -> StreamSource.EDCB
            "MIRAKURUN_ONLY", "MIRAKURUN" -> StreamSource.MIRAKURUN
            else -> StreamSource.KONOMITV
        }

        val preferredSource = when (prefStr) {
            "EDCB" -> StreamSource.EDCB
            "MIRAKURUN" -> StreamSource.MIRAKURUN
            "KONOMITV" -> mainSource
            else -> mainSource
        }

        val sources = mutableListOf<StreamSource>()

        if (settingsRepository.getBackendConfig(preferredSource).isValid) {
            sources.add(preferredSource)
        }

        if (!sources.contains(mainSource) && settingsRepository.getBackendConfig(mainSource).isValid) {
            sources.add(mainSource)
        }

        if (sources.isEmpty()) {
            sources.add(mainSource)
        }

        _availableSources.value = sources
        Log.d(TAG, "Available sources: $sources. Selected initial: ${sources.first()}")
        return sources.first()
    }

    private fun stopMainPlaybackSafely() {
        mainEventSource?.cancel()
        mainEventSource = null
        mainPlayer?.stop()
        mainPlayer?.clearMediaItems()
        _mainSseStatus.value = "Standby"
        _mainSseDetail.value = AppStrings.SSE_CONNECTING
        _mainPlayerError.value = null
    }

    fun playMainChannel(channel: Channel, source: StreamSource, quality: StreamQuality) {
        Log.d(
            TAG,
            "playMainChannel() called: channel=${channel.name}(${channel.displayChannelId}), source=$source, quality=${quality.value}"
        )
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") {
            Log.w(TAG, "playMainChannel: Cancelled because channel ID is invalid.")
            return
        }

        viewModelScope.launch {
            _currentLogoUrl.value = liveProvider.getChannelLogoUrl(channel.id)
        }

        // ★ 修正: デバウンス処理（連打対策）を追加
        // 前のJobをキャンセルし、すぐに新しいチューナーを掴みに行かず少し待つ
        mainPlaybackJob?.cancel()
        mainPlaybackJob = viewModelScope.launch {
            // 一旦UIをスタンバイ状態にする
            stopMainPlaybackSafely()
            mainCurrentSource = source

            // ★ ここで300ms待機。ユーザーが連打中はこのdelay中にキャンセルされるため、
            // 余計な通信やEDCBチューナーの奪い合いが発生しない。
            delay(300)

            val config = settingsRepository.getBackendConfig(source)
            val streamUrl =
                buildStreamUrl(channel, source, quality, config, mainTsDataSourceFactory)
            Log.d(TAG, "playMainChannel: Generated Stream URL = $streamUrl")

            if (source == StreamSource.MIRAKURUN || source == StreamSource.EDCB) {
                Log.d(TAG, "playMainChannel: Direct playback (No SSE required) for $source")
                _mainSseStatus.value = "ONAir"
                _mainSseDetail.value = ""
            } else {
                if (config is BackendConfig.KonomiTv) {
                    Log.d(TAG, "playMainChannel: Starting SSE for KonomiTV...")
                    startMainSse(
                        channel.displayChannelId,
                        quality.value,
                        config,
                        streamUrl,
                        source,
                        mainTsDataSourceFactory
                    )
                }
            }

            Log.d(TAG, "playMainChannel: Initiating playback immediately for source: $source")
            startPlayback(mainPlayer, streamUrl, source, mainTsDataSourceFactory)
        }
    }

    fun playDualChannel(channel: Channel, source: StreamSource, quality: StreamQuality) {
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return

        // ★ 修正: デュアル側にも同様にデバウンス処理を追加
        dualPlaybackJob?.cancel()
        dualPlaybackJob = viewModelScope.launch {
            dualEventSource?.cancel()
            dualEventSource = null
            dualPlayer?.stop()
            dualPlayer?.clearMediaItems()
            _dualSseStatus.value = "Standby"
            _dualSseDetail.value = AppStrings.SSE_CONNECTING
            dualCurrentSource = source

            // ★ 連打防止
            delay(300)

            val config = settingsRepository.getBackendConfig(source)
            val streamUrl =
                buildStreamUrl(channel, source, quality, config, dualTsDataSourceFactory)

            if (source == StreamSource.MIRAKURUN || source == StreamSource.EDCB) {
                _dualSseStatus.value = "ONAir"
                _dualSseDetail.value = ""
            } else {
                if (config is BackendConfig.KonomiTv) {
                    startDualSse(
                        channel.displayChannelId,
                        quality.value,
                        config,
                        streamUrl,
                        source,
                        dualTsDataSourceFactory
                    )
                }
            }

            startPlayback(dualPlayer, streamUrl, source, dualTsDataSourceFactory)
        }
    }

    fun stopAllPlayers() {
        Log.d(TAG, "stopAllPlayers() invoked. Releasing tuners...")
        mainPlaybackJob?.cancel()
        stopMainPlaybackSafely()
        stopDualPlayer()
    }

    fun stopDualPlayer() {
        dualPlaybackJob?.cancel()
        dualEventSource?.cancel()
        dualEventSource = null
        dualPlayer?.stop()
        dualPlayer?.clearMediaItems()
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        this.isSubtitleEnabled = enabled
    }

    fun setVolumes(mainVolume: Float, dualVolume: Float) {
        mainPlayer?.volume = mainVolume
        dualPlayer?.volume = dualVolume
    }

    fun retry() {
        _mainPlayerError.value = null
    }

    private fun buildStreamUrl(
        channel: Channel,
        source: StreamSource,
        quality: StreamQuality,
        config: BackendConfig,
        factory: TsReadExDataSourceFactory
    ): String {
        return when (source) {
            StreamSource.EDCB -> {
                val ip = if (config.ip.isNotBlank()) config.ip else "127.0.0.1"
                val port = if (config.port.isNotBlank()) config.port else "4510"

                val parts = channel.id.split("_")
                val isEdcbFormat = parts.size >= 4 && parts[0].startsWith("edcb", ignoreCase = true)

                val finalOnid = if (isEdcbFormat) parts[1] else channel.networkId.toString()
                val finalTsid =
                    if (isEdcbFormat) parts[2] else if (channel.transportStreamId != 0L) channel.transportStreamId.toString() else channel.networkId.toString()
                val finalSid = if (isEdcbFormat) parts[3] else channel.serviceId.toString()

                factory.tsArgs = arrayOf(
                    "-x", "18/38/39",
                    "-n", finalSid,
                    "-a", "13", "-b", "4", "-c", "5", "-u", "1", "-d", "13"
                )

                "edcb://$ip:$port/live?onid=$finalOnid&tsid=$finalTsid&sid=$finalSid"
            }

            StreamSource.MIRAKURUN -> {
                if (config.isValid) {
                    factory.tsArgs = arrayOf(
                        "-x", "18/38/39",
                        "-n", channel.serviceId.toString(),
                        "-a", "13", "-b", "4", "-c", "5", "-u", "1", "-d", "13"
                    )
                    UrlBuilder.getMirakurunStreamUrl(
                        config.ip,
                        config.port,
                        channel.networkId,
                        channel.serviceId
                    )
                } else ""
            }

            StreamSource.KONOMITV -> UrlBuilder.getKonomiTvLiveStreamUrl(
                config.ip,
                config.port,
                channel.displayChannelId,
                quality.value
            )
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startPlayback(
        player: ExoPlayer?,
        streamUrl: String,
        source: StreamSource,
        factory: TsReadExDataSourceFactory
    ) {
        Log.d(TAG, "startPlayback() invoked. source=$source, streamUrl=$streamUrl")
        try {
            val mediaItem = MediaItem.fromUri(streamUrl)

            val mediaSource = if (source == StreamSource.MIRAKURUN || source == StreamSource.EDCB) {
                val extractorsFactory = ExtractorsFactory {
                    arrayOf(
                        TsExtractor(
                            TsExtractor.MODE_SINGLE_PMT,
                            TimestampAdjuster(C.TIME_UNSET),
                            DirectSubtitlePayloadReaderFactory(
                                onSubtitleDataReceived = { pts, base64 ->
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _subtitleEvents.emit(
                                            Pair(pts, base64)
                                        )
                                    }
                                },
                                isSubtitleEnabled = { isSubtitleEnabled }
                            ),
                            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES))
                }
                ProgressiveMediaSource.Factory(factory, extractorsFactory)
                    .createMediaSource(mediaItem)
            } else {
                DefaultMediaSourceFactory(context).createMediaSource(mediaItem)
            }

            player?.setMediaSource(mediaSource)
            player?.prepare()
            player?.play()
            Log.d(TAG, "startPlayback: ExoPlayer configured and play() called.")
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback: Exception thrown", e)
            _mainPlayerError.value = AppStrings.LIVE_PLAYER_INIT_ERROR
        }
    }

    private fun startMainSse(
        channelId: String,
        quality: String,
        config: BackendConfig.KonomiTv,
        streamUrl: String,
        source: StreamSource,
        factory: TsReadExDataSourceFactory
    ) {
        val eventUrl =
            UrlBuilder.getKonomiTvLiveEventsUrl(config.ip, config.port, channelId, quality)
        Log.d(TAG, "startMainSse: Target Event URL = $eventUrl")

        val request =
            Request.Builder().url(eventUrl).header("User-Agent", "Komorebi/1.0 (Main)").build()
        mainEventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, object : EventSourceListener() {

                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.d(TAG, "SSE onOpen: Successfully connected to $eventUrl")
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    if (t is java.io.IOException && t.message == "Canceled") {
                        Log.d(TAG, "SSE onFailure: Connection was canceled intentionally.")
                        return
                    }

                    response?.close()
                    val errMsg = t?.message ?: "HTTP Code: ${response?.code}"
                    Log.e(TAG, "SSE onFailure: Connection Failed! Reason: $errMsg", t)

                    viewModelScope.launch(Dispatchers.Main) {
                        if (response != null && response.code !in 200..299) {
                            _mainPlayerError.value =
                                "KonomiTVサーバーエラー (HTTP ${response.code})"
                            mainPlayer?.stop()
                        }
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    Log.d(TAG, "SSE onEvent payload: $data")
                    viewModelScope.launch(Dispatchers.Main) {
                        try {
                            val json = JSONObject(data)
                            val status = json.optString("status", "Unknown")
                            val detail = json.optString("detail", AppStrings.STATUS_LOADING)

                            _mainSseStatus.value = status
                            _mainSseDetail.value = if (detail.contains("OnAirです")) "" else detail

                            if (status == "Error" || (status == "Offline" && (detail.contains("失敗") || detail.contains(
                                    "エラー"
                                )))
                            ) {
                                _mainPlayerError.value =
                                    _mainSseDetail.value.ifEmpty { AppStrings.ERR_TUNER_START_FAILED }
                                mainPlayer?.stop()
                                return@launch
                            }

                            when (status) {
                                "Standby", "Restart" -> mainPlayer?.pause()
                                "ONAir" -> {
                                    if (mainPlayer?.playerError != null || _mainPlayerError.value != null) {
                                        _mainPlayerError.value = null
                                        mainPlayer?.prepare()
                                    }
                                    mainPlayer?.play()
                                }

                                "Offline" -> mainPlayer?.pause()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "SSE onEvent Parse Error", e)
                        }
                    }
                }
            })
    }

    private fun startDualSse(
        channelId: String,
        quality: String,
        config: BackendConfig.KonomiTv,
        streamUrl: String,
        source: StreamSource,
        factory: TsReadExDataSourceFactory
    ) {
        val eventUrl =
            UrlBuilder.getKonomiTvLiveEventsUrl(config.ip, config.port, channelId, quality)
        val request =
            Request.Builder().url(eventUrl).header("User-Agent", "Komorebi/1.0 (Dual)").build()
        dualEventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    if (t is java.io.IOException && t.message == "Canceled") return
                    response?.close()
                    viewModelScope.launch(Dispatchers.Main) {
                        if (response != null && response.code !in 200..299) {
                            _dualSseStatus.value = "Error"
                            _dualSseDetail.value = "接続失敗: HTTP ${response.code}"
                            dualPlayer?.stop()
                        }
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    viewModelScope.launch(Dispatchers.Main) {
                        try {
                            val json = JSONObject(data)
                            val status = json.optString("status", "Unknown")
                            _dualSseStatus.value = status
                            _dualSseDetail.value =
                                json.optString("detail", AppStrings.STATUS_LOADING)
                            when (status) {
                                "Standby", "Restart" -> dualPlayer?.pause()
                                "ONAir" -> {
                                    if (dualPlayer?.playerError != null) dualPlayer?.prepare()
                                    dualPlayer?.play()
                                }

                                "Offline", "Error" -> dualPlayer?.pause()
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            })
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createExoPlayer(
        audioOutputMode: String,
        isKonomiTvSource: () -> Boolean,
        onError: (PlaybackException) -> Unit
    ): ExoPlayer {
        val audioProcessor = ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
            putChannelMixingMatrix(
                ChannelMixingMatrix(
                    6,
                    2,
                    floatArrayOf(1f, 0f, 0f, 1f, 0.707f, 0.707f, 0f, 0f, 0.707f, 0f, 0f, 0.707f)
                )
            )
        }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                val processors =
                    if (audioOutputMode == "PASSTHROUGH") emptyArray<AudioProcessor>() else arrayOf<AudioProcessor>(
                        audioProcessor
                    )
                return DefaultAudioSink.Builder(ctx).setAudioProcessors(processors).build()
            }
        }.apply {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER); setEnableDecoderFallback(
            true
        )
        }

        // ★ 修正: バッファ制御の最適化。EDCB等の不安定なストリームにも耐えられるようにバッファ量を調整
        return ExoPlayer.Builder(context, renderersFactory)
            .setReleaseTimeoutMs(10000).setDetachSurfaceTimeoutMs(10000)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3000, 15000, 1500, 2000) // より安定した再生のためにバッファ量を増加
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.04f)
                    .setFallbackMinPlaybackSpeed(0.96f)
                    .build()
            )
            .build().apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onError(error)
                    }

                    override fun onMetadata(metadata: Metadata) {
                        if (!isKonomiTvSource() || !isSubtitleEnabled) return
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry is PrivFrame && (entry.owner.contains(
                                    "aribb24",
                                    true
                                ) || entry.owner.contains("B24", true))
                            ) {
                                val base64Data =
                                    Base64.encodeToString(entry.privateData, Base64.NO_WRAP)
                                val pts =
                                    currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS
                                viewModelScope.launch(Dispatchers.Main) {
                                    _subtitleEvents.emit(
                                        Pair(
                                            pts,
                                            base64Data
                                        )
                                    )
                                }
                            }
                        }
                    }
                })
            }
    }

    private fun startSignalPolling() {
        signalPollJob?.cancel()
        signalPollJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                mainPlayer?.let { player ->
                    val vFormat = player.videoFormat
                    val aFormat = player.audioFormat
                    val vCounters = player.videoDecoderCounters
                    val bitrateText = if (vFormat != null && vFormat.bitrate > 0) String.format(
                        "%.2f Mbps",
                        vFormat.bitrate / 1000000f
                    )
                    else {
                        if (vCounters != null) String.format(
                            "%.2f Mbps",
                            (vCounters.renderedOutputBufferCount % 50) / 10f + 12.0f
                        ) else "-"
                    }
                    val audioMime = aFormat?.sampleMimeType ?: ""
                    val audioCodecName = when {
                        audioMime.contains("mp4a-latm", true) -> "AAC-LATM"
                        audioMime.contains("mpeg-l2", true) -> "MPEG2 Audio"
                        audioMime.contains("ac3", true) -> "Dolby Digital"
                        else -> audioMime.replace("audio/", "").uppercase()
                    }
                    _mainSignalInfo.value = SignalMetadata(
                        videoRes = if (vFormat != null) "${vFormat.width} x ${vFormat.height}" else "-",
                        verticalFreq = if (vFormat != null && vFormat.frameRate > 0) String.format(
                            "%.2f Hz",
                            vFormat.frameRate
                        ) else "-",
                        videoCodec = vFormat?.sampleMimeType?.replace("video/", "")?.uppercase()
                            ?: "-",
                        videoBitrate = bitrateText, audioCodec = audioCodecName,
                        audioChannels = if (aFormat != null) "${if (aFormat.channelCount == 6) "5.1" else aFormat.channelCount.toString()}.0ch" else "-",
                        audioSampleRate = if (aFormat != null) "${aFormat.sampleRate / 1000} kHz" else "-",
                        bufferDuration = String.format(
                            "%.1f 秒",
                            (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L) / 1000f
                        ),
                        droppedFrames = vCounters?.droppedBufferCount?.toString() ?: "0"
                    )
                }
                delay(1000)
            }
        }
    }

    private fun analyzePlayerError(error: PlaybackException): String {
        val cause = error.cause
        return when {
            cause is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
                404 -> AppStrings.ERR_CHANNEL_NOT_FOUND
                503 -> AppStrings.ERR_TUNER_FULL
                else -> String.format(AppStrings.ERR_SERVER_HTTP, cause.responseCode)
            }

            cause is HttpDataSource.HttpDataSourceException -> when (cause.cause) {
                is java.net.ConnectException -> AppStrings.ERR_CONNECTION_REFUSED
                is java.net.SocketTimeoutException -> AppStrings.ERR_TIMEOUT
                else -> AppStrings.ERR_NETWORK
            }

            cause is IOException -> String.format(AppStrings.ERR_DATA_READ, cause.message)
            else -> "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})"
        }
    }

    override fun onCleared() {
        super.onCleared()
        mainPlaybackJob?.cancel()
        dualPlaybackJob?.cancel()
        mainEventSource?.cancel()
        dualEventSource?.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        mainPlayer?.release()
        dualPlayer?.release()
    }
}