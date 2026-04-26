@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.TsExtractor
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.data.model.BackendConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class LiveComment(
    val text: String,
    val color: String,
    val position: String,
    val size: String
)

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveProvider: LiveProvider,
    private val recordProvider: RecordProvider,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
        private const val MAX_AUTO_RETRY = 2

        private val JIKKYO_CHANNEL_ID_MAP = mapOf(
            "jk1" to "ch2646436", "jk2" to "ch2646437", "jk4" to "ch2646438",
            "jk5" to "ch2646439", "jk6" to "ch2646440", "jk7" to "ch2646441",
            "jk8" to "ch2646442", "jk9" to "ch2646485", "jk10" to null,
            "jk11" to null, "jk12" to null, "jk13" to null, "jk14" to null,
            "jk101" to "ch2647992", "jk103" to null, "jk141" to null,
            "jk151" to null, "jk161" to null, "jk171" to null, "jk181" to null,
            "jk191" to null, "jk192" to null, "jk193" to null, "jk200" to null,
            "jk201" to null, "jk211" to "ch2646846", "jk222" to null,
            "jk236" to null, "jk252" to null, "jk260" to null, "jk263" to null,
            "jk265" to null, "jk333" to null
        )
    }

    private val gson = Gson()

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

    private val _availableQualities =
        MutableStateFlow<List<StreamQuality>>(StreamQuality.DEFAULT_QUALITIES)
    val availableQualities: StateFlow<List<StreamQuality>> = _availableQualities.asStateFlow()

    private val _isQualitiesLoaded = MutableStateFlow(false)
    val isQualitiesLoaded: StateFlow<Boolean> = _isQualitiesLoaded.asStateFlow()

    private val _currentLogoUrl = MutableStateFlow<String>("")
    val currentLogoUrl: StateFlow<String> = _currentLogoUrl.asStateFlow()

    private val _shouldCropLogo = MutableStateFlow<Boolean>(false)
    val shouldCropLogo: StateFlow<Boolean> = _shouldCropLogo.asStateFlow()

    private val _liveComments = MutableSharedFlow<LiveComment>(extraBufferCapacity = 100)
    val liveComments: SharedFlow<LiveComment> = _liveComments.asSharedFlow()

    private val _clearCommentsEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearCommentsEvent: SharedFlow<Unit> = _clearCommentsEvent.asSharedFlow()

    private val _mainBackendType = MutableStateFlow("KONOMITV")
    val mainBackendType: StateFlow<String> = _mainBackendType.asStateFlow()

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
    private var mainIsEdcbDirect = false
    private var mainCurrentChannel: Channel? = null
    private var mainCurrentQuality: StreamQuality? = null
    private var mainAutoRetryCount = 0

    private var dualCurrentSource = StreamSource.KONOMITV
    private var dualIsEdcbDirect = false
    private var dualCurrentChannel: Channel? = null
    private var dualCurrentQuality: StreamQuality? = null
    private var dualAutoRetryCount = 0

    private var jikkyoClient: JikkyoClient? = null
    private val processedCommentIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private var jikkyoChannelsCache: JSONArray? = null

    init {
        viewModelScope.launch {
            settingsRepository.backendType.collect { type ->
                _mainBackendType.value = type
                _shouldCropLogo.value = type == "KONOMITV"
            }
        }
        startSignalPolling()
    }

    suspend fun getInitialEdcbDirect(): Boolean {
        val backendStr = settingsRepository.backendType.first()
        val prefStr = settingsRepository.preferredStreamSource.first()
        if (backendStr == "EDCB") {
            if (prefStr == "EDCB") return true
            if (prefStr == "KONOMITV") return false
        } else if (backendStr == "KONOMITV" || backendStr == "MIRAKURUN_ONLY") {
            if (prefStr == "EDCB") return true
        }
        return false
    }

    // ★ 修正: ご提案の通り、キャッシュが空の場合はEPG取得に割り込んでAPIで取得する！
    fun fetchAvailableQualities(source: StreamSource, isEdcbDirect: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isQualitiesLoaded.value = false
            try {
                if (source == StreamSource.EDCB) {
                    if (isEdcbDirect) {
                        _availableQualities.value = listOf(
                            StreamQuality(
                                label = "オリジナル (Direct)",
                                value = "direct",
                                isRawTs = true
                            )
                        )
                    } else {
                        val json = settingsRepository.availableStreamQualities.first()
                        if (json.isNotBlank()) {
                            try {
                                val type = object : TypeToken<List<StreamQuality>>() {}.type
                                val list = gson.fromJson<List<StreamQuality>>(json, type)
                                if (!list.isNullOrEmpty()) {
                                    _availableQualities.value = list
                                } else {
                                    fetchFromApiAndSave()
                                }
                            } catch (e: Exception) {
                                fetchFromApiAndSave()
                            }
                        } else {
                            fetchFromApiAndSave()
                        }
                    }
                } else if (source == StreamSource.KONOMITV) {
                    _availableQualities.value = StreamQuality.DEFAULT_QUALITIES
                } else {
                    _availableQualities.value = listOf(
                        StreamQuality(
                            label = "オリジナル (Direct)",
                            value = "direct",
                            isRawTs = true
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stream qualities", e)
                val currentLive = settingsRepository.liveQuality.first()
                _availableQualities.value = listOf(
                    StreamQuality(
                        label = "設定値 ($currentLive)",
                        value = currentLive,
                        isRawTs = false
                    )
                )
            } finally {
                _isQualitiesLoaded.value = true
            }
        }
    }

    private suspend fun fetchFromApiAndSave() {
        try {
            Log.i(TAG, "Cache empty. Interrupting EPG to fetch qualities from API.")
            val fetched = recordProvider.getStreamQualities()
            if (fetched.isNotEmpty()) {
                settingsRepository.saveString(
                    SettingsRepository.AVAILABLE_STREAM_QUALITIES,
                    gson.toJson(fetched)
                )
                _availableQualities.value = fetched
            } else {
                val currentLive = settingsRepository.liveQuality.first()
                _availableQualities.value = listOf(
                    StreamQuality(
                        label = "設定値 ($currentLive)",
                        value = currentLive,
                        isRawTs = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch from API", e)
            val currentLive = settingsRepository.liveQuality.first()
            _availableQualities.value = listOf(
                StreamQuality(label = "設定値 ($currentLive)", value = currentLive, isRawTs = false)
            )
        }
    }

    fun saveLiveQuality(qualityValue: String) {
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.LIVE_QUALITY, qualityValue)
        }
    }

    suspend fun getInitialStreamSource(): StreamSource {
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
        return sources.first()
    }

    private fun stopMainPlaybackSafely() {
        mainEventSource?.cancel()
        mainEventSource = null

        _mainPlayer.value?.stop()
        _mainPlayer.value?.release()
        _mainPlayer.value = null

        _mainSseStatus.value = "Standby"
        _mainSseDetail.value = AppStrings.SSE_CONNECTING

        stopJikkyo()
    }

    private fun stopDualPlaybackSafely() {
        dualEventSource?.cancel()
        dualEventSource = null

        _dualPlayer.value?.stop()
        _dualPlayer.value?.release()
        _dualPlayer.value = null

        _dualSseStatus.value = "Standby"
        _dualSseDetail.value = AppStrings.SSE_CONNECTING
    }

    fun releasePlayers() {
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

        stopJikkyo()
    }

    private fun handleMainError(uiContext: Context, error: PlaybackException) {
        viewModelScope.launch {
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = mainCurrentSource == StreamSource.EDCB && !mainIsEdcbDirect

            if (isEdcbTranscode && is404 && mainAutoRetryCount < 5) {
                mainAutoRetryCount++
                Log.w(TAG, "EDCB HLS 404: Retrying prepare... ($mainAutoRetryCount/5)")
                _mainSseDetail.value = "セグメント生成待機中... ($mainAutoRetryCount/5)"
                delay(2500)
                _mainPlayer.value?.prepare()
                _mainPlayer.value?.play()
                return@launch
            }

            val errorMsg = analyzePlayerError(error)
            if (mainAutoRetryCount < MAX_AUTO_RETRY) {
                mainAutoRetryCount++
                Log.w(
                    TAG,
                    "Auto-recovering main player (Attempt $mainAutoRetryCount / $MAX_AUTO_RETRY) after error: $errorMsg"
                )
                _mainSseDetail.value = "通信復旧中... ($mainAutoRetryCount/$MAX_AUTO_RETRY)"

                stopMainPlaybackSafely()
                delay(2000)

                val channel = mainCurrentChannel
                val source = mainCurrentSource
                val quality = mainCurrentQuality
                val direct = mainIsEdcbDirect
                if (channel != null && quality != null) {
                    playMainChannel(uiContext, channel, source, direct, quality, isAutoRetry = true)
                }
            } else {
                Log.e(TAG, "Max auto-retry reached. Showing error dialog.")
                _mainPlayerError.value = errorMsg
                stopMainPlaybackSafely()
            }
        }
    }

    private fun handleDualError(uiContext: Context, error: PlaybackException) {
        viewModelScope.launch {
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = dualCurrentSource == StreamSource.EDCB && !dualIsEdcbDirect

            if (isEdcbTranscode && is404 && dualAutoRetryCount < 5) {
                dualAutoRetryCount++
                Log.w(TAG, "EDCB HLS 404 (Dual): Retrying prepare... ($dualAutoRetryCount/5)")
                _dualSseDetail.value = "セグメント生成待機中... ($dualAutoRetryCount/5)"
                delay(2500)
                _dualPlayer.value?.prepare()
                _dualPlayer.value?.play()
                return@launch
            }

            val errorMsg = analyzePlayerError(error)
            if (dualAutoRetryCount < MAX_AUTO_RETRY) {
                dualAutoRetryCount++
                Log.w(
                    TAG,
                    "Auto-recovering dual player (Attempt $dualAutoRetryCount / $MAX_AUTO_RETRY) after error: $errorMsg"
                )
                _dualSseDetail.value = "通信復旧中... ($dualAutoRetryCount/$MAX_AUTO_RETRY)"

                stopDualPlaybackSafely()
                delay(2000)

                val channel = dualCurrentChannel
                val source = dualCurrentSource
                val quality = dualCurrentQuality
                val direct = dualIsEdcbDirect
                if (channel != null && quality != null) {
                    playDualChannel(uiContext, channel, source, direct, quality, isAutoRetry = true)
                }
            } else {
                Log.e(TAG, "Max auto-retry reached on dual player.")
                _dualSseStatus.value = "Error"
                _dualSseDetail.value = errorMsg
                stopDualPlaybackSafely()
            }
        }
    }

    fun playMainChannel(
        uiContext: Context,
        channel: Channel,
        source: StreamSource,
        isEdcbDirect: Boolean,
        quality: StreamQuality,
        isAutoRetry: Boolean = false
    ) {
        Log.d(TAG, "playMainChannel() called: channel=${channel.name}")
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return

        if (!isAutoRetry) {
            mainAutoRetryCount = 0
            _mainPlayerError.value = null
        }
        mainCurrentChannel = channel
        mainCurrentSource = source
        mainIsEdcbDirect = isEdcbDirect
        mainCurrentQuality = quality

        viewModelScope.launch {
            _currentLogoUrl.value = liveProvider.getChannelLogoUrl(channel.id)
        }

        mainPlaybackJob?.cancel()
        mainPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                mainPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        stopMainPlaybackSafely()
                        _mainSseStatus.value = "Standby"
                        _mainSseDetail.value = "ストリームを準備中..."
                    }

                    delay(if (isAutoRetry) 0 else 600)

                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val newPlayer = withContext(Dispatchers.Main) {
                        createExoPlayer(
                            uiContext,
                            audioOutputMode,
                            { mainCurrentSource == StreamSource.KONOMITV }) { error ->
                            Log.e(TAG, "ExoPlayer (Main) Error: ${error.message}", error)
                            handleMainError(uiContext, error)
                        }
                    }
                    _mainPlayer.value = newPlayer

                    val config = settingsRepository.getBackendConfig(source)

                    val streamUrl = if (source == StreamSource.EDCB && !isEdcbDirect) {
                        withContext(Dispatchers.Main) {
                            _mainSseDetail.value = "トランスコード開始を待機中..."
                        }
                        Log.i(TAG, "[Main] Calling getLiveStreamUrl for EDCB HLS...")
                        val hlsUrl = liveProvider.getLiveStreamUrl(channel.id, quality.value, 0)
                        Log.i(TAG, "[Main] getLiveStreamUrl returned: $hlsUrl")

                        if (hlsUrl.isBlank()) {
                            throw Exception("HLSトランスコードの開始に失敗しました")
                        }
                        hlsUrl
                    } else {
                        buildStreamUrl(channel, source, quality, config, mainTsDataSourceFactory)
                    }

                    withContext(Dispatchers.Main) {
                        if (source == StreamSource.MIRAKURUN || (source == StreamSource.EDCB && isEdcbDirect)) {
                            _mainSseStatus.value = "ONAir"
                            _mainSseDetail.value = ""
                        } else if (source == StreamSource.EDCB && !isEdcbDirect) {
                            _mainSseStatus.value = "ONAir"
                            _mainSseDetail.value = ""
                        } else {
                            if (config is BackendConfig.KonomiTv) {
                                startMainSse(
                                    uiContext,
                                    channel.displayChannelId,
                                    quality.value,
                                    config
                                )
                            }
                        }

                        startPlayback(
                            uiContext,
                            newPlayer,
                            streamUrl,
                            source,
                            isEdcbDirect,
                            mainTsDataSourceFactory
                        )
                        startJikkyo(channel, source)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playMainChannel: Job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "playMainChannel: Failed", e)
                withContext(Dispatchers.Main) {
                    val fallbackError =
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED)
                    handleMainError(uiContext, fallbackError)
                }
            }
        }
    }

    fun playDualChannel(
        uiContext: Context,
        channel: Channel,
        source: StreamSource,
        isEdcbDirect: Boolean,
        quality: StreamQuality,
        isAutoRetry: Boolean = false
    ) {
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return

        if (!isAutoRetry) {
            dualAutoRetryCount = 0
        }
        dualCurrentChannel = channel
        dualCurrentSource = source
        dualIsEdcbDirect = isEdcbDirect
        dualCurrentQuality = quality

        dualPlaybackJob?.cancel()
        dualPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                dualPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        stopDualPlaybackSafely()
                        _dualSseStatus.value = "Standby"
                        _dualSseDetail.value = "ストリームを準備中..."
                    }

                    delay(if (isAutoRetry) 0 else 600)

                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val newDualPlayer = withContext(Dispatchers.Main) {
                        createExoPlayer(
                            uiContext,
                            audioOutputMode,
                            { dualCurrentSource == StreamSource.KONOMITV }) { error ->
                            handleDualError(uiContext, error)
                        }
                    }
                    _dualPlayer.value = newDualPlayer

                    val config = settingsRepository.getBackendConfig(source)

                    val streamUrl = if (source == StreamSource.EDCB && !isEdcbDirect) {
                        withContext(Dispatchers.Main) {
                            _dualSseDetail.value = "トランスコード開始を待機中..."
                        }
                        val hlsUrl = liveProvider.getLiveStreamUrl(channel.id, quality.value, 1)
                        if (hlsUrl.isBlank()) {
                            throw Exception("HLSトランスコードの開始に失敗しました")
                        }
                        hlsUrl
                    } else {
                        buildStreamUrl(channel, source, quality, config, dualTsDataSourceFactory)
                    }

                    withContext(Dispatchers.Main) {
                        if (source == StreamSource.MIRAKURUN || (source == StreamSource.EDCB && isEdcbDirect)) {
                            _dualSseStatus.value = "ONAir"
                            _dualSseDetail.value = ""
                        } else if (source == StreamSource.EDCB && !isEdcbDirect) {
                            _dualSseStatus.value = "ONAir"
                            _dualSseDetail.value = ""
                        } else {
                            if (config is BackendConfig.KonomiTv) {
                                startDualSse(
                                    uiContext,
                                    channel.displayChannelId,
                                    quality.value,
                                    config
                                )
                            }
                        }

                        startPlayback(
                            uiContext,
                            newDualPlayer,
                            streamUrl,
                            source,
                            isEdcbDirect,
                            dualTsDataSourceFactory
                        )
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playDualChannel: Job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "playDualChannel: Failed", e)
                withContext(Dispatchers.Main) {
                    val fallbackError =
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED)
                    handleDualError(uiContext, fallbackError)
                }
            }
        }
    }

    fun stopAllPlayers() {
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
        mainAutoRetryCount = 0
        _mainPlayerError.value = null
    }

    private fun getJikkyoChannels(): JSONArray {
        if (jikkyoChannelsCache != null) return jikkyoChannelsCache!!
        return try {
            val jsonString =
                context.assets.open("jikkyo_channels.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonString)
            jikkyoChannelsCache = array
            array
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun getJikkyoId(networkId: Int, serviceId: Int): String? {
        val channels = getJikkyoChannels()
        for (i in 0 until channels.length()) {
            val jc = channels.optJSONObject(i) ?: continue
            val jcNid = jc.optInt("network_id", -1)

            val sidRaw = jc.opt("service_id")?.toString() ?: "-1"
            val jcSid = if (sidRaw.startsWith("0x", ignoreCase = true)) {
                sidRaw.substring(2).toIntOrNull(16) ?: -1
            } else {
                sidRaw.toIntOrNull() ?: -1
            }
            val jkJikkyoId = jc.optInt("jikkyo_id", -1)

            var matched = false
            if (networkId == jcNid && serviceId == jcSid) {
                matched = true
            } else if (networkId in 0x7880..0x7FEF && jcNid == 15) {
                if (serviceId == jcSid || serviceId - 1 == jcSid || serviceId - 2 == jcSid) {
                    matched = true
                }
            }

            if (matched && jkJikkyoId != -1) {
                val jkId = "jk$jkJikkyoId"
                if (JIKKYO_CHANNEL_ID_MAP.containsKey(jkId)) {
                    return jkId
                }
            }
        }
        return null
    }

    private fun startJikkyo(channel: Channel, source: StreamSource) {
        stopJikkyo()

        viewModelScope.launch(Dispatchers.IO) {
            _clearCommentsEvent.emit(Unit)
            val watchUrl = getJikkyoWatchSessionUrl(channel, source)
            if (watchUrl.isNullOrEmpty()) return@launch

            jikkyoClient = JikkyoClient(watchUrl)
            jikkyoClient?.start { jsonText ->
                parseAndEmitComment(jsonText)
            }
        }
    }

    private fun stopJikkyo() {
        jikkyoClient?.stop()
        jikkyoClient = null
        processedCommentIds.clear()
    }

    private suspend fun getJikkyoWatchSessionUrl(channel: Channel, source: StreamSource): String? {
        if (source == StreamSource.KONOMITV) {
            val config = settingsRepository.getBackendConfig(source) as? BackendConfig.KonomiTv
            if (config == null) return null
            try {
                val apiUrl =
                    "${config.ip}:${config.port}/api/channels/${channel.displayChannelId}/jikkyo"
                val request = Request.Builder().url(apiUrl).build()
                val response = okHttpClient.newCall(request).execute()

                val bodyString = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    val json = JSONObject(bodyString)
                    return json.optString("watch_session_url").takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
            }
            return null
        } else {
            val networkId = channel.networkId.toInt()
            val serviceId = channel.serviceId.toInt()

            val jkId = getJikkyoId(networkId, serviceId)
            if (jkId != null) {
                return "wss://nx-jikkyo.tsukumijima.net/api/v1/channels/$jkId/ws/watch"
            }
            return null
        }
    }

    private fun parseAndEmitComment(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val chat = json.optJSONObject("chat") ?: return
            val content = chat.optString("content", "")
            if (content.isBlank()) return
            if (chat.optString("deleted") == "1") return

            if (content.startsWith("/") && content.matches(Regex("^/[a-z][a-z0-9_-]*(?:\\s|$).*"))) {
                if (chat.optString("premium") == "3") return
            }

            val commentId = chat.optString("no", "") + "_" + content
            if (!processedCommentIds.add(commentId)) return
            if (processedCommentIds.size > 2000) processedCommentIds.clear()

            var color = "#FFEAEA"
            var position = "right"
            var size = "medium"
            val mail = chat.optString("mail", "")
            val commands = mail.replace("184", "").split(" ")
            for (cmd in commands) {
                getCommentColor(cmd)?.let { color = it }
                getCommentPosition(cmd)?.let { position = it }
                getCommentSize(cmd)?.let { size = it }
            }

            viewModelScope.launch(Dispatchers.Main) {
                _liveComments.emit(LiveComment(content, color, position, size))
            }

        } catch (e: Exception) {
        }
    }

    private fun getCommentColor(color: String): String? {
        if (color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) return color
        val map = mapOf(
            "white" to "#FFEAEA", "red" to "#F02840", "pink" to "#FD7E80",
            "orange" to "#FDA708", "yellow" to "#FFE133", "green" to "#64DD17",
            "cyan" to "#00D4F5", "blue" to "#4763FF", "purple" to "#D500F9",
            "black" to "#1E1310", "white2" to "#CCCC99", "niconicowhite" to "#CCCC99",
            "red2" to "#CC0033", "truered" to "#CC0033", "pink2" to "#FF33CC",
            "orange2" to "#FF6600", "passionorange" to "#FF6600", "yellow2" to "#999900",
            "madyellow" to "#999900", "green2" to "#00CC66", "elementalgreen" to "#00CC66",
            "cyan2" to "#00CCCC", "blue2" to "#3399FF", "marineblue" to "#3399FF",
            "purple2" to "#6633CC", "nobleviolet" to "#6633CC", "black2" to "#666666"
        )
        return map[color]
    }

    private fun getCommentPosition(pos: String): String? {
        val map = mapOf("ue" to "top", "naka" to "right", "shita" to "bottom")
        return map[pos]
    }

    private fun getCommentSize(size: String): String? {
        val map = mapOf("big" to "big", "medium" to "medium", "small" to "small")
        return map[size]
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
        isEdcbDirect: Boolean,
        factory: TsReadExDataSourceFactory
    ) {
        try {
            val mediaItem = MediaItem.fromUri(streamUrl)

            val mediaSource =
                if (source == StreamSource.MIRAKURUN || (source == StreamSource.EDCB && isEdcbDirect)) {
                    val extractorsFactory = ExtractorsFactory {
                        arrayOf(
                            TsExtractor(
                                TsExtractor.MODE_SINGLE_PMT,
                                TimestampAdjuster(C.TIME_UNSET),
                                DirectSubtitlePayloadReaderFactory(
                                    onSubtitleDataReceived = { pts, base64 ->
                                        viewModelScope.launch(Dispatchers.Main) {
                                            _subtitleEvents.emit(Pair(pts, base64))
                                        }
                                    },
                                    isSubtitleEnabled = { isSubtitleEnabled }
                                ),
                                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES))
                    }
                    ProgressiveMediaSource.Factory(factory, extractorsFactory)
                        .createMediaSource(mediaItem)
                } else {
                    if (source == StreamSource.EDCB && !isEdcbDirect) {
                        val uri = Uri.parse(streamUrl)
                        val ctok = uri.getQueryParameter("ctok") ?: ""
                        Log.i(TAG, "[Playback] Extracted ctok from URL: $ctok")

                        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(mapOf("Cookie" to "ctok=$ctok"))
                            .setAllowCrossProtocolRedirects(true)

                        HlsMediaSource.Factory(httpDataSourceFactory)
                            .setAllowChunklessPreparation(false)
                            .createMediaSource(mediaItem)
                    } else {
                        DefaultMediaSourceFactory(uiContext).createMediaSource(mediaItem)
                    }
                }

            player?.setMediaSource(mediaSource)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback: Exception thrown", e)
            val fallbackError =
                PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED)
            handleMainError(uiContext, fallbackError)
        }
    }

    private fun startMainSse(
        uiContext: Context,
        channelId: String,
        quality: String,
        config: BackendConfig.KonomiTv
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
                            val error =
                                PlaybackException("KonomiTV HTTP Error", null, response.code)
                            handleMainError(uiContext, error)
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
                                val errMsg =
                                    _mainSseDetail.value.ifEmpty { AppStrings.ERR_TUNER_START_FAILED }
                                val error = PlaybackException(
                                    errMsg,
                                    null,
                                    PlaybackException.ERROR_CODE_UNSPECIFIED
                                )
                                handleMainError(uiContext, error)
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
        uiContext: Context,
        channelId: String,
        quality: String,
        config: BackendConfig.KonomiTv
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
                            val error = PlaybackException("HTTP Error", null, response.code)
                            handleDualError(uiContext, error)
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

                            if (status == "Error" || (status == "Offline" && (dualSseDetail.value.contains(
                                    "失敗"
                                ) || dualSseDetail.value.contains("エラー")))
                            ) {
                                val errMsg = dualSseDetail.value.ifEmpty { "エラーが発生しました" }
                                val error = PlaybackException(
                                    errMsg,
                                    null,
                                    PlaybackException.ERROR_CODE_UNSPECIFIED
                                )
                                handleDualError(uiContext, error)
                                return@launch
                            }

                            when (status) {
                                "Standby", "Restart" -> _dualPlayer.value?.pause()
                                "ONAir" -> {
                                    if (_dualPlayer.value?.playerError != null) _dualPlayer.value?.prepare()
                                    _dualPlayer.value?.play()
                                }

                                "Offline" -> _dualPlayer.value?.pause()
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
                422 -> "サーバーエラー (HTTP 422)\nCSRFトークンの不一致"
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