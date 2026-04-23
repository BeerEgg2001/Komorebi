package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Keep
data class PostRecordingBatch(
    val name: String,
    val path: String
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: RecordSyncEngine,
    private val recordProvider: RecordProvider,
    private val db: AppDatabase
) : ViewModel() {

    private val gson = Gson()

    private val _availableQualities =
        MutableStateFlow<List<StreamQuality>>(StreamQuality.DEFAULT_QUALITIES)
    val availableQualities: StateFlow<List<StreamQuality>> = _availableQualities.asStateFlow()

    val totalRecordCount: StateFlow<Int> = db.recordedProgramDao().getTotalCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncedAt: StateFlow<Long> = db.syncMetaDao().getSyncMetaFlow()
        .map { it?.lastSyncedAt ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val backendType: StateFlow<String> = settingsRepository.backendType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "KONOMITV")

    val edcbIp: StateFlow<String> = settingsRepository.edcbIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val edcbPort: StateFlow<String> = settingsRepository.edcbPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "5510")

    val edcbRecordPlayMethod: StateFlow<String> = settingsRepository.edcbRecordPlayMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "API")

    val epgStationIp: StateFlow<String> = settingsRepository.epgStationIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val epgStationPort: StateFlow<String> = settingsRepository.epgStationPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "8888")

    val mirakurunIp: StateFlow<String> = settingsRepository.mirakurunIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val mirakurunPort: StateFlow<String> = settingsRepository.mirakurunPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val konomiIp: StateFlow<String> = settingsRepository.konomiIp
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            "https://192-168-xxx-xxx.local.konomi.tv"
        )
    val konomiPort: StateFlow<String> = settingsRepository.konomiPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "7000")

    val commentSpeed: StateFlow<String> = settingsRepository.commentSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")
    val commentFontSize: StateFlow<String> = settingsRepository.commentFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")
    val commentOpacity: StateFlow<String> = settingsRepository.commentOpacity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.0")
    val commentMaxLines: StateFlow<String> = settingsRepository.commentMaxLines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0")
    val commentDefaultDisplay: StateFlow<String> = settingsRepository.commentDefaultDisplay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ON")

    val liveQuality: StateFlow<String> = settingsRepository.liveQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p-60fps")
    val videoQuality: StateFlow<String> = settingsRepository.videoQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p-60fps")

    val liveSubtitleDefault: StateFlow<String> = settingsRepository.liveSubtitleDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val videoSubtitleDefault: StateFlow<String> = settingsRepository.videoSubtitleDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val subtitleCommentLayer: StateFlow<String> = settingsRepository.subtitleCommentLayer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "CommentOnTop")

    val audioOutputMode: StateFlow<String> = settingsRepository.audioOutputMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DOWNMIX")

    val playerUiMode: StateFlow<String> = settingsRepository.playerUiMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MODERN")

    val autoCmSkip: StateFlow<String> = settingsRepository.autoCmSkip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")

    val labAnnictIntegration: StateFlow<String> = settingsRepository.labAnnictIntegration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val labShobocalIntegration: StateFlow<String> = settingsRepository.labShobocalIntegration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")

    val labAllowMirakurunDual: StateFlow<String> = settingsRepository.labAllowMirakurunDual
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")
    val defaultPostCommand: StateFlow<String> = settingsRepository.defaultPostCommand
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val startupChannel: StateFlow<String> = settingsRepository.startupChannel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OFF")

    val timeFormat: StateFlow<String> = settingsRepository.timeFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "24H")

    init {
        fetchAvailableQualities()
    }

    private fun fetchAvailableQualities() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backend = settingsRepository.backendType.first()
                if (backend == "EDCB") {
                    val qualities = recordProvider.getStreamQualities()
                    if (qualities.isNotEmpty()) {
                        _availableQualities.value = qualities
                        Log.i(
                            "SettingsViewModel",
                            "Loaded dynamic stream qualities: ${qualities.size} options"
                        )
                    } else {
                        _availableQualities.value = StreamQuality.DEFAULT_QUALITIES
                    }
                } else {
                    _availableQualities.value = StreamQuality.DEFAULT_QUALITIES
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to load stream qualities", e)
                _availableQualities.value = StreamQuality.DEFAULT_QUALITIES
            }
        }
    }

    fun updateBackendType(newType: String) = viewModelScope.launch {
        val oldType = backendType.value

        if (oldType == newType) return@launch

        settingsRepository.saveString(SettingsRepository.BACKEND_TYPE, newType)

        withContext(Dispatchers.IO) {
            try {
                db.clearAllTables()
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
                Log.i(
                    "SettingsViewModel",
                    "Backend changed to $newType. Cleared AppDatabase and Image Caches."
                )
            } catch (e: Exception) {
                Log.e(
                    "SettingsViewModel",
                    "Failed to clear DB/Caches on backend change",
                    e
                )
            }
        }

        syncEngine.launchSyncAllRecords(forceFullSync = true)
        fetchAvailableQualities()
    }

    fun updateEdcbIp(ip: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.EDCB_IP, ip)
        fetchAvailableQualities()
    }

    fun updateEdcbPort(port: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.EDCB_PORT, port)
    }

    // ★ 修正: 再生方式（DIRECT/API）が変更された際に画質リストを再取得してUIを更新する
    fun updateEdcbRecordPlayMethod(method: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.EDCB_RECORD_PLAY_METHOD, method)
        // DataStoreへの保存がトリガーされた直後にリストを再フェッチし、_availableQualities を更新
        fetchAvailableQualities()
    }

    fun updateEpgStationIp(ip: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.EPGSTATION_IP, ip)
    }

    fun updateEpgStationPort(port: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.EPGSTATION_PORT, port)
    }

    fun updateStartupChannel(value: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.STARTUP_CHANNEL, value)
    }

    fun updateTimeFormat(value: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.TIME_FORMAT, value)
    }

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

    val startupTab: StateFlow<String> = settingsRepository.startupTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ホーム")
    val appTheme: StateFlow<String> = settingsRepository.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MONOTONE")

    val receiveBetaUpdates: StateFlow<Boolean> = settingsRepository.receiveBetaUpdates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isSettingsInitialized: StateFlow<Boolean> = settingsRepository.isInitialized
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val hideSubChannels: StateFlow<Boolean> = settingsRepository.hideSubChannels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun updateMirakurunIp(ip: String) {
        viewModelScope.launch { settingsRepository.saveString(SettingsRepository.MIRAKURUN_IP, ip) }
    }

    fun updateKonomiIp(ip: String) {
        viewModelScope.launch {
            val oldIp = konomiIp.value
            settingsRepository.saveString(SettingsRepository.KONOMI_IP, ip)
            val isDefault = oldIp == "" || oldIp == "https://192-168-xxx-xxx.local.konomi.tv"
            if (oldIp != ip && !isDefault) {
                syncEngine.launchSyncAllRecords(forceFullSync = true)
            }
        }
    }

    fun updateKonomiPort(port: String) {
        viewModelScope.launch {
            val oldPort = konomiPort.value
            settingsRepository.saveString(SettingsRepository.KONOMI_PORT, port)
            val isDefault = oldPort == "" || oldPort == "7000"
            if (oldPort != port && !isDefault) {
                syncEngine.launchSyncAllRecords(forceFullSync = true)
            }
        }
    }

    fun triggerFullSync() {
        viewModelScope.launch {
            syncEngine.launchSyncAllRecords(forceFullSync = true)
        }
    }

    fun addPostRecordingBatch(name: String, path: String) {
        viewModelScope.launch {
            val newList = postRecordingBatchList.value.toMutableList().apply {
                add(PostRecordingBatch(name, path))
            }
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(newList)
            )
        }
    }

    fun deletePostRecordingBatch(batch: PostRecordingBatch) {
        viewModelScope.launch {
            val newList = postRecordingBatchList.value.toMutableList().apply {
                remove(batch)
            }
            settingsRepository.saveString(
                SettingsRepository.POST_RECORDING_BATCH_LIST,
                gson.toJson(newList)
            )
        }
    }

    fun updateFavoriteBaseballTeams(teams: Set<String>) {
        viewModelScope.launch {
            settingsRepository.saveString(
                SettingsRepository.FAVORITE_BASEBALL_TEAMS,
                gson.toJson(teams)
            )
        }
    }

    fun updateAppTheme(themeName: String) {
        viewModelScope.launch {
            settingsRepository.saveString(
                SettingsRepository.APP_THEME,
                themeName
            )
        }
    }

    fun updateDefaultRecordListView(viewType: String) {
        viewModelScope.launch {
            settingsRepository.saveString(
                SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                viewType
            )
        }
    }

    suspend fun getStartupTabOnce(): String {
        return settingsRepository.getStartupTabOnce()
    }

    fun updateLabAllowMirakurunDual(value: String) = viewModelScope.launch {
        settingsRepository.saveString(SettingsRepository.LAB_ALLOW_MIRAKURUN_DUAL, value)
    }

    private var ktorServer: ApplicationEngine? = null

    private val _localIpAddress = MutableStateFlow(getLocalIpAddress())
    val localIpAddress: StateFlow<String> = _localIpAddress

    fun startGeminiLocalServer() {
        if (ktorServer != null) return

        _localIpAddress.value = getLocalIpAddress()

        ktorServer = embeddedServer(CIO, port = 8081) {
            routing {
                get("/") {
                    call.respondText(getSetupHtml(), ContentType.Text.Html)
                }
                post("/submit") {
                    val formParams = call.receiveParameters()
                    val apiKey = formParams["api_key"] ?: ""

                    if (apiKey.isNotBlank()) {
                        viewModelScope.launch {
                            settingsRepository.saveString(SettingsRepository.GEMINI_API_KEY, apiKey)
                        }
                        val successHtml =
                            "<html><body style='font-family:sans-serif; text-align:center; padding:50px; background:#e8f0fe;'>" +
                                    "<h2 style='color:#1a73e8;'>連携が完了しました！🎉</h2>" +
                                    "<p>テレビ画面を確認してください。この画面は閉じて大丈夫です。</p>" +
                                    "</body></html>"
                        call.respondText(successHtml, ContentType.Text.Html)
                    } else {
                        call.respondText(
                            "APIキーが空です。戻ってやり直してください。",
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

    fun toggleHideSubChannels() {
        viewModelScope.launch {
            val current = settingsRepository.hideSubChannels.first()
            settingsRepository.saveBoolean(
                SettingsRepository.HIDE_SUB_CHANNELS,
                !current
            )
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun getSetupHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>AIコンシェルジュ設定</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background-color: #f0f2f5; color: #333; display: flex; justify-content: center; padding: 20px; }
                    .card { background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); max-width: 500px; width: 100%; }
                    h2 { margin-top: 0; color: #1a73e8; text-align: center; }
                    .step { background: #e8f0fe; padding: 15px; border-radius: 8px; margin-bottom: 20px; }
                    .btn { display: block; width: 100%; padding: 15px; background: #34a853; color: white; text-align: center; text-decoration: none; border-radius: 8px; font-weight: bold; font-size: 16px; margin-top: 10px; box-sizing: border-box; }
                    input[type="text"] { width: 100%; padding: 15px; margin: 10px 0; border: 2px solid #ccc; border-radius: 8px; box-sizing: border-box; font-size: 16px; }
                    button[type="submit"] { width: 100%; padding: 15px; background: #1a73e8; color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>AIコンシェルジュ連携</h2>
                    <div class="step">
                        <p><b>Step 1:</b> 以下のボタンからGoogle AI Studioを開き、APIキー(AIzaSy...)を作成してコピーしてください。</p>
                        <a href="https://aistudio.google.com/app/apikey" target="_blank" class="btn">GoogleからAPIキーを取得</a>
                    </div>
                    <div class="step">
                        <p><b>Step 2:</b> コピーしたAPIキーを下に貼り付けて、送信ボタンを押してください。</p>
                        <form action="/submit" method="post">
                            <input type="text" name="api_key" placeholder="AIzaSy..." required>
                            <button type="submit">テレビに送信する</button>
                        </form>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}