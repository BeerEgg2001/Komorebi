package com.beeregg2001.komorebi.data

import android.content.Context
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
        val KONOMI_IP = stringPreferencesKey("konomi_ip")
        val KONOMI_PORT = stringPreferencesKey("konomi_port")
        val MIRAKURUN_IP = stringPreferencesKey("mirakurun_ip")
        val MIRAKURUN_PORT = stringPreferencesKey("mirakurun_port")

        val COMMENT_SPEED = stringPreferencesKey("comment_speed")
        val COMMENT_FONT_SIZE = stringPreferencesKey("comment_font_size")
        val COMMENT_OPACITY = stringPreferencesKey("comment_opacity")
        val COMMENT_MAX_LINES = stringPreferencesKey("comment_max_lines")
        val COMMENT_DEFAULT_DISPLAY = stringPreferencesKey("comment_default_display")

        val LIVE_QUALITY = stringPreferencesKey("live_quality")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")

        val HOME_PICKUP_GENRE = stringPreferencesKey("home_pickup_genre")
        val EXCLUDE_PAID_BROADCASTS = stringPreferencesKey("exclude_paid_broadcasts")
        // ★追加: ピックアップの時間帯設定（自動、朝、昼、夜）
        val HOME_PICKUP_TIME = stringPreferencesKey("home_pickup_time")
    }

    val konomiIp: Flow<String> = context.dataStore.data.map { it[KONOMI_IP] ?: "https://192-168-xxx-xxx.local.konomi.tv" }
    val konomiPort: Flow<String> = context.dataStore.data.map { it[KONOMI_PORT] ?: "7000" }
    val mirakurunIp: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_IP] ?: "" }
    val mirakurunPort: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_PORT] ?: "" }

    val commentSpeed: Flow<String> = context.dataStore.data.map { it[COMMENT_SPEED] ?: "1.0" }
    val commentFontSize: Flow<String> = context.dataStore.data.map { it[COMMENT_FONT_SIZE] ?: "1.0" }
    val commentOpacity: Flow<String> = context.dataStore.data.map { it[COMMENT_OPACITY] ?: "1.0" }
    val commentMaxLines: Flow<String> = context.dataStore.data.map { it[COMMENT_MAX_LINES] ?: "0" }
    val commentDefaultDisplay: Flow<String> = context.dataStore.data.map { it[COMMENT_DEFAULT_DISPLAY] ?: "ON" }

    val liveQuality: Flow<String> = context.dataStore.data.map { it[LIVE_QUALITY] ?: "1080p-60fps" }
    val videoQuality: Flow<String> = context.dataStore.data.map { it[VIDEO_QUALITY] ?: "1080p-60fps" }

    val homePickupGenre: Flow<String> = context.dataStore.data.map { it[HOME_PICKUP_GENRE] ?: "アニメ" }
    val excludePaidBroadcasts: Flow<String> = context.dataStore.data.map { it[EXCLUDE_PAID_BROADCASTS] ?: "ON" }

    // ★追加: デフォルトは「自動」（現在の時間帯）
    val homePickupTime: Flow<String> = context.dataStore.data.map { it[HOME_PICKUP_TIME] ?: "自動" }

    val isInitialized: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs.contains(KONOMI_IP) || prefs.contains(MIRAKURUN_IP)
    }

    suspend fun saveString(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.dataStore.edit { settings ->
            settings[key] = value
        }
    }

    suspend fun getBaseUrl(): String {
        val prefs = context.dataStore.data.first()
        var ip = prefs[KONOMI_IP] ?: "https://192-168-xxx-xxx.local.konomi.tv"
        val port = prefs[KONOMI_PORT] ?: "7000"
        if (!ip.startsWith("http://") && !ip.startsWith("https://")) { ip = "https://$ip" }
        val base = ip.removeSuffix("/")
        return "$base:$port/"
    }
}