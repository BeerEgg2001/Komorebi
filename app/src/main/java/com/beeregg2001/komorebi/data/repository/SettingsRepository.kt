package com.beeregg2001.komorebi.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // ★ 追加: バックエンドの選択と、各システムのIP/Port
        val BACKEND_TYPE = stringPreferencesKey("backend_type")
        val EDCB_IP = stringPreferencesKey("edcb_ip")
        val EDCB_PORT = stringPreferencesKey("edcb_port")
        val EPGSTATION_IP = stringPreferencesKey("epgstation_ip")
        val EPGSTATION_PORT = stringPreferencesKey("epgstation_port")

        val KONOMI_IP = stringPreferencesKey("konomi_ip")
        val KONOMI_PORT = stringPreferencesKey("konomi_port")
        val MIRAKURUN_IP = stringPreferencesKey("mirakurun_ip")
        val MIRAKURUN_PORT = stringPreferencesKey("mirakurun_port")
        val PREFERRED_STREAM_SOURCE = stringPreferencesKey("preferred_stream_source")

        val COMMENT_SPEED = stringPreferencesKey("comment_speed")
        val COMMENT_FONT_SIZE = stringPreferencesKey("comment_font_size")
        val COMMENT_OPACITY = stringPreferencesKey("comment_opacity")
        val COMMENT_MAX_LINES = stringPreferencesKey("comment_max_lines")
        val COMMENT_DEFAULT_DISPLAY = stringPreferencesKey("comment_default_display")

        val LIVE_QUALITY = stringPreferencesKey("live_quality")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val LIVE_SUBTITLE_DEFAULT = stringPreferencesKey("live_subtitle_default")
        val VIDEO_SUBTITLE_DEFAULT = stringPreferencesKey("video_subtitle_default")
        val SUBTITLE_COMMENT_LAYER = stringPreferencesKey("subtitle_comment_layer")
        val AUDIO_OUTPUT_MODE = stringPreferencesKey("audio_output_mode")

        val LAB_ANNICT_INTEGRATION = stringPreferencesKey("lab_annict_integration")
        val LAB_SHOBOCAL_INTEGRATION = stringPreferencesKey("lab_shobocal_integration")
        val LAB_ALLOW_MIRAKURUN_DUAL = stringPreferencesKey("lab_allow_mirakurun_dual")

        val DEFAULT_POST_COMMAND = stringPreferencesKey("default_post_command")
        val POST_RECORDING_BATCH_LIST = stringPreferencesKey("post_recording_batch_list")

        val FAVORITE_BASEBALL_TEAMS = stringPreferencesKey("favorite_baseball_teams")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val ENABLE_AI_NORMALIZATION = stringPreferencesKey("enable_ai_normalization")

        val HOME_PICKUP_GENRE = stringPreferencesKey("home_pickup_genre")
        val EXCLUDE_PAID_BROADCASTS = stringPreferencesKey("exclude_paid_broadcasts")
        val HOME_PICKUP_TIME = stringPreferencesKey("home_pickup_time")

        val STARTUP_TAB = stringPreferencesKey("startup_tab")
        val STARTUP_CHANNEL = stringPreferencesKey("startup_channel")
        val APP_THEME = stringPreferencesKey("app_theme")
        val DEFAULT_RECORD_LIST_VIEW = stringPreferencesKey("default_record_list_view")

        // 時間表記フォーマット (12H or 24H)
        val TIME_FORMAT = stringPreferencesKey("time_format")

        // ベータ版アップデート受信設定のキー
        val RECEIVE_BETA_UPDATES = booleanPreferencesKey("receive_beta_updates")
    }

    // ★ 追加: Flow定義
    val backendType: Flow<String> = context.dataStore.data.map { it[BACKEND_TYPE] ?: "KONOMITV" }
    val edcbIp: Flow<String> = context.dataStore.data.map { it[EDCB_IP] ?: "" }
    val edcbPort: Flow<String> = context.dataStore.data.map { it[EDCB_PORT] ?: "5510" }
    val epgStationIp: Flow<String> = context.dataStore.data.map { it[EPGSTATION_IP] ?: "" }
    val epgStationPort: Flow<String> = context.dataStore.data.map { it[EPGSTATION_PORT] ?: "8888" }

    val konomiIp: Flow<String> = context.dataStore.data.map { it[KONOMI_IP] ?: "" }
    val konomiPort: Flow<String> = context.dataStore.data.map { it[KONOMI_PORT] ?: "7000" }
    val mirakurunIp: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_IP] ?: "" }
    val mirakurunPort: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_PORT] ?: "40772" }
    val preferredStreamSource: Flow<String> =
        context.dataStore.data.map { it[PREFERRED_STREAM_SOURCE] ?: "KONOMITV" }

    val commentSpeed: Flow<String> = context.dataStore.data.map { it[COMMENT_SPEED] ?: "1.0" }
    val commentFontSize: Flow<String> =
        context.dataStore.data.map { it[COMMENT_FONT_SIZE] ?: "1.0" }
    val commentOpacity: Flow<String> = context.dataStore.data.map { it[COMMENT_OPACITY] ?: "1.0" }
    val commentMaxLines: Flow<String> = context.dataStore.data.map { it[COMMENT_MAX_LINES] ?: "0" }
    val commentDefaultDisplay: Flow<String> =
        context.dataStore.data.map { it[COMMENT_DEFAULT_DISPLAY] ?: "ON" }

    val liveQuality: Flow<String> = context.dataStore.data.map { it[LIVE_QUALITY] ?: "1080p-60fps" }
    val videoQuality: Flow<String> =
        context.dataStore.data.map { it[VIDEO_QUALITY] ?: "1080p-60fps" }
    val liveSubtitleDefault: Flow<String> =
        context.dataStore.data.map { it[LIVE_SUBTITLE_DEFAULT] ?: "ON" }
    val videoSubtitleDefault: Flow<String> =
        context.dataStore.data.map { it[VIDEO_SUBTITLE_DEFAULT] ?: "ON" }
    val subtitleCommentLayer: Flow<String> =
        context.dataStore.data.map { it[SUBTITLE_COMMENT_LAYER] ?: "COMMENT_TOP" }
    val audioOutputMode: Flow<String> =
        context.dataStore.data.map { it[AUDIO_OUTPUT_MODE] ?: "DOWNMIX" }

    val labAnnictIntegration: Flow<String> =
        context.dataStore.data.map { it[LAB_ANNICT_INTEGRATION] ?: "OFF" }
    val labShobocalIntegration: Flow<String> =
        context.dataStore.data.map { it[LAB_SHOBOCAL_INTEGRATION] ?: "OFF" }
    val labAllowMirakurunDual: Flow<String> =
        context.dataStore.data.map { it[LAB_ALLOW_MIRAKURUN_DUAL] ?: "OFF" }

    val defaultPostCommand: Flow<String> =
        context.dataStore.data.map { it[DEFAULT_POST_COMMAND] ?: "" }
    val postRecordingBatchList: Flow<String> =
        context.dataStore.data.map { it[POST_RECORDING_BATCH_LIST] ?: "[]" }

    val favoriteBaseballTeams: Flow<String> =
        context.dataStore.data.map { it[FAVORITE_BASEBALL_TEAMS] ?: "[]" }
    val geminiApiKey: Flow<String> = context.dataStore.data.map { it[GEMINI_API_KEY] ?: "" }
    val enableAiNormalization: Flow<String> =
        context.dataStore.data.map { it[ENABLE_AI_NORMALIZATION] ?: "OFF" }

    val homePickupGenre: Flow<String> =
        context.dataStore.data.map { it[HOME_PICKUP_GENRE] ?: "アニメ" }
    val excludePaidBroadcasts: Flow<String> =
        context.dataStore.data.map { it[EXCLUDE_PAID_BROADCASTS] ?: "ON" }
    val homePickupTime: Flow<String> = context.dataStore.data.map { it[HOME_PICKUP_TIME] ?: "自動" }

    val startupTab: Flow<String> = context.dataStore.data.map { it[STARTUP_TAB] ?: "ホーム" }
    val startupChannel: Flow<String> = context.dataStore.data.map { it[STARTUP_CHANNEL] ?: "OFF" }
    val appTheme: Flow<String> = context.dataStore.data.map { it[APP_THEME] ?: "MONOTONE" }
    val defaultRecordListView: Flow<String> =
        context.dataStore.data.map { it[DEFAULT_RECORD_LIST_VIEW] ?: "LIST" }

    val timeFormat: Flow<String> = context.dataStore.data.map { it[TIME_FORMAT] ?: "24H" }
    val receiveBetaUpdates: Flow<Boolean> =
        context.dataStore.data.map { it[RECEIVE_BETA_UPDATES] ?: false }

    // ★ 修正: KonomiTV以外にもEDCBやEPGStationが設定されていれば初期化済みと判定する
    val isInitialized: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs.contains(KONOMI_IP) || prefs.contains(MIRAKURUN_IP) || prefs.contains(EDCB_IP) || prefs.contains(
            EPGSTATION_IP
        )
    }

    suspend fun saveString(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String
    ) {
        Log.i(
            "BaseballDebug",
            "[SettingsRepository] Saving to DataStore - Key: ${key.name}, Value: $value"
        )
        context.dataStore.edit { settings ->
            settings[key] = value
        }
    }

    suspend fun getBaseUrl(): String {
        val prefs = context.dataStore.data.first()
        var ip = prefs[KONOMI_IP] ?: "https://192-168-xxx-xxx.local.konomi.tv"
        val port = prefs[KONOMI_PORT] ?: "7000"
        if (!ip.startsWith("http://") && !ip.startsWith("https://")) {
            ip = "http://$ip"
        }
        return "$ip:$port"
    }

    suspend fun getStartupTabOnce(): String {
        val prefs = context.dataStore.data.first()
        return prefs[STARTUP_TAB] ?: "ホーム"
    }

    suspend fun saveBoolean(
        key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        value: Boolean
    ) {
        context.dataStore.edit { settings ->
            settings[key] = value
        }
    }

    // ★ 追加: 指定されたStreamSourceに対応するBackendConfigを直接生成して返す
    suspend fun getBackendConfig(source: com.beeregg2001.komorebi.data.model.StreamSource): com.beeregg2001.komorebi.data.model.BackendConfig {
        val prefs = context.dataStore.data.first()
        return when (source) {
            com.beeregg2001.komorebi.data.model.StreamSource.KONOMITV -> com.beeregg2001.komorebi.data.model.BackendConfig.KonomiTv(
                ip = prefs[KONOMI_IP] ?: "",
                port = prefs[KONOMI_PORT] ?: "7000"
            )
            com.beeregg2001.komorebi.data.model.StreamSource.MIRAKURUN -> com.beeregg2001.komorebi.data.model.BackendConfig.Mirakurun(
                ip = prefs[MIRAKURUN_IP] ?: "",
                port = prefs[MIRAKURUN_PORT] ?: "40772"
            )
            com.beeregg2001.komorebi.data.model.StreamSource.EDCB -> com.beeregg2001.komorebi.data.model.BackendConfig.Edcb(
                ip = prefs[EDCB_IP] ?: "",
                port = prefs[EDCB_PORT] ?: "4510"
            )
        }
    }
}