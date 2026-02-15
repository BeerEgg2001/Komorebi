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

// Contextの拡張プロパティとしてDataStoreを定義
private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // キーの定義
        val KONOMI_IP = stringPreferencesKey("konomi_ip")
        val KONOMI_PORT = stringPreferencesKey("konomi_port")
        val MIRAKURUN_IP = stringPreferencesKey("mirakurun_ip")
        val MIRAKURUN_PORT = stringPreferencesKey("mirakurun_port")

        // 実況カスタマイズ用キー
        val COMMENT_SPEED = stringPreferencesKey("comment_speed")
        val COMMENT_FONT_SIZE = stringPreferencesKey("comment_font_size")
        val COMMENT_OPACITY = stringPreferencesKey("comment_opacity")
        val COMMENT_MAX_LINES = stringPreferencesKey("comment_max_lines")
        // ★追加: コメント表示のデフォルト設定
        val COMMENT_DEFAULT_DISPLAY = stringPreferencesKey("comment_default_display")
    }

    // 値を取得するFlow
    val konomiIp: Flow<String> = context.dataStore.data.map { it[KONOMI_IP] ?: "https://192-168-xxx-xxx.local.konomi.tv" }
    val konomiPort: Flow<String> = context.dataStore.data.map { it[KONOMI_PORT] ?: "7000" }
    val mirakurunIp: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_IP] ?: "" }
    val mirakurunPort: Flow<String> = context.dataStore.data.map { it[MIRAKURUN_PORT] ?: "" }

    // 実況設定のFlow
    val commentSpeed: Flow<String> = context.dataStore.data.map { it[COMMENT_SPEED] ?: "1.0" }
    val commentFontSize: Flow<String> = context.dataStore.data.map { it[COMMENT_FONT_SIZE] ?: "1.0" }
    val commentOpacity: Flow<String> = context.dataStore.data.map { it[COMMENT_OPACITY] ?: "1.0" }
    val commentMaxLines: Flow<String> = context.dataStore.data.map { it[COMMENT_MAX_LINES] ?: "0" }
    // ★追加: コメント表示のデフォルト設定のFlow (デフォルトはON)
    val commentDefaultDisplay: Flow<String> = context.dataStore.data.map { it[COMMENT_DEFAULT_DISPLAY] ?: "ON" }

    // 設定が保存（初期化）されているかチェックするFlow
    val isInitialized: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs.contains(KONOMI_IP) || prefs.contains(MIRAKURUN_IP)
    }

    // 値を保存するサスペンド関数
    suspend fun saveString(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.dataStore.edit { settings ->
            settings[key] = value
        }
    }

    // 現在設定されているベースURLを組み立てて取得する
    suspend fun getBaseUrl(): String {
        val prefs = context.dataStore.data.first()
        var ip = prefs[KONOMI_IP] ?: "https://192-168-xxx-xxxxs.local.konomi.tv"
        val port = prefs[KONOMI_PORT] ?: "7000"

        // http(s):// が抜けている場合の補完
        if (!ip.startsWith("http://") && !ip.startsWith("https://")) {
            ip = "https://$ip"
        }

        // 末尾のスラッシュを一旦削除して整形
        val base = ip.removeSuffix("/")
        return "$base:$port/"
    }
}