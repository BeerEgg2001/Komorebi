package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.video.player.ChapterInfo
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

@HiltViewModel
class SmbViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath

    private val _fileList = MutableStateFlow<List<SmbItem>>(emptyList())
    val fileList: StateFlow<List<SmbItem>> = _fileList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var rootPath = ""
    private var cachedServers: List<SmbServer> = emptyList()

    private val _drives = MutableStateFlow<List<SmbItem>>(emptyList())
    val drives: StateFlow<List<SmbItem>> = _drives

    private val _pinnedFolders = MutableStateFlow<List<SmbItem>>(emptyList())
    val pinnedFolders: StateFlow<List<SmbItem>> = _pinnedFolders

    private val prefs = context.getSharedPreferences("smb_prefs", Context.MODE_PRIVATE)

    init {
        loadPinnedFolders()
    }

    private fun loadPinnedFolders() {
        val savedPaths = prefs.getStringSet("pinned_paths", emptySet()) ?: emptySet()
        val items = savedPaths.map { path ->
            val name = path.trimEnd('/').substringAfterLast('/')
            SmbItem(name = name, path = path, isDirectory = true, size = 0, lastModified = 0)
        }
        _pinnedFolders.value = items.sortedBy { it.name.lowercase() }
    }

    fun initSmb(resumePath: String? = null) {
        viewModelScope.launch {
            val json = settingsRepository.smbServerList.first()
            val type = object : TypeToken<List<SmbServer>>() {}.type
            cachedServers = try {
                Gson().fromJson<List<SmbServer>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            if (cachedServers.isEmpty()) {
                _errorMessage.value =
                    "SMBサーバーが登録されていません。設定画面から追加してください。"
                return@launch
            }

            val driveList = cachedServers.map { server ->
                val ip = server.ip.trim()
                val port = server.port.ifEmpty { "445" }
                val parts = ip.split("/", limit = 2)
                val host = parts[0]
                val share = if (parts.size > 1) "${parts[1].trimEnd('/')}/" else ""
                val url = "smb://$host:$port/$share"
                SmbItem(
                    name = server.name,
                    path = url,
                    isDirectory = true,
                    size = 0,
                    lastModified = 0
                )
            }

            _drives.value = driveList
            rootPath = driveList.first().path

            val targetPath = if (resumePath != null && resumePath.startsWith("smb://")) {
                val lastSlash = resumePath.trimEnd('/').lastIndexOf('/')
                if (lastSlash > 6) resumePath.substring(0, lastSlash + 1) else rootPath
            } else {
                rootPath
            }

            loadDirectory(targetPath)
        }
    }

    fun loadDirectory(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _currentPath.value = path
            _fileList.value = emptyList()

            try {
                val items = withContext(Dispatchers.IO) {
                    val currentServer = cachedServers.find { server ->
                        val port = server.port.ifEmpty { "445" }
                        val host = server.ip.split("/", limit = 2)[0]
                        path.startsWith("smb://$host:$port/") || path.startsWith("smb://$host/")
                    }

                    val user = currentServer?.user ?: ""
                    val pass = currentServer?.password ?: ""

                    val context = SmbContextBuilder.build(user, pass)
                    val smbFile = SmbFile(path, context)

                    if (!smbFile.exists()) throw Exception("Path not found.")
                    val children = smbFile.listFiles() ?: emptyArray()

                    children.filter { !it.name.startsWith(".") }.map { child ->
                        SmbItem(
                            name = child.name.replace("/", ""),
                            path = child.url.toString(),
                            isDirectory = child.isDirectory,
                            size = try {
                                child.length()
                            } catch (e: Exception) {
                                0L
                            },
                            lastModified = try {
                                child.lastModified()
                            } catch (e: Exception) {
                                0L
                            }
                        )
                    }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }

                _fileList.value = items

            } catch (e: Exception) {
                Log.e("SmbViewModel", "Failed to load SMB directory: $path", e)
                _errorMessage.value = "エラーが発生しました: ${e.localizedMessage}"
                _fileList.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun loadChaptersForSmbItem(
        videoItem: SmbItem,
        server: SmbServer?,
        durationSec: Double
    ): List<ChapterInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val user = server?.user ?: ""
                val pass = server?.password ?: ""
                val context = SmbContextBuilder.build(user, pass)

                val basePath = videoItem.path
                val nameWithoutExt = basePath.substringBeforeLast(".")
                val candidates = listOf(
                    "$basePath.chapter",
                    "$nameWithoutExt.chapter",
                    "$basePath.chapter.txt",
                    "$nameWithoutExt.chapter.txt"
                )

                var targetFile: SmbFile? = null
                for (candidatePath in candidates) {
                    val file = SmbFile(candidatePath, context)
                    if (file.exists()) {
                        targetFile = file
                        break
                    }
                }

                if (targetFile == null) return@withContext emptyList()

                val content = StringBuilder()
                BufferedReader(InputStreamReader(targetFile.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        content.append(line).append("\n")
                        line = reader.readLine()
                    }
                }

                val text = content.toString()

                if (targetFile.name.endsWith(".txt", ignoreCase = true)) {
                    parseIniFormat(text, durationSec)
                } else {
                    parseLuaFormat(text, durationSec)
                }

            } catch (e: Exception) {
                Log.e("SmbViewModel", "Failed to load chapters for SMB item", e)
                emptyList()
            }
        }
    }

    private fun parseLuaFormat(text: String, durationSec: Double): List<ChapterInfo> {
        val rawMarkers = mutableListOf<Pair<Long, String>>() // 時間と名前のペアを一時保存
        val trimmed = text.trim()
        if (!trimmed.startsWith("c-") || !trimmed.endsWith("-c")) return emptyList()

        val coreContent = trimmed.substring(2, trimmed.length - 2)
        val segments = coreContent.split("-").filter { it.isNotEmpty() }
        val regex = Regex("""^(\d*)([cde])(.*)$""")

        var lastTimeMs: Long = 0L

        // 1. 全てのマーカーを時間順にリストアップする
        for (segment in segments) {
            val match = regex.find(segment) ?: continue
            val posValue = match.groupValues[1]
            val type = match.groupValues[2]
            val name = match.groupValues[3]

            val timeMs: Long = when (type) {
                "c" -> posValue.toLongOrNull() ?: 0L
                "d" -> (posValue.toLongOrNull() ?: 0L) * 100L
                "e" -> if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
                else -> continue
            }

            rawMarkers.add(Pair(timeMs, name))
            lastTimeMs = timeMs
        }

        if (rawMarkers.isEmpty()) return emptyList()

        val safeDurationMs = if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
        val chapters = mutableListOf<ChapterInfo>()
        var currentCmStartMs: Long? = null

        // 2. マーカーリストを舐めながら、面(CM)と点(マーカー)を構築する
        for (i in 0 until rawMarkers.size) {
            val (timeMs, name) = rawMarkers[i]
            // ★ 修正ポイント: 次のマーカーまでの時間を「区間の終了時間」とする
            val nextTimeMs = if (i + 1 < rawMarkers.size) rawMarkers[i + 1].first else safeDurationMs

            val isCmStart = name.startsWith("ox", ignoreCase = true)
            val isCmEnd = name.startsWith("ix", ignoreCase = true)

            // --- CM面（赤いスキップ帯）の構築 ---
            if (isCmStart && currentCmStartMs == null) {
                currentCmStartMs = timeMs
            } else if (isCmEnd && currentCmStartMs != null) {
                chapters.add(ChapterInfo(currentCmStartMs, timeMs, isCm = true, isMarkerOnly = false, label = ""))
                currentCmStartMs = null
            }

            // --- マーカー点（リストやシークで飛ぶ用）の構築 ---
            if (name.isNotEmpty() && !isCmStart && !isCmEnd) {
                // UIで「0秒」にならないように、startTimeMs と 次のチャプターまでの時間を endTimeMs に設定する
                chapters.add(ChapterInfo(timeMs, nextTimeMs, isCm = false, isMarkerOnly = true, label = name))
            }
        }

        // CMが閉じていない場合の終端処理
        if (currentCmStartMs != null) {
            chapters.add(ChapterInfo(currentCmStartMs, safeDurationMs, isCm = true, isMarkerOnly = false, label = ""))
        }

        return chapters.sortedBy { it.startTimeMs }
    }

    private fun parseIniFormat(text: String, durationSec: Double): List<ChapterInfo> {
        val rawMarkers = mutableListOf<Pair<Long, String>>()
        val lines = text.split("\n")
        var currentStartMs = -1L

        val timeRegex = Regex("""CHAPTER\d+=(\d{2}):(\d{2}):(\d{2})\.(\d{3})""")
        val nameRegex = Regex("""CHAPTER\d+NAME=(.*)""")

        for (line in lines) {
            val tMatch = timeRegex.find(line)
            if (tMatch != null) {
                val h = tMatch.groupValues[1].toLong()
                val m = tMatch.groupValues[2].toLong()
                val s = tMatch.groupValues[3].toLong()
                val ms = tMatch.groupValues[4].toLong()
                currentStartMs = (h * 3600000) + (m * 60000) + (s * 1000) + ms
            }

            val nMatch = nameRegex.find(line)
            if (nMatch != null && currentStartMs >= 0L) {
                val name = nMatch.groupValues[1].trim()
                rawMarkers.add(Pair(currentStartMs, name))
                currentStartMs = -1L // リセット
            }
        }

        if (rawMarkers.isEmpty()) return emptyList()

        val lastTimeMs = rawMarkers.last().first
        val safeDurationMs = if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
        val chapters = mutableListOf<ChapterInfo>()

        for (i in 0 until rawMarkers.size) {
            val (timeMs, name) = rawMarkers[i]
            // ★ 修正ポイント: 次のマーカーまでの時間を「区間の終了時間」とする
            val nextTimeMs = if (i + 1 < rawMarkers.size) rawMarkers[i + 1].first else safeDurationMs
            val isCm = name.contains("CM", ignoreCase = true) || name.contains("Sponsor", ignoreCase = true)

            if (isCm) {
                chapters.add(ChapterInfo(timeMs, nextTimeMs, isCm = true, isMarkerOnly = false, label = ""))
            }
            // 本編マーカーとしても追加（UI用）
            chapters.add(ChapterInfo(timeMs, nextTimeMs, isCm = false, isMarkerOnly = true, label = name))
        }

        return chapters.sortedBy { it.startTimeMs }
    }


    fun navigateUp(): Boolean {
        val current = _currentPath.value
        if (_drives.value.any { it.path == current } || current.count { it == '/' } <= 3) return false

        val parentPath = current.trimEnd('/').substringBeforeLast('/') + "/"
        loadDirectory(parentPath)
        return true
    }

    fun togglePin(item: SmbItem): Boolean {
        val currentList = _pinnedFolders.value.toMutableList()
        val existingItem = currentList.find { it.path == item.path }

        val isAdded = if (existingItem != null) {
            currentList.remove(existingItem)
            false
        } else {
            val itemToPin = if (item.isDirectory) item else item.copy(isDirectory = true)
            currentList.add(itemToPin)
            true
        }

        _pinnedFolders.value = currentList.sortedBy { it.name.lowercase() }
        prefs.edit().putStringSet("pinned_paths", currentList.map { it.path }.toSet()).apply()
        return isAdded
    }

    fun isPinned(path: String): Boolean {
        return _pinnedFolders.value.any { it.path == path }
    }
}