package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.beeregg2001.komorebi.data.KonomiOriginalQualityGate
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationLiveRepository
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.response.respondText
import io.ktor.server.request.receiveParameters
import io.ktor.http.ContentType
import io.ktor.server.application.call
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Keep
data class PostRecordingBatch(
    val name: String,
    val path: String
)

@Keep
data class SmbServer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val port: String,
    val user: String,
    val password: String
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: RecordSyncEngine,
    private val recordProvider: RecordProvider,
    // ★ 追加: EPGStationはライブ配信(m2ts:n等)と録画配信(mp4:n/hls:n/webm:n等)で
    // 画質の値空間が別なため、録画用のrecordProvider.getStreamQualities()とは別に
    // ライブ用の画質一覧を取得する必要がある(詳細はforceSyncStreamQualities()参照)。
    private val epgStationLiveRepository: EpgStationLiveRepository,
    private val appUpdater: AppUpdater,
    private val db: AppDatabase
) : ViewModel() {

    private val gson = Gson()

    private val _dynamicQualities = MutableStateFlow<List<StreamQuality>?>(null)
    // ★ 追加: EPGStationのライブ画質一覧専用のキャッシュ。EDCB/KonomiTVはライブ・録画で
    // 同じ画質空間を共有するため_dynamicQualitiesをそのまま使い、これはEPGSTATIONの
    // ときだけ実体を持つ。
    private val _liveDynamicQualities = MutableStateFlow<List<StreamQuality>?>(null)

    val availableQualities: StateFlow<List<StreamQuality>> = combine(
        _dynamicQualities,
        settingsRepository.availableStreamQualities,
        settingsRepository.backendType,
        settingsRepository.liveQuality,
        settingsRepository.videoQuality
    ) { dynamicList, json, backend, currentLive, currentVideo ->
        // ★ 修正: 以前はEDCBかそれ以外の2択で、EPGStationをKonomiTVと同一視していたため、
        // 設定画面の「デフォルト画質」ダイアログにEPGStationの実画質(m2ts:n等)が出せず、
        // KonomiTV用のDEFAULT_QUALITIES(original/1080p-60fps等)しか選べなかった。
        // その状態で決定すると、現在値の照合が必ず不一致になりダイアログは常に先頭
        // "original"を選択済みとして開くため、決定するとLIVE_QUALITY="original"のような
        // 無効な値が保存され、再生中の画質設定が黙って上書きされてしまっていた。
        // EDCBと同じ「動的取得→キャッシュJSON→設定値フォールバック」のロジックを適用する。
        if (backend == "EDCB" || backend == "EPGSTATION") {
            if (dynamicList != null && dynamicList.isNotEmpty()) {
                return@combine dynamicList
            }

            if (json.isNotBlank()) {
                try {
                    val type = object : TypeToken<List<StreamQuality>>() {}.type
                    val list = gson.fromJson<List<StreamQuality>>(json, type) ?: emptyList()
                    if (list.isNotEmpty()) return@combine list
                } catch (e: Exception) {
                }
            }

            val dummyList = mutableListOf<StreamQuality>()
            if (currentLive.isNotBlank()) {
                dummyList.add(
                    StreamQuality(
                        label = "設定値 ($currentLive)",
                        value = currentLive,
                        isRawTs = false
                    )
                )
            }
            if (currentVideo.isNotBlank() && currentVideo != currentLive) {
                dummyList.add(
                    StreamQuality(
                        label = "設定値 ($currentVideo)",
                        value = currentVideo,
                        isRawTs = false
                    )
                )
            }

            if (dummyList.isEmpty()) StreamQuality.DEFAULT_QUALITIES else dummyList
        } else {
            StreamQuality.DEFAULT_QUALITIES
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreamQuality.DEFAULT_QUALITIES)

    /**
     * ライブ視聴の「デフォルト画質」ダイアログ専用の画質一覧。
     *
     * ★ 追加: 以前は設定画面の「ライブ配信の画質」「録画視聴の画質」ダイアログが同じ
     * [availableQualities] を共有していた。EDCB(resolver.luaの単一画質空間)や
     * KonomiTV(original/1080p-60fps等をライブ・録画で共有)ではこれで問題ないが、
     * EPGStationはライブ(m2ts:n / m2tsll:n / hls:n)と録画(direct / mp4:n / hls:n / webm:n)で
     * 値空間が別であり、[availableQualities]は録画用のrecordProvider.getStreamQualities()から
     * 構築される。これをライブ画質ダイアログにも流用していたため、ダイアログでの現在値照合が
     * 常に不一致になり、決定するたびにLIVE_QUALITYが録画用の値("direct"等)へ黙って
     * 上書きされてしまっていた(forceSyncStreamQualities()側の自動同期でも同様の理由で
     * 毎回LIVE_QUALITYがリセットされていた)。EPGSTATIONのときだけライブ専用の取得元
     * (epgStationLiveRepository.getLiveStreamQualities())を使うよう分離する。
     */
    val liveAvailableQualities: StateFlow<List<StreamQuality>> = combine(
        _liveDynamicQualities,
        availableQualities,
        settingsRepository.backendType,
        settingsRepository.liveQuality
    ) { liveDynamicList, fallbackList, backend, currentLive ->
        if (backend == "EPGSTATION") {
            if (liveDynamicList != null && liveDynamicList.isNotEmpty()) {
                liveDynamicList
            } else if (currentLive.isNotBlank()) {
                listOf(StreamQuality(label = "設定値 ($currentLive)", value = currentLive, isRawTs = false))
            } else {
                StreamQuality.DEFAULT_QUALITIES
            }
        } else if (backend == "KONOMITV" && KonomiOriginalQualityGate.isUnsupported()) {
            // ★ 追加: KonomiTVのOriginal画質(ライブ)がサーバー側で非対応と判明した後も、
            // この設定画面の一覧(availableQualitiesのelse分岐由来)には常にDEFAULT_QUALITIES
            // (originalを含む)がそのまま出ていたため、ここで選ぶと再び422で失敗する画質が
            // 選べてしまっていた。LivePlayerViewModel側のフィルタと同じ条件をここにも適用する。
            fallbackList.filterNot { it.value == "original" }
        } else {
            // EDCB/KonomiTV/その他はライブ・録画で画質空間を共有しているため、
            // 従来通りavailableQualities(録画用と同じ取得元)をそのまま使う。
            fallbackList
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreamQuality.DEFAULT_QUALITIES)

    val totalRecordCount: StateFlow<Int> = db.recordedProgramDao().getTotalCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncedAt: StateFlow<Long> = db.syncMetaDao().getSyncMetaFlow()
        .map { it?.lastSyncedAt ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val backendType: StateFlow<String> = settingsRepository.backendType.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "KONOMITV"
    )
    val edcbIp: StateFlow<String> =
        settingsRepository.edcbIp.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val edcbPort: StateFlow<String> = settingsRepository.edcbPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "5510"
    )
    val edcbRecordPlayMethod: StateFlow<String> = settingsRepository.edcbRecordPlayMethod.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "API"
    )
    val epgStationIp: StateFlow<String> = settingsRepository.epgStationIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val epgStationPort: StateFlow<String> = settingsRepository.epgStationPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "8888"
    )
    val mirakurunIp: StateFlow<String> = settingsRepository.mirakurunIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val konomiIp: StateFlow<String> = settingsRepository.konomiIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "https://192-168-xxx-xxx.local.konomi.tv"
    )
    val konomiPort: StateFlow<String> = settingsRepository.konomiPort.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "7000"
    )
    val commentSpeed: StateFlow<String> = settingsRepository.commentSpeed.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1.0"
    )
    val commentFontSize: StateFlow<String> = settingsRepository.commentFontSize.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1.0"
    )
    val commentOpacity: StateFlow<String> = settingsRepository.commentOpacity.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1.0"
    )
    val commentMaxLines: StateFlow<String> = settingsRepository.commentMaxLines.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "0"
    )
    val commentDefaultDisplay: StateFlow<String> = settingsRepository.commentDefaultDisplay.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "ON"
    )
    val liveQuality: StateFlow<String> = settingsRepository.liveQuality.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1080p-60fps"
    )
    val videoQuality: StateFlow<String> = settingsRepository.videoQuality.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "1080p-60fps"
    )
    val liveSubtitleDefault: StateFlow<String> = settingsRepository.liveSubtitleDefault.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val videoSubtitleDefault: StateFlow<String> = settingsRepository.videoSubtitleDefault.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val subtitleCommentLayer: StateFlow<String> = settingsRepository.subtitleCommentLayer.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "CommentOnTop"
    )
    val audioOutputMode: StateFlow<String> = settingsRepository.audioOutputMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "DOWNMIX"
    )
    val playerUiMode: StateFlow<String> = settingsRepository.playerUiMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "MODERN"
    )
    val autoCmSkip: StateFlow<String> = settingsRepository.autoCmSkip.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val labAnnictIntegration: StateFlow<String> = settingsRepository.labAnnictIntegration.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val labShobocalIntegration: StateFlow<String> =
        settingsRepository.labShobocalIntegration.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            "OFF"
        )
    val labAllowMirakurunDual: StateFlow<String> = settingsRepository.labAllowMirakurunDual.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val defaultPostCommand: StateFlow<String> = settingsRepository.defaultPostCommand.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )
    val startupChannel: StateFlow<String> = settingsRepository.startupChannel.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "OFF"
    )
    val timeFormat: StateFlow<String> = settingsRepository.timeFormat.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "24H"
    )
    val startupTab: StateFlow<String> = settingsRepository.startupTab.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "ホーム"
    )
    val appTheme: StateFlow<String> = settingsRepository.appTheme.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "MONOTONE"
    )
    val receiveBetaUpdates: StateFlow<Boolean> = settingsRepository.receiveBetaUpdates.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    // ★ 追加: 設定画面からの手動アップデート確認用。AppUpdaterはSingletonのため、
    // ここで発火した確認結果はホーム画面のアップデートダイアログにもそのまま反映される。
    val updateCheckState: StateFlow<UpdateState> = appUpdater.updateState

    private val _hasManuallyCheckedForUpdate = MutableStateFlow(false)
    val hasManuallyCheckedForUpdate: StateFlow<Boolean> = _hasManuallyCheckedForUpdate.asStateFlow()

    fun checkForUpdatesManually() {
        viewModelScope.launch {
            val receiveBeta = settingsRepository.receiveBetaUpdates.first()
            appUpdater.checkForUpdates(receiveBetaUpdates = receiveBeta)
            _hasManuallyCheckedForUpdate.value = true
        }
    }
    val isSettingsInitialized: StateFlow<Boolean> = settingsRepository.isInitialized.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )
    val hideSubChannels: StateFlow<Boolean> = settingsRepository.hideSubChannels.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val geminiApiKey: StateFlow<String> = settingsRepository.geminiApiKey.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )

    // ★ 追加: Cloudflare Zero Trust サービストークン
    val cfAccessClientId: StateFlow<String> = settingsRepository.cfAccessClientId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val cfAccessClientSecret: StateFlow<String> = settingsRepository.cfAccessClientSecret
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ★ 追加: APIキーの検証結果("VALID"/"INVALID"/"UNVERIFIED"/未検証時は空文字)
    val geminiApiKeyStatus: StateFlow<String> = settingsRepository.geminiApiKeyStatus.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )

    private val _isValidatingGeminiApiKey = MutableStateFlow(false)
    val isValidatingGeminiApiKey: StateFlow<Boolean> = _isValidatingGeminiApiKey

    /**
     * GeminiのAPIキーを保存する前に、Google側へ軽量な疎通確認(countTokens)を行い実際に有効かどうかを検証する。
     * ネットワーク不通など有効性を断定できない場合は「無効」ではなく「未確認」として保存する。
     */
    fun saveAndValidateGeminiApiKey(rawKey: String) {
        val key = rawKey.trim()
        viewModelScope.launch {
            if (key.isBlank()) {
                settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY, "")
                settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY_STATUS, "")
                return@launch
            }

            _isValidatingGeminiApiKey.value = true
            val status = withContext(Dispatchers.IO) {
                try {
                    GenerativeModel(modelName = "gemini-3-flash-preview", apiKey = key)
                        .countTokens("疎通確認")
                    "VALID"
                } catch (e: InvalidAPIKeyException) {
                    "INVALID"
                } catch (e: Exception) {
                    Log.w("SettingsViewModel", "Geminiキーの検証に失敗(通信エラー等)", e)
                    "UNVERIFIED"
                }
            }
            settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY, key)
            settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY_STATUS, status)
            _isValidatingGeminiApiKey.value = false
        }
    }

    fun clearGeminiApiKey() {
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY, "")
            settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY_STATUS, "")
        }
    }

    // ★ 追加: 番組表設定の StateFlow
    val epgColumnCount: StateFlow<String> = settingsRepository.epgColumnCount.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "7"
    )
    val epgFontSizeScale: StateFlow<String> = settingsRepository.epgFontSizeScale.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0"
    )
    val epgVisibleHours: StateFlow<String> = settingsRepository.epgVisibleHours.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "6"
    )

    val smbServerList: StateFlow<List<SmbServer>> = settingsRepository.smbServerList
        .map { json ->
            try {
                val type = object : TypeToken<List<SmbServer>>() {}.type
                gson.fromJson<List<SmbServer>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val postRecordingBatchList: StateFlow<List<PostRecordingBatch>> =
        settingsRepository.postRecordingBatchList
            .map { json ->
                try {
                    val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
                    gson.fromJson<List<PostRecordingBatch>>(json, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteBaseballTeams: StateFlow<Set<String>> = settingsRepository.favoriteBaseballTeams
        .map { json ->
            try {
                val type = object : TypeToken<Set<String>>() {}.type
                gson.fromJson<Set<String>>(json, type) ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _smbServerAddedEvent = MutableSharedFlow<String>()
    val smbServerAddedEvent = _smbServerAddedEvent.asSharedFlow()

    init {
        // ★ 修正: 初期化時の重い同期処理をバックグラウンドに回し、UI描画後に遅らせる
        viewModelScope.launch {
            delay(1500)
            forceSyncStreamQualities()
        }
    }

    // ★ 追加: init内の自動同期は「アプリ起動後1.5秒経過時点で1回だけ」しか行われないため、
    // その瞬間にEDCBのresolver.lua取得がたまたま失敗すると(サーバー起動タイミング・
    // 一時的な通信不調等)、以後リトライされず、_dynamicQualities が空のまま固定されてしまう。
    // 結果、設定画面の「再生設定」の画質選択ダイアログが「設定値」ベースの
    // ダミー1〜2件だけの縮退リストになり、実質選べない状態になっていた
    // (Komorebi側の設定変更(IP/ポート/再生方式)を行わない限り再同期のきっかけが無かった)。
    // ユーザーが実際に設定画面(再生設定タブ)を開いたタイミングは「今まさにこのデータを
    // 見ようとしている」という強いシグナルなので、そのタイミングで確実に取り直せるよう
    // 公開関数として用意する。
    fun refreshStreamQualities() {
        viewModelScope.launch {
            forceSyncStreamQualities()
        }
    }

    private suspend fun forceSyncStreamQualities() {
        withContext(Dispatchers.IO) {
            try {
                val backend = settingsRepository.backendType.first()
                val preLive = settingsRepository.liveQuality.first()
                val preVideo = settingsRepository.videoQuality.first()

                if (backend == "EDCB") {
                    // EDCBはライブ・録画で画質空間を共有しているため、従来通り単一の
                    // 取得元(recordProvider.getStreamQualities() = resolver.lua)を両方に適用する。
                    val fetched = recordProvider.getStreamQualities()
                    if (fetched.isNotEmpty()) {
                        _dynamicQualities.value = fetched
                        settingsRepository.saveString(
                            SettingsRepository.AVAILABLE_STREAM_QUALITIES,
                            gson.toJson(fetched)
                        )

                        val postLive = settingsRepository.liveQuality.first()
                        val postVideo = settingsRepository.videoQuality.first()

                        if (preLive == postLive && fetched.none { it.value == postLive }) {
                            settingsRepository.saveString(
                                SettingsRepository.LIVE_QUALITY,
                                fetched.first().value
                            )
                        }
                        if (preVideo == postVideo && fetched.none { it.value == postVideo }) {
                            settingsRepository.saveString(
                                SettingsRepository.VIDEO_QUALITY,
                                fetched.first().value
                            )
                        }
                    } else {
                        _dynamicQualities.value = emptyList()
                    }
                } else if (backend == "EPGSTATION") {
                    // ★ 修正: EPGStationはライブ(m2ts:n等)と録画(direct/mp4:n/hls:n/webm:n)で
                    // 画質の値空間が別。以前はrecordProvider.getStreamQualities()(録画用)の
                    // 結果をLIVE_QUALITYの照合・上書きにも使っていたため、ほぼ必ず不一致となり
                    // 起動のたびにLIVE_QUALITYが録画用の値("direct"等)へ黙って上書きされていた。
                    // 録画用とライブ用を別々に取得・同期する。
                    val videoFetched = recordProvider.getStreamQualities()
                    if (videoFetched.isNotEmpty()) {
                        _dynamicQualities.value = videoFetched
                        settingsRepository.saveString(
                            SettingsRepository.AVAILABLE_STREAM_QUALITIES,
                            gson.toJson(videoFetched)
                        )
                        val postVideo = settingsRepository.videoQuality.first()
                        if (preVideo == postVideo && videoFetched.none { it.value == postVideo }) {
                            settingsRepository.saveString(
                                SettingsRepository.VIDEO_QUALITY,
                                videoFetched.first().value
                            )
                        }
                    } else {
                        _dynamicQualities.value = emptyList()
                    }

                    val liveFetched = runCatching { epgStationLiveRepository.getLiveStreamQualities() }
                        .getOrElse {
                            Log.e("SettingsViewModel", "Failed to sync EPGStation live qualities", it)
                            emptyList()
                        }
                    if (liveFetched.isNotEmpty()) {
                        _liveDynamicQualities.value = liveFetched
                        val postLive = settingsRepository.liveQuality.first()
                        if (preLive == postLive && liveFetched.none { it.value == postLive }) {
                            settingsRepository.saveString(
                                SettingsRepository.LIVE_QUALITY,
                                liveFetched.first().value
                            )
                        }
                    } else {
                        _liveDynamicQualities.value = emptyList()
                    }
                } else if (backend == "KONOMITV") {
                    settingsRepository.saveString(SettingsRepository.AVAILABLE_STREAM_QUALITIES, "")
                    val defQualities = StreamQuality.DEFAULT_QUALITIES

                    val postLive = settingsRepository.liveQuality.first()
                    val postVideo = settingsRepository.videoQuality.first()

                    if (preLive == postLive && defQualities.none { it.value == postLive }) {
                        settingsRepository.saveString(
                            SettingsRepository.LIVE_QUALITY,
                            defQualities.first().value
                        )
                    }
                    if (preVideo == postVideo && defQualities.none { it.value == postVideo }) {
                        settingsRepository.saveString(
                            SettingsRepository.VIDEO_QUALITY,
                            defQualities.first().value
                        )
                    }
                } else {
                    settingsRepository.saveString(SettingsRepository.AVAILABLE_STREAM_QUALITIES, "")
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to sync stream qualities to cache", e)
                _dynamicQualities.value = emptyList()
            }
        }
    }

    // ★ 追加: 番組表設定の更新メソッド
    fun updateEpgColumnCount(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EPG_COLUMN_COUNT, value)
    }

    fun updateEpgFontSizeScale(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EPG_FONT_SIZE_SCALE, value)
    }

    fun updateEpgVisibleHours(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EPG_VISIBLE_HOURS, value)
    }

    fun updateBackendType(newType: String) = viewModelScope.launch(Dispatchers.IO) {
        val oldType = settingsRepository.backendType.first()
        if (oldType == newType) return@launch

        settingsRepository.saveString(SettingsRepository.BACKEND_TYPE, newType)
        try {
            db.clearAllTables()
            context.imageLoader.memoryCache?.clear()
            context.imageLoader.diskCache?.clear()
        } catch (e: Exception) {
        }
        syncEngine.launchSyncAllRecords(forceFullSync = true)
        forceSyncStreamQualities()
    }

    fun updateEdcbIp(ip: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(
            SettingsRepository.EDCB_IP,
            ip
        ); forceSyncStreamQualities()
    }

    fun updateEdcbPort(port: String) =
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveString(
                SettingsRepository.EDCB_PORT,
                port
            )
        }

    fun updateEdcbRecordPlayMethod(method: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(
            SettingsRepository.EDCB_RECORD_PLAY_METHOD,
            method
        ); forceSyncStreamQualities()
    }

    fun updateEpgStationIp(ip: String) = viewModelScope.launch(Dispatchers.IO) {
        val oldIp = settingsRepository.epgStationIp.first()
        settingsRepository.saveString(SettingsRepository.EPGSTATION_IP, ip)
        // 接続先が変わったら録画一覧を作り直す必要があるため、全同期をかけ直す
        if (oldIp.isNotBlank() && oldIp != ip) {
            syncEngine.launchSyncAllRecords(forceFullSync = true)
        }
        forceSyncStreamQualities()
    }

    fun updateEpgStationPort(port: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(SettingsRepository.EPGSTATION_PORT, port)
        forceSyncStreamQualities()
    }

    fun updateMirakurunIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveString(
                SettingsRepository.MIRAKURUN_IP,
                ip
            )
        }
    }

    fun updateKonomiIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldIp = settingsRepository.konomiIp.first()
            settingsRepository.saveString(SettingsRepository.KONOMI_IP, ip)
            if (oldIp != ip && (oldIp != "" && oldIp != "https://192-168-xxx-xxx.local.konomi.tv")) {
                syncEngine.launchSyncAllRecords(forceFullSync = true)
            }
        }
    }

    fun updateKonomiPort(port: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldPort = settingsRepository.konomiPort.first()
            settingsRepository.saveString(SettingsRepository.KONOMI_PORT, port)
            if (oldPort != port && (oldPort != "" && oldPort != "7000")) {
                syncEngine.launchSyncAllRecords(forceFullSync = true)
            }
        }
    }

    fun triggerFullSync() {
        viewModelScope.launch { syncEngine.launchSyncAllRecords(forceFullSync = true) }
    }

    fun addPostRecordingBatch(name: String, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.postRecordingBatchList.first()
            val currentList = try {
                val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
                gson.fromJson<List<PostRecordingBatch>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            currentList.add(PostRecordingBatch(name, path))
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(currentList)
            )
        }
    }

    fun deletePostRecordingBatch(batch: PostRecordingBatch) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.postRecordingBatchList.first()
            val currentList = try {
                val type = object : TypeToken<List<PostRecordingBatch>>() {}.type
                gson.fromJson<List<PostRecordingBatch>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            currentList.remove(batch)
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(currentList)
            )
        }
    }

    fun saveSmbServer(server: SmbServer) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.smbServerList.first()
            val currentList = try {
                val type = object : TypeToken<List<SmbServer>>() {}.type
                gson.fromJson<List<SmbServer>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            val existingIndex = currentList.indexOfFirst { it.id == server.id }
            if (existingIndex != -1) {
                currentList[existingIndex] = server
            } else {
                currentList.add(server)
            }
            settingsRepository.saveString(
                SettingsRepository.SMB_SERVER_LIST,
                gson.toJson(currentList)
            )
        }
    }

    fun deleteSmbServer(serverId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = settingsRepository.smbServerList.first()
            val currentList = try {
                val type = object : TypeToken<List<SmbServer>>() {}.type
                gson.fromJson<List<SmbServer>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val newList = currentList.filter { it.id != serverId }
            settingsRepository.saveString(
                SettingsRepository.SMB_SERVER_LIST,
                gson.toJson(newList)
            )
        }
    }

    fun updateFavoriteBaseballTeams(teams: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveString(
                SettingsRepository.FAVORITE_BASEBALL_TEAMS,
                gson.toJson(teams)
            )
        }
    }

    fun updateAppTheme(themeName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveString(
                SettingsRepository.APP_THEME,
                themeName
            )
        }
    }

    fun updateDefaultRecordListView(viewType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveString(
                SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                viewType
            )
        }
    }

    suspend fun getStartupTabOnce(): String {
        return settingsRepository.getStartupTabOnce()
    }

    fun updateStartupChannel(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(
            SettingsRepository.STARTUP_CHANNEL,
            value
        )
    }

    fun updateTimeFormat(value: String) = viewModelScope.launch(Dispatchers.IO) {
        settingsRepository.saveString(
            SettingsRepository.TIME_FORMAT,
            value
        )
    }

    fun toggleHideSubChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveBoolean(
                SettingsRepository.HIDE_SUB_CHANNELS,
                !settingsRepository.hideSubChannels.first()
            )
        }
    }

    private var ktorServer: ApplicationEngine? = null
    private val _localIpAddress = MutableStateFlow(getLocalIpAddress())
    val localIpAddress: StateFlow<String> = _localIpAddress

    fun startGeminiLocalServer() {
        if (ktorServer != null) return
        _localIpAddress.value = getLocalIpAddress()

        ktorServer = embeddedServer(CIO, port = 8081) {
            routing {
                get("/") { call.respondText(getSetupHtml(), ContentType.Text.Html) }
                post("/submit") {
                    val formParams = call.receiveParameters()
                    val apiKey = formParams["api_key"] ?: ""
                    if (apiKey.isNotBlank()) {
                        saveAndValidateGeminiApiKey(apiKey)
                        call.respondText(
                            "<html><body style='font-family:sans-serif; text-align:center; padding:50px; background:#e8f0fe;'><h2 style='color:#1a73e8;'>連携が完了しました！🎉</h2><p>テレビ画面を確認してください。この画面は閉じて大丈夫です。</p></body></html>",
                            ContentType.Text.Html
                        )
                    } else {
                        call.respondText(
                            "APIキーが空です。戻ってやり直してください。",
                            ContentType.Text.Plain
                        )
                    }
                }

                get("/smb") {
                    val targetId = call.request.queryParameters["id"]
                    val json = settingsRepository.smbServerList.first()
                    val currentList = try {
                        val type = object : TypeToken<List<SmbServer>>() {}.type
                        gson.fromJson<List<SmbServer>>(json, type) ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }

                    val targetServer = currentList.find { it.id == targetId }
                    call.respondText(getSmbSetupHtml(targetServer), ContentType.Text.Html)
                }

                post("/submit_smb") {
                    val formParams = call.receiveParameters()
                    val id =
                        formParams["id"]?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID()
                            .toString()
                    val name = formParams["name"] ?: ""
                    val ip = formParams["ip"] ?: ""
                    val port = formParams["port"]?.takeIf { it.isNotBlank() } ?: "445"
                    val user = formParams["user"] ?: ""
                    val password = formParams["password"] ?: ""

                    if (name.isNotBlank() && ip.isNotBlank()) {
                        saveSmbServer(SmbServer(id, name, ip, port, user, password))

                        _smbServerAddedEvent.emit(name)

                        val successHtml = """
                            <!DOCTYPE html>
                            <html lang="ja">
                            <head>
                                <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>送信完了</title>
                                <style>
                                    body { font-family: sans-serif; background-color: #f0f2f5; display: flex; justify-content: center; padding: 20px; } 
                                    .card { background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); width: 100%; max-width: 500px; text-align: center;} 
                                    h2 { color: #1a73e8; } 
                                    .btn { display: inline-block; width: 100%; padding: 15px; background: #34a853; color: white; text-decoration: none; border-radius: 8px; font-weight: bold; font-size: 16px; margin-top: 20px; box-sizing: border-box;}
                                </style>
                            </head>
                            <body>
                                <div class="card">
                                    <h2 style='color:#1a73e8;'>設定を完了しました！🎉</h2>
                                    <p>テレビ側に「<b>$name</b>」の登録・更新が反映されました。</p>
                                    <br>
                                    <a href="/smb" class="btn">続けて別のサーバーを追加する</a>
                                </div>
                            </body>
                            </html>
                        """.trimIndent()
                        call.respondText(successHtml, ContentType.Text.Html)
                    } else {
                        call.respondText(
                            "表示名とIPアドレスは必須です。戻ってやり直してください。",
                            ContentType.Text.Plain
                        )
                    }
                }
            }
        }.start(wait = false)
        Log.i("SettingsViewModel", "Started Local Server on port 8081")
    }

    fun stopGeminiLocalServer() {
        viewModelScope.launch(Dispatchers.IO) {
            ktorServer?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
            ktorServer = null
            Log.i("SettingsViewModel", "Stopped Local Server")
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress
                        ?: ""
                }
            }
        } catch (e: Exception) {
        }
        return "127.0.0.1"
    }

    private fun getSetupHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>AIコンシェルジュ設定</title>
                <style>body { font-family: sans-serif; background-color: #f0f2f5; display: flex; justify-content: center; padding: 20px; } .card { background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); width: 100%; max-width: 500px;} h2 { color: #1a73e8; text-align: center; } .step { background: #e8f0fe; padding: 15px; border-radius: 8px; margin-bottom: 20px; } .btn { display: block; width: 100%; padding: 15px; background: #34a853; color: white; text-align: center; text-decoration: none; border-radius: 8px; font-weight: bold; font-size: 16px; margin-top: 10px; box-sizing: border-box; } input { width: 100%; padding: 15px; margin: 10px 0; border: 2px solid #ccc; border-radius: 8px; box-sizing: border-box; font-size: 16px; } button { width: 100%; padding: 15px; background: #1a73e8; color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; }</style>
            </head>
            <body>
                <div class="card"><h2>AIコンシェルジュ連携</h2>
                    <div class="step"><p><b>Step 1:</b> 以下のボタンからGoogle AI Studioを開き、APIキー(AIzaSy...)を作成してコピーしてください。</p><a href="https://aistudio.google.com/app/apikey" target="_blank" class="btn">GoogleからAPIキーを取得</a></div>
                    <div class="step"><p><b>Step 2:</b> コピーしたAPIキーを下に貼り付けて、送信ボタンを押してください。</p>
                        <form action="/submit" method="post"><input type="text" name="api_key" placeholder="AIzaSy..." required><button type="submit">テレビに送信する</button></form>
                    </div>
                </div>
            </body></html>
        """.trimIndent()
    }

    private fun getSmbSetupHtml(server: SmbServer?): String {
        val title = if (server != null) "SMBサーバーの編集" else "新しいSMBサーバーの登録"
        val idVal = server?.id ?: ""
        val nameVal = server?.name ?: ""
        val ipVal = server?.ip ?: ""
        val portVal = server?.port ?: "445"
        val userVal = server?.user ?: ""
        val passVal = server?.password ?: ""

        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>$title</title>
                <style>body { font-family: sans-serif; background-color: #f0f2f5; display: flex; justify-content: center; padding: 20px; } .card { background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); width: 100%; max-width: 500px;} h2 { color: #1a73e8; text-align: center; } .step { background: #e8f0fe; padding: 15px; border-radius: 8px; margin-bottom: 20px; } input { width: 100%; padding: 15px; margin: 10px 0; border: 2px solid #ccc; border-radius: 8px; box-sizing: border-box; font-size: 16px; } button { width: 100%; padding: 15px; background: #1a73e8; color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; margin-top: 15px; } label { font-weight: bold; font-size: 14px; color: #555; }</style>
            </head>
            <body>
                <div class="card"><h2>$title</h2>
                    <div class="step"><p>必要な情報を入力して送信ボタンを押してください。</p>
                        <form action="/submit_smb" method="post">
                            <input type="hidden" name="id" value="$idVal">
                            <label>表示名 (必須)</label><input type="text" name="name" placeholder="例: リビングのNAS" value="$nameVal" required>
                            <label>IPアドレス (必須)</label><input type="text" name="ip" placeholder="例: 192.168.1.10" value="$ipVal" required>
                            <label>ポート番号</label><input type="text" name="port" placeholder="例: 445" value="$portVal">
                            <label>ユーザー名 (空ならゲスト)</label><input type="text" name="user" placeholder="ユーザー名" value="$userVal">
                            <label>パスワード</label><input type="password" name="password" placeholder="パスワード" value="$passVal">
                            <button type="submit">テレビに設定を送信</button>
                        </form>
                    </div>
                </div>
            </body></html>
        """.trimIndent()
    }
}