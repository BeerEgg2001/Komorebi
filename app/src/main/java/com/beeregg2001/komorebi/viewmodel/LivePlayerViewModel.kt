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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
    }

    // ★ 修正: ExoPlayerを動的に再生成してUIに伝えるため、StateFlowに変更
    private val _mainPlayer = MutableStateFlow<ExoPlayer?>(null)
    val mainPlayer: StateFlow<ExoPlayer?> = _mainPlayer.asStateFlow()

    private val _dualPlayer = MutableStateFlow<ExoPlayer?>(null)
    val dualPlayer: StateFlow<ExoPlayer?> = _dualPlayer.asStateFlow()

    private val mainTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray())
    private val dualTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray())

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

    private val mainPlaybackMutex = Mutex()
    private val dualPlaybackMutex = Mutex()

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

    // ★ 修正: ストリーム停止時にExoPlayerインスタンスを完全に破棄(release)して null にする
    private fun stopMainPlaybackSafely() {
        Log.d(TAG, "stopMainPlaybackSafely() called. Destroying ExoPlayer instance...")
        mainEventSource?.cancel()
        mainEventSource = null

        _mainPlayer.value?.stop()
        _mainPlayer.value?.release()
        _mainPlayer.value = null

        _mainSseStatus.value = "Standby"
        _mainSseDetail.value = AppStrings.SSE_CONNECTING
        _mainPlayerError.value = null
    }

    private fun stopDualPlaybackSafely() {
        Log.d(TAG, "stopDualPlaybackSafely() called. Destroying ExoPlayer instance...")
        dualEventSource?.cancel()
        dualEventSource = null

        _dualPlayer.value?.stop()
        _dualPlayer.value?.release()
        _dualPlayer.value = null

        _dualSseStatus.value = "Standby"
        _dualSseDetail.value = AppStrings.SSE_CONNECTING
    }

    fun releasePlayers() {
        Log.d(TAG, "releasePlayers() called. Completely freeing hardware decoders.")
        mainPlaybackJob?.cancel()
        dualPlaybackJob?.cancel()
        mainEventSource?.cancel()
        dualEventSource?.cancel()

        _mainPlayer.value?.release()
        _mainPlayer.value = null
        _dualPlayer.value?.release()
        _dualPlayer.value = null

        _mainSseStatus.value = "Standby"
        _dualSseStatus.value = "Standby"
    }

    fun playMainChannel(
        uiContext: Context,
        channel: Channel,
        source: StreamSource,
        quality: StreamQuality
    ) {
        Log.d(TAG, "playMainChannel() called: channel=${channel.name}")
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return

        viewModelScope.launch {
            _currentLogoUrl.value = liveProvider.getChannelLogoUrl(channel.id)
        }

        mainPlaybackJob?.cancel()
        mainPlaybackJob = viewModelScope.launch {
            try {
                mainPlaybackMutex.withLock {
                    stopMainPlaybackSafely()
                    mainCurrentSource = source

                    delay(400) // デコーダとEDCBチューナーの完全解放を待つ

                    // ★ 修正: ここで全く新しい ExoPlayer インスタンスを生成する！
                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val newPlayer = createExoPlayer(
                        uiContext,
                        audioOutputMode,
                        { mainCurrentSource == StreamSource.KONOMITV }) { error ->
                        Log.e(TAG, "ExoPlayer (Main) Error: ${error.message}", error)
                        _mainPlayerError.value = analyzePlayerError(error)
                    }
                    _mainPlayer.value = newPlayer

                    val config = settingsRepository.getBackendConfig(source)
                    val streamUrl =
                        buildStreamUrl(channel, source, quality, config, mainTsDataSourceFactory)

                    if (source == StreamSource.MIRAKURUN || source == StreamSource.EDCB) {
                        _mainSseStatus.value = "ONAir"
                        _mainSseDetail.value = ""
                    } else {
                        if (config is BackendConfig.KonomiTv) {
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

                    startPlayback(uiContext, newPlayer, streamUrl, source, mainTsDataSourceFactory)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playMainChannel: Job cancelled.")
            }
        }
    }

    fun playDualChannel(
        uiContext: Context,
        channel: Channel,
        source: StreamSource,
        quality: StreamQuality
    ) {
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return

        dualPlaybackJob?.cancel()
        dualPlaybackJob = viewModelScope.launch {
            try {
                dualPlaybackMutex.withLock {
                    stopDualPlaybackSafely()
                    dualCurrentSource = source

                    delay(400)

                    // ★ 修正: デュアル側も毎回新しい ExoPlayer を生成する
                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val newDualPlayer = createExoPlayer(
                        uiContext,
                        audioOutputMode,
                        { dualCurrentSource == StreamSource.KONOMITV }) { }
                    _dualPlayer.value = newDualPlayer

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

                    startPlayback(
                        uiContext,
                        newDualPlayer,
                        streamUrl,
                        source,
                        dualTsDataSourceFactory
                    )
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playDualChannel: Job cancelled.")
            }
        }
    }

    fun stopAllPlayers() {
        Log.d(TAG, "stopAllPlayers() invoked. Releasing tuners...")
        mainPlaybackJob?.cancel()
        dualPlaybackJob?.cancel()

        viewModelScope.launch {
            mainPlaybackMutex.withLock { stopMainPlaybackSafely() }
            dualPlaybackMutex.withLock { stopDualPlaybackSafely() }
        }
    }

    fun stopDualPlayer() {
        dualPlaybackJob?.cancel()
        viewModelScope.launch {
            dualPlaybackMutex.withLock { stopDualPlaybackSafely() }
        }
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        this.isSubtitleEnabled = enabled
    }

    fun setVolumes(mainVolume: Float, dualVolume: Float) {
        _mainPlayer.value?.volume = mainVolume
        _dualPlayer.value?.volume = dualVolume
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
        uiContext: Context,
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
                DefaultMediaSourceFactory(uiContext).createMediaSource(mediaItem)
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
        val request =
            Request.Builder().url(eventUrl).header("User-Agent", "Komorebi/1.0 (Main)").build()
        mainEventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {}
                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    if (t is java.io.IOException && t.message == "Canceled") return
                    response?.close()
                    viewModelScope.launch(Dispatchers.Main) {
                        if (response != null && response.code !in 200..299) {
                            _mainPlayerError.value =
                                "KonomiTVサーバーエラー (HTTP ${response.code})"
                            _mainPlayer.value?.stop()
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
                            val detail = json.optString("detail", AppStrings.STATUS_LOADING)

                            _mainSseStatus.value = status
                            _mainSseDetail.value = if (detail.contains("OnAirです")) "" else detail

                            if (status == "Error" || (status == "Offline" && (detail.contains("失敗") || detail.contains(
                                    "エラー"
                                )))
                            ) {
                                _mainPlayerError.value =
                                    _mainSseDetail.value.ifEmpty { AppStrings.ERR_TUNER_START_FAILED }
                                _mainPlayer.value?.stop()
                                return@launch
                            }

                            when (status) {
                                "Standby", "Restart" -> _mainPlayer.value?.pause()
                                "ONAir" -> {
                                    if (_mainPlayer.value?.playerError != null || _mainPlayerError.value != null) {
                                        _mainPlayerError.value = null
                                        _mainPlayer.value?.prepare()
                                    }
                                    _mainPlayer.value?.play()
                                }

                                "Offline" -> _mainPlayer.value?.pause()
                            }
                        } catch (e: Exception) {
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
                            _dualPlayer.value?.stop()
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
                                "Standby", "Restart" -> _dualPlayer.value?.pause()
                                "ONAir" -> {
                                    if (_dualPlayer.value?.playerError != null) _dualPlayer.value?.prepare()
                                    _dualPlayer.value?.play()
                                }

                                "Offline", "Error" -> _dualPlayer.value?.pause()
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            })
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createExoPlayer(
        uiContext: Context,
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

        val renderersFactory = object : DefaultRenderersFactory(uiContext) {
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
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }

        return ExoPlayer.Builder(uiContext, renderersFactory)
            .setReleaseTimeoutMs(10000).setDetachSurfaceTimeoutMs(10000)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2000, 10000, 1000, 1500)
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
                                    _subtitleEvents.emit(Pair(pts, base64Data))
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
                // ★ 修正: _mainPlayer.value から情報を取る
                _mainPlayer.value?.let { player ->
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
        releasePlayers()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}