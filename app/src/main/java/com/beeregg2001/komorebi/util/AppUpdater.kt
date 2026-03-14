package com.beeregg2001.komorebi.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val versionName: String, val releaseNotes: String, val apkUrl: String) : UpdateState()
    data class Downloading(val progressPercentage: Int) : UpdateState()
    object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val client = OkHttpClient()

    // ★ご自身のGitHubのversion.jsonのRaw URLに書き換えてください
    private val versionJsonUrl = "https://raw.githubusercontent.com/BeerEgg2001/Komorebi/main/version.json"

    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        try {
            val request = Request.Builder().url(versionJsonUrl).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonString = response.body?.string() ?: return@withContext
                val json = JSONObject(jsonString)

                val latestVersionCode = json.getInt("versionCode")
                val latestVersionName = json.getString("versionName")
                val releaseNotes = json.getString("releaseNotes")
                val apkUrl = json.getString("apkUrl")

                val currentVersionCode = getCurrentVersionCode()

                if (latestVersionCode > currentVersionCode) {
                    _updateState.value = UpdateState.UpdateAvailable(latestVersionName, releaseNotes, apkUrl)
                } else {
                    _updateState.value = UpdateState.Idle
                }
            } else {
                _updateState.value = UpdateState.Error("アップデート情報の取得に失敗しました")
            }
        } catch (e: Exception) {
            Log.e("AppUpdater", "Update check failed", e)
            _updateState.value = UpdateState.Error("ネットワークエラーが発生しました")
        }
    }

    suspend fun downloadAndInstallUpdate(apkUrl: String) = withContext(Dispatchers.IO) {
        try {
            _updateState.value = UpdateState.Downloading(0)

            val request = Request.Builder().url(apkUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) throw Exception("APKのダウンロードに失敗しました")

            val body = response.body ?: throw Exception("レスポンスが空です")
            val contentLength = body.contentLength()

            // 外部キャッシュディレクトリ（アンインストール時に消え、FileProviderで共有しやすい場所）
            val updateFile = File(context.externalCacheDir, "update.apk")
            if (updateFile.exists()) updateFile.delete()

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(updateFile)
            val buffer = ByteArray(8 * 1024)
            var bytesCopied: Long = 0
            var bytes = inputStream.read(buffer)

            var lastProgress = 0

            while (bytes >= 0) {
                outputStream.write(buffer, 0, bytes)
                bytesCopied += bytes

                val progress = ((bytesCopied * 100) / contentLength).toInt()
                // 1%刻みでFlowを更新する（UIの描画負荷軽減のため）
                if (progress > lastProgress) {
                    _updateState.value = UpdateState.Downloading(progress)
                    lastProgress = progress
                }

                bytes = inputStream.read(buffer)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            _updateState.value = UpdateState.ReadyToInstall
            installApk(updateFile)

        } catch (e: Exception) {
            Log.e("AppUpdater", "Download failed", e)
            _updateState.value = UpdateState.Error("ダウンロード中にエラーが発生しました: ${e.localizedMessage}")
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)

            // インストール画面を開いたら状態をIdleに戻す
            _updateState.value = UpdateState.Idle
        } catch (e: Exception) {
            Log.e("AppUpdater", "Install failed", e)
            _updateState.value = UpdateState.Error("インストーラーの起動に失敗しました")
        }
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }
}