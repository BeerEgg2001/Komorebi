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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

enum class SmbSortType { NAME, DATE, SIZE }
enum class SmbSortOrder { ASC, DESC }

@HiltViewModel
class SmbViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath

    private val _rawFileList = MutableStateFlow<List<SmbItem>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory

    private val SMB_HISTORY_PREF = "smb_search_history_pref"
    private val SMB_KEY_HISTORY = "smb_history_list"

    // ★ 修正: ソート状態をUI側から監視・設定できるように公開
    private val _sortType = MutableStateFlow(SmbSortType.NAME)
    val sortType: StateFlow<SmbSortType> = _sortType

    private val _sortOrder = MutableStateFlow(SmbSortOrder.ASC)
    val sortOrder: StateFlow<SmbSortOrder> = _sortOrder

    private val VLC_VIDEO_EXTENSIONS = setOf(
        "3g2", "3gp", "3gp2", "3gpp", "amv", "asf", "avi", "divx", "drc", "dv",
        "f4v", "flv", "gvi", "gxf", "ismv", "iso", "m1v", "m2v", "m2t", "m2ts",
        "m4v", "mkv", "mov", "mp2", "mp2v", "mp4", "mp4v", "mpe", "mpeg", "mpeg1",
        "mpeg2", "mpeg4", "mpg", "mpv2", "mts", "mtv", "mxf", "mxg", "nsv", "nuv",
        "ogg", "ogm", "ogv", "ogx", "ps", "rec", "rm", "rmvb", "rpl", "thp", "tod",
        "ts", "tts", "txd", "vob", "vro", "webm", "wm", "wmv", "wtv", "xesc"
    )

    val fileList: StateFlow<List<SmbItem>> = combine(
        _rawFileList, _activeSearchQuery, _sortType, _sortOrder
    ) { rawList, activeQuery, sortType, sortOrder ->
        rawList.filter { item ->
            // 横断的検索時は rawList にすでに絞り込まれた結果が入るが、念のためここでも安全機構としてフィルタ
            if (activeQuery.isNotBlank() && !item.name.contains(activeQuery, ignoreCase = true)) {
                return@filter false
            }
            if (!item.isDirectory) {
                val ext = item.name.substringAfterLast('.', "").lowercase()
                if (ext !in VLC_VIDEO_EXTENSIONS) {
                    return@filter false
                }
            }
            true
        }.sortedWith { a, b ->
            if (a.isDirectory && !b.isDirectory) return@sortedWith -1
            if (!a.isDirectory && b.isDirectory) return@sortedWith 1

            val result = when (sortType) {
                SmbSortType.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SmbSortType.DATE -> a.lastModified.compareTo(b.lastModified)
                SmbSortType.SIZE -> a.size.compareTo(b.size)
            }
            if (sortOrder == SmbSortOrder.ASC) result else -result
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    // ★ 追加: 検索用のJob（キャンセルできるように保持）
    private var searchJob: Job? = null

    init {
        loadPinnedFolders()
        loadSearchHistory()
    }

    private fun loadPinnedFolders() {
        val savedPaths = prefs.getStringSet("pinned_paths", emptySet()) ?: emptySet()
        val items = savedPaths.map { path ->
            val name = path.trimEnd('/').substringAfterLast('/')
            SmbItem(name = name, path = path, isDirectory = true, size = 0, lastModified = 0)
        }
        _pinnedFolders.value = items.sortedBy { it.name.lowercase() }
    }

    private fun loadSearchHistory() {
        try {
            val prefs = context.getSharedPreferences(SMB_HISTORY_PREF, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(SMB_KEY_HISTORY, "[]")
            val jsonArray = JSONArray(jsonString)
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) list.add(jsonArray.getString(i))
            _searchHistory.value = list
        } catch (e: Exception) {
            _searchHistory.value = emptyList()
        }
    }

    private fun addSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        currentList.remove(query); currentList.add(0, query)
        if (currentList.size > 5) currentList.removeAt(currentList.lastIndex)
        _searchHistory.value = currentList
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences(SMB_HISTORY_PREF, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(currentList)
                prefs.edit().putString(SMB_KEY_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) {
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ★ 修正: 検索時に配下のフォルダを横断的に検索するロジックを呼び出す
    fun searchFiles(query: String) {
        _activeSearchQuery.value = query
        _searchQuery.value = query
        if (query.isNotBlank()) {
            addSearchHistory(query)
            performRecursiveSearch(_currentPath.value, query)
        } else {
            // 検索が空になった場合は現在のディレクトリを再読み込み
            loadDirectory(_currentPath.value)
        }
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        loadDirectory(_currentPath.value)
    }

    // ★ 追加: メニューから指定されたソート条件を適用する
    fun setSort(type: SmbSortType, order: SmbSortOrder) {
        _sortType.value = type
        _sortOrder.value = order
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
        searchJob?.cancel() // フォルダ移動時に検索はキャンセル
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _currentPath.value = path
            _rawFileList.value = emptyList()
            _searchQuery.value = ""
            _activeSearchQuery.value = ""

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
                    }
                }

                _rawFileList.value = items

            } catch (e: Exception) {
                Log.e("SmbViewModel", "Failed to load SMB directory: $path", e)
                _errorMessage.value = "エラーが発生しました: ${e.localizedMessage}"
                _rawFileList.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ==========================================
    // ★ 追加: SMB配下の横断的（再帰的）検索
    // ==========================================
    private fun performRecursiveSearch(basePath: String, query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            _rawFileList.value = emptyList() // 検索開始時にリストをクリア

            try {
                val currentServer = cachedServers.find { server ->
                    val port = server.port.ifEmpty { "445" }
                    val host = server.ip.split("/", limit = 2)[0]
                    basePath.startsWith("smb://$host:$port/") || basePath.startsWith("smb://$host/")
                }
                val user = currentServer?.user ?: ""
                val pass = currentServer?.password ?: ""
                val context = SmbContextBuilder.build(user, pass)

                val foundItems = mutableListOf<SmbItem>()
                var lastUpdateTime = System.currentTimeMillis()

                // 再帰検索関数（深さ5まで制限して無限ループや遅延を防止）
                suspend fun traverse(path: String, depth: Int) {
                    if (depth > 5 || !isActive) return
                    try {
                        val smbFile = SmbFile(path, context)
                        if (!smbFile.exists()) return
                        val children = smbFile.listFiles() ?: emptyArray()

                        for (child in children) {
                            if (!isActive) break // キャンセルされたら即停止
                            val name = child.name.replace("/", "")
                            if (name.startsWith(".")) continue

                            val isDir = child.isDirectory

                            // クエリに一致すればリストに追加
                            if (name.contains(query, ignoreCase = true)) {
                                val item = SmbItem(
                                    name = name,
                                    path = child.url.toString(),
                                    isDirectory = isDir,
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
                                foundItems.add(item)

                                // ★ リアルタイムプログレッシブ描画（0.5秒ごとにUIへ順次反映させる）
                                val now = System.currentTimeMillis()
                                if (now - lastUpdateTime > 500) {
                                    _rawFileList.value = foundItems.toList()
                                    lastUpdateTime = now
                                }
                            }

                            // ディレクトリならさらに深堀り
                            if (isDir) {
                                traverse(child.url.toString(), depth + 1)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("SmbViewModel", "Cannot access $path: ${e.message}")
                    }
                }

                traverse(basePath, 0)

                // 最終結果を反映
                _rawFileList.value = foundItems.toList()
                if (foundItems.isEmpty()) {
                    _errorMessage.value = "「${query}」に一致するファイルは見つかりませんでした"
                }

            } catch (e: Exception) {
                Log.e("SmbViewModel", "Search error", e)
                _errorMessage.value = "検索中にエラーが発生しました: ${e.localizedMessage}"
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
        val trimmed = text.trim()

        // 1. 仕様: "c-"で始めて"c"で終わる
        if (!trimmed.startsWith("c-") || !trimmed.endsWith("c")) return emptyList()

        // 先頭の "c-" と末尾の "c" を取り除く ("c-c" の場合は coreContent が空になる)
        val coreContent = trimmed.substring(2, trimmed.length - 1)
        if (coreContent.isEmpty()) return emptyList()

        // 2. 仕様を満たさないコマンドは全体を無視するための事前バリデーション
        // パターン: {正整数}{c|d|e}{文字列}- の連続であること
        if (!coreContent.matches(Regex("^(?:\\d+[cde][^-]*-)+$"))) {
            return emptyList()
        }

        val segments = coreContent.split("-").filter { it.isNotEmpty() }
        val regex = Regex("""^(\d+)([cde])(.*)$""")

        val rawMarkers = mutableListOf<Pair<Long, String>>()
        var lastTimeMs = 0L

        for (segment in segments) {
            val match = regex.find(segment) ?: return emptyList()
            val posValue = match.groupValues[1]
            val type = match.groupValues[2]
            val name = match.groupValues[3]

            val timeMs: Long = when (type) {
                "c" -> posValue.toLongOrNull() ?: 0L
                "d" -> (posValue.toLongOrNull() ?: 0L) * 100L
                "e" -> if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
                else -> return emptyList() // "c" "d" "e" 以外は全体無視
            }

            rawMarkers.add(Pair(timeMs, name))
            lastTimeMs = timeMs
        }

        if (rawMarkers.isEmpty()) return emptyList()

        val safeDurationMs =
            if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
        val chapters = mutableListOf<ChapterInfo>()

        var currentCmStartMs: Long? = null
        var lastChapterEndMs = 0L // 本編区間を補完するための変数

        for (i in rawMarkers.indices) {
            val (timeMs, name) = rawMarkers[i]
            val nextTimeMs =
                if (i + 1 < rawMarkers.size) rawMarkers[i + 1].first else safeDurationMs

            val isCmStart = name.startsWith("ix", ignoreCase = true)
            val isCmEnd = name.startsWith("ox", ignoreCase = true)

            if (isCmStart && currentCmStartMs == null) {
                // [補完] 直前の終了位置から今回のCM開始位置までにギャップがあれば「本編」として追加
                if (lastChapterEndMs < timeMs) {
                    chapters.add(
                        ChapterInfo(
                            startTimeMs = lastChapterEndMs,
                            endTimeMs = timeMs,
                            isCm = false,
                            isMarkerOnly = false,
                            label = "" // UI表示用に "本編" などに変更可能です
                        )
                    )
                }
                currentCmStartMs = timeMs
            } else if (isCmEnd && currentCmStartMs != null) {
                // CM区間の追加
                chapters.add(
                    ChapterInfo(
                        startTimeMs = currentCmStartMs,
                        endTimeMs = timeMs,
                        isCm = true,
                        isMarkerOnly = false,
                        label = ""
                    )
                )
                currentCmStartMs = null
                lastChapterEndMs = timeMs // 次の本編の開始位置を更新
            }

            // ixでもoxでもない通常のマーカー（C5Sec など）
            if (!isCmStart && !isCmEnd) {
                chapters.add(
                    ChapterInfo(
                        startTimeMs = timeMs,
                        endTimeMs = nextTimeMs,
                        isCm = false,
                        isMarkerOnly = true,
                        label = name
                    )
                )
            }
        }

        // 終端処理 (CMが閉じられずに終わった場合)
        if (currentCmStartMs != null) {
            chapters.add(
                ChapterInfo(
                    startTimeMs = currentCmStartMs,
                    endTimeMs = safeDurationMs,
                    isCm = true,
                    isMarkerOnly = false,
                    label = ""
                )
            )
            lastChapterEndMs = safeDurationMs
        }

        // [補完] 最後のマーカーから終端までの本編区間を追加
        if (lastChapterEndMs < safeDurationMs) {
            chapters.add(
                ChapterInfo(
                    startTimeMs = lastChapterEndMs,
                    endTimeMs = safeDurationMs,
                    isCm = false,
                    isMarkerOnly = false,
                    label = ""
                )
            )
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
                currentStartMs = -1L
            }
        }

        if (rawMarkers.isEmpty()) return emptyList()

        val lastTimeMs = rawMarkers.last().first
        val safeDurationMs =
            if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
        val chapters = mutableListOf<ChapterInfo>()

        for (i in 0 until rawMarkers.size) {
            val (timeMs, name) = rawMarkers[i]
            val nextTimeMs =
                if (i + 1 < rawMarkers.size) rawMarkers[i + 1].first else safeDurationMs
            val isCm = name.contains("CM", ignoreCase = true) || name.contains(
                "Sponsor",
                ignoreCase = true
            )

            if (isCm) {
                chapters.add(
                    ChapterInfo(
                        timeMs,
                        nextTimeMs,
                        isCm = true,
                        isMarkerOnly = false,
                        label = ""
                    )
                )
            }
            chapters.add(
                ChapterInfo(
                    timeMs,
                    nextTimeMs,
                    isCm = false,
                    isMarkerOnly = true,
                    label = name
                )
            )
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