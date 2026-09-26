package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.content
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

sealed class AiConciergeAction {
    data class PlayLive(val channelId: String) : AiConciergeAction()
    data class PlayRecorded(val videoId: Int) : AiConciergeAction()

    data class SearchEpg(
        val keyword: String,
        val genre: String,
        val date: String,
        val isLiveOnly: Boolean,
        val channelName: String
    ) : AiConciergeAction()

    data class SearchRecord(
        val keyword: String,
        val genre: String
    ) : AiConciergeAction()

    data class ReqEpgSearch(
        val keyword: String,
        val genre: String,
        val date: String,
        val isLiveOnly: Boolean,
        val channelName: String
    ) : AiConciergeAction()

    data class ReqRecSearch(
        val keyword: String,
        val genre: String
    ) : AiConciergeAction()

    data class ReserveSingle(val programId: String) : AiConciergeAction()
    data class ReserveAuto(val keyword: String) : AiConciergeAction()
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val isThinking: Boolean = false,
    val isHidden: Boolean = false
)

data class AiContextData(
    val liveChannels: Map<String, List<Channel>>,
    val groupedSeries: Map<String, List<SeriesInfo>>
)

@HiltViewModel
class AiConciergeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _internalChatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _internalChatHistory
        .map { list -> list.filter { !it.isHidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingAction = MutableSharedFlow<AiConciergeAction>()
    val pendingAction = _pendingAction.asSharedFlow()

    private var lastContextData: AiContextData? = null

    private fun getGenerativeModel(apiKey: String): GenerativeModel {
        return GenerativeModel(
            modelName = "gemini-3.6-flash",
            apiKey = apiKey,
            systemInstruction = content {
                text(
                    "あなたはTVアプリ「Komorebi」の優秀で親しみやすいAIコンシェルジュです。\n" +
                            "ユーザーの意図を汲み取り、アプリの操作が必要な場合のみ【特殊コマンドタグ】を出力します。\n\n" +
                            "【対話による条件の絞り込み（マルチターン検索）】\n" +
                            "ユーザーの要望が「何か面白い番組ある？」「アニメ見たい」など非常に曖昧な場合、すぐに当てずっぽうで検索コマンドを出すのではなく、「今はどんな気分ですか？笑ってスカッとしたいですか、それとも感動して泣きたいですか？」のように、**1〜2回の対話を通じて条件を絞り込む質問**をしてください。\n" +
                            "ユーザーとの会話を通して要望が具体的になったタイミングで、初めて以下の検索コマンドを出力してください。\n\n" +
                            "【最重要: アクション（タグ出力）のフェーズ分け】\n" +
                            "★ システムから検索結果が返ってきた直後（報告・提案のフェーズ）では、絶対に `[PLAY_LIVE]` `[PLAY_REC]` `[RESERVE_SINGLE]` `[RESERVE_AUTO]` などの**実行タグを出力してはいけません**。\n" +
                            "★ 「これらの番組が見つかりました。予約しますか？」と提案するだけにとどめてください。\n" +
                            "★ ユーザーから「うん、予約して」「1番を再生して」などと**明確な承諾・指示があった場合のみ**、実行タグを出力して操作を実行してください。\n\n" +
                            "【チャンネル切り替えの推論】\n" +
                            "「BS11にして」「日テレが見たい」などライブ視聴の要望があった場合、必ずコンテキストの【対応チャンネル一覧】から該当チャンネルを探し、名前の横にある LIVE_ID を使って [PLAY_LIVE: LIVE_ID] を出力してください。\n\n" +
                            "【自律検索（RAG）とスマートキーワード展開】\n" +
                            "あなたの手元には録画リストや未来の番組表データはありません。番組を探すよう依頼されたら必ず自ら検索コマンドを出力してシステムに裏側検索を要求し、結果を待ってから回答してください。\n" +
                            "システムは「単語の完全一致検索」しかできません。「笑える」「泣ける」などの抽象的な気分で検索を頼まれた場合、あなたの持つ豊富な事前知識と、コンテキストにある【ユーザーの好みの傾向】を参考に、ユーザーの好みに合いそうな具体的な関連キーワード（同義語、番組名、出演者など）を3〜5個推論し、**カンマ区切り**で指定してください。\n" +
                            "例: 「笑えるバラエティ」 -> [REQ_REC_SEARCH: 水曜日のダウンタウン,アメトーーク,コント,お笑い | バラエティ]\n" +
                            "例: 「乃木坂が出てるやつ」 -> [REQ_REC_SEARCH: 乃木坂工事中,乃木坂46,バナナマン | ]\n\n" +
                            "【自律検索（裏側検索）のタグ一覧】\n" +
                            "・未来の番組や現在放送中の番組を探す場合:\n" +
                            "  [REQ_EPG_SEARCH: 固有名詞1,固有名詞2 | ジャンル | 日付 | 生中継(true/false) | チャンネル名]\n" +
                            "・既に録画済みの番組を探す場合:\n" +
                            "  [REQ_REC_SEARCH: 固有名詞1,固有名詞2 | ジャンル]\n\n" +
                            "【一般知識や雑談への対応】\n" +
                            "テレビ番組と無関係な質問（トリビア、天気、日常会話など）には、タグを使わず直接、明るく簡潔に回答してください。\n\n" +
                            "【UI操作タグ一覧（ユーザーの明確な指示があった場合に出力）】\n" +
                            "・番組表検索画面を開く: [SEARCH_EPG: キーワード | ジャンル | 日付 | 生中継(true/false) | チャンネル名]\n" +
                            "・録画リスト画面を開く: [SEARCH_REC: キーワード | ジャンル]\n" +
                            "・予約実行: [RESERVE_SINGLE: PROGRAM_ID]\n" +
                            "・自動録画条件の登録: [RESERVE_AUTO: キーワード]\n"
                )
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendTextWithContext(
        userInput: String,
        liveChannels: Map<String, List<Channel>>,
        recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>,
        activeReserves: List<ReserveItem>
    ) {
        viewModelScope.launch {
            lastContextData = AiContextData(liveChannels, groupedSeries)
            val userMsg = ChatMessage(isUser = true, text = userInput)
            val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
            val historyText = buildHistoryText()
            _internalChatHistory.value = _internalChatHistory.value + userMsg + aiThinkingMsg

            try {
                val rawApiKey = settingsRepository.geminiApiKey.first()
                val currentApiKey = rawApiKey.trim()

                if (currentApiKey.isBlank()) throw IllegalStateException("APIキーが設定されていません")

                val generativeModel = getGenerativeModel(currentApiKey)
                val contextPrompt = withContext(Dispatchers.Default) {
                    buildMinimalContextPrompt(liveChannels, groupedSeries)
                }
                val response =
                    generativeModel.generateContent(content { text("$contextPrompt\n$historyText\n\n現在のユーザーの指示: 「$userInput」") })
                handleAiResponse(response.text, aiThinkingMsg.id, null)
            } catch (e: Exception) {
                handleAiError(e, aiThinkingMsg.id)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendAudioWithContext(
        audioBytes: ByteArray,
        liveChannels: Map<String, List<Channel>>,
        recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>,
        activeReserves: List<ReserveItem>
    ) {
        viewModelScope.launch {
            lastContextData = AiContextData(liveChannels, groupedSeries)
            val userMsgId = UUID.randomUUID().toString()
            val userMsg = ChatMessage(id = userMsgId, isUser = true, text = "🎤 音声を解析中...")
            val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
            val historyText = buildHistoryText()
            _internalChatHistory.value = _internalChatHistory.value + userMsg + aiThinkingMsg

            try {
                val rawApiKey = settingsRepository.geminiApiKey.first()
                val currentApiKey = rawApiKey.trim()

                if (currentApiKey.isBlank()) throw IllegalStateException("APIキーが設定されていません")

                val generativeModel = getGenerativeModel(currentApiKey)
                val contextPrompt = withContext(Dispatchers.Default) {
                    buildMinimalContextPrompt(liveChannels, groupedSeries)
                }
                val response = generativeModel.generateContent(content {
                    blob("audio/wav", audioBytes)
                    text("$contextPrompt\n$historyText\n\n必ずレスポンスの1行目に「認識結果: (言葉)」と出力してから回答してください。")
                })
                handleAiResponse(response.text, aiThinkingMsg.id, userMsgId)
            } catch (e: Exception) {
                handleAiError(e, aiThinkingMsg.id)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun submitSilentSearchResult(keyword: String, results: List<UiSearchResultItem>) {
        viewModelScope.launch {
            val searchResultText = if (results.isEmpty()) {
                "システム: 番組表の検索結果は0件でした。これ以上検索は繰り返さず、見つからなかった旨をユーザーに伝えてください。"
            } else {
                val sb = java.lang.StringBuilder("システム: 番組表の検索結果は以下の通りです。\n")
                results.take(5).forEach { res ->
                    val p = res.program
                    sb.append("- ${p.title} (${p.start_time}) [PROGRAM_ID: ${p.id}] [LIVE_ID: ${res.channel.id}]\n")
                }
                sb.append("\n※注意: ここで勝手に [RESERVE_SINGLE] や [PLAY_LIVE] などの実行タグを出力してはいけません。ユーザーに番組内容と「予約しますか？」「再生しますか？」という提案だけを行ってください。")
                sb.toString()
            }
            processSilentResult(searchResultText)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun submitSilentRecordSearchResult(keyword: String, results: List<RecordedProgram>) {
        viewModelScope.launch {
            val searchResultText = if (results.isEmpty()) {
                "システム: 録画リストの検索結果は0件でした。これ以上検索は繰り返さず、見つからなかった旨をユーザーに伝えてください。"
            } else {
                val sb =
                    java.lang.StringBuilder("システム: 録画リストの検索結果は以下の通りです。\n")
                results.take(5).forEach { p ->
                    sb.append("- ${p.title} (${p.startTime}) [REC_ID: ${p.id}]\n")
                }
                sb.append("\n※注意: ここで勝手に [PLAY_REC] などの実行タグを出力してはいけません。ユーザーに録画内容と「再生しますか？」という提案だけを行ってください。")
                sb.toString()
            }
            processSilentResult(searchResultText)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processSilentResult(systemMessage: String) {
        _internalChatHistory.value = _internalChatHistory.value + ChatMessage(
            isUser = true,
            text = systemMessage,
            isHidden = true
        )
        val aiThinkingMsg = ChatMessage(isUser = false, text = "", isThinking = true)
        _internalChatHistory.value = _internalChatHistory.value + aiThinkingMsg

        try {
            val rawApiKey = settingsRepository.geminiApiKey.first()
            val currentApiKey = rawApiKey.trim()

            if (currentApiKey.isBlank()) throw IllegalStateException("APIキーが設定されていません")

            val generativeModel = getGenerativeModel(currentApiKey)
            val historyText = buildHistoryText()
            val contextData = lastContextData
            val contextPrompt = if (contextData != null) {
                withContext(Dispatchers.Default) {
                    buildMinimalContextPrompt(contextData.liveChannels, contextData.groupedSeries)
                }
            } else ""

            val response = generativeModel.generateContent(content {
                text("$contextPrompt\n$historyText\n\nシステムからの検索結果を元に、ユーザーへの回答を生成してください。")
            })
            handleAiResponse(response.text, aiThinkingMsg.id, null)
        } catch (e: Exception) {
            handleAiError(e, aiThinkingMsg.id)
        }
    }

    private suspend fun handleAiResponse(rawText: String?, messageId: String, userMsgId: String?) {
        var responseText = rawText ?: ""

        Log.i("AI_Concierge", "🤖 AI Raw Output:\n$responseText")

        if (userMsgId != null) {
            val recognizedRegex = Regex("""認識結果:\s*(.+)""")
            val recognizedMatch = recognizedRegex.find(responseText)
            if (recognizedMatch != null) {
                val recognizedText = recognizedMatch.groupValues[1]
                _internalChatHistory.value = _internalChatHistory.value.map {
                    if (it.id == userMsgId) it.copy(text = "🎤 「$recognizedText」") else it
                }
                responseText = responseText.replace(recognizedRegex, "").trim()
            } else {
                _internalChatHistory.value = _internalChatHistory.value.map {
                    if (it.id == userMsgId) it.copy(text = "🎤 (音声入力)") else it
                }
            }
        }

        val liveRegex = Regex("""\[PLAY_LIVE:\s*([^\]\s]+)\]?""")
        val recRegex = Regex("""\[PLAY_REC:\s*([0-9]+)\]?""")

        val searchEpgRegex = Regex("""\[SEARCH_EPG:\s*([^\]]+)\]""")
        val searchRecRegex = Regex("""\[SEARCH_REC:\s*([^\]]+)\]""")

        val reqSearchEpgRegex = Regex("""\[REQ_EPG_SEARCH:\s*([^\]]+)\]""")
        val reqSearchRecRegex = Regex("""\[REQ_REC_SEARCH:\s*([^\]]+)\]""")

        val resSingleRegex = Regex("""\[RESERVE_SINGLE:\s*([^\]\s]+).*?\]""")
        val resAutoRegex = Regex("""\[RESERVE_AUTO:\s*([^\]\|]+).*?\]""")

        val liveMatch = liveRegex.find(responseText)
        val recMatch = recRegex.find(responseText)
        val searchEpgMatch = searchEpgRegex.find(responseText)
        val searchRecMatch = searchRecRegex.find(responseText)
        val reqSearchEpgMatch = reqSearchEpgRegex.find(responseText)
        val reqSearchRecMatch = reqSearchRecRegex.find(responseText)
        val resSingleMatches = resSingleRegex.findAll(responseText).toList()
        val resAutoMatch = resAutoRegex.find(responseText)

        listOf(
            liveRegex, recRegex, searchEpgRegex, searchRecRegex,
            reqSearchEpgRegex, reqSearchRecRegex, resSingleRegex, resAutoRegex
        ).forEach { regex ->
            responseText = responseText.replace(regex, "").trim()
        }

        if (responseText.isBlank() && (liveMatch != null || recMatch != null)) responseText =
            "はい、再生を開始します！"
        else if (responseText.isBlank() && searchEpgMatch != null) responseText =
            "はい、番組表を検索します！"
        else if (responseText.isBlank() && searchRecMatch != null) responseText =
            "はい、録画リストを検索します！"
        else if (responseText.isBlank() && (resSingleMatches.isNotEmpty() || resAutoMatch != null)) responseText =
            "はい、予約を登録しました！"
        else if (responseText.isBlank() && reqSearchEpgMatch == null && reqSearchRecMatch == null) responseText =
            "すみません、うまく処理できませんでした。"

        _internalChatHistory.value = _internalChatHistory.value.map {
            if (it.id == messageId) it.copy(text = responseText, isThinking = false) else it
        }

        val displayDelayMs = (responseText.length * 15L) + 1500L

        when {
            liveMatch != null -> viewModelScope.launch {
                delay(displayDelayMs)
                _pendingAction.emit(AiConciergeAction.PlayLive(liveMatch.groupValues[1].trim()))
            }

            recMatch != null -> {
                val id = recMatch.groupValues[1].trim().toIntOrNull()
                if (id != null) viewModelScope.launch {
                    delay(displayDelayMs)
                    _pendingAction.emit(AiConciergeAction.PlayRecorded(id))
                }
            }

            searchEpgMatch != null -> {
                val parts = searchEpgMatch.groupValues[1].split("|").map { it.trim() }
                viewModelScope.launch {
                    delay(displayDelayMs)
                    _pendingAction.emit(
                        AiConciergeAction.SearchEpg(
                            parts.getOrNull(0) ?: "",
                            parts.getOrNull(1) ?: "",
                            parts.getOrNull(2) ?: "",
                            parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
                            parts.getOrNull(4) ?: ""
                        )
                    )
                }
            }

            searchRecMatch != null -> {
                val parts = searchRecMatch.groupValues[1].split("|").map { it.trim() }
                viewModelScope.launch {
                    delay(displayDelayMs)
                    _pendingAction.emit(
                        AiConciergeAction.SearchRecord(
                            parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: ""
                        )
                    )
                }
            }

            reqSearchEpgMatch != null -> {
                val parts = reqSearchEpgMatch.groupValues[1].split("|").map { it.trim() }
                _pendingAction.emit(
                    AiConciergeAction.ReqEpgSearch(
                        parts.getOrNull(0) ?: "",
                        parts.getOrNull(1) ?: "",
                        parts.getOrNull(2) ?: "",
                        parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
                        parts.getOrNull(4) ?: ""
                    )
                )
            }

            reqSearchRecMatch != null -> {
                val parts = reqSearchRecMatch.groupValues[1].split("|").map { it.trim() }
                _pendingAction.emit(
                    AiConciergeAction.ReqRecSearch(
                        parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: ""
                    )
                )
            }

            resSingleMatches.isNotEmpty() -> viewModelScope.launch {
                delay(displayDelayMs)
                resSingleMatches.forEach { match ->
                    _pendingAction.emit(AiConciergeAction.ReserveSingle(match.groupValues[1].trim()))
                }
            }

            resAutoMatch != null -> viewModelScope.launch {
                delay(displayDelayMs)
                _pendingAction.emit(AiConciergeAction.ReserveAuto(resAutoMatch.groupValues[1].trim()))
            }
        }
    }

    private fun handleAiError(e: Exception, messageId: String) {
        Log.e("AI_Concierge", "🚨 AI API通信エラー発生: ", e)
        val errorText = when {
            e is QuotaExceededException || e.message?.contains("429") == true -> "【API制限】少し待ってからお試しください。"
            e.message?.contains(
                "timeout",
                ignoreCase = true
            ) == true -> "【タイムアウト】サーバーからの応答がありません。"

            e.message?.contains(
                "API key not valid",
                ignoreCase = true
            ) == true -> "【認証エラー】APIキーが間違っています。設定を確認してください。"

            e.message?.contains("APIキーが設定されていません") == true -> "【未設定】設定画面の「AIコンシェルジュ (Gemini)」からAPIキーを登録してください。"
            else -> "通信エラーが発生しました: ${e.localizedMessage}"
        }
        _internalChatHistory.value = _internalChatHistory.value.map {
            if (it.id == messageId) it.copy(text = errorText, isThinking = false) else it
        }
    }

    private fun buildHistoryText(): String {
        // ★ 履歴数を増やしすぎるとトークン消費が激しいため、直近6件（3ターン）に制限したまま運用
        val recentHistory = _internalChatHistory.value.filter { !it.isThinking }.takeLast(6)
        if (recentHistory.isEmpty()) return ""
        val sb = StringBuilder("\n【直近の会話履歴】\n")
        recentHistory.forEach { msg -> sb.append("${if (msg.isUser) "ユーザー(またはシステム)" else "AI"}: ${msg.text}\n") }
        return sb.toString()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildMinimalContextPrompt(
        liveChannels: Map<String, List<Channel>>,
        groupedSeries: Map<String, List<SeriesInfo>>
    ): String {
        val sb = StringBuilder(1000)
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        sb.append("【現在の情報】\n時刻: ${LocalDateTime.now().format(formatter)}\n\n")

        sb.append("【対応チャンネル一覧 (名前:LIVE_ID)】\n")
        liveChannels.forEach { (network, channels) ->
            val compressedCh = channels.joinToString(", ") { "${it.name}:${it.id}" }
            sb.append("- [$network] $compressedCh\n")
        }

        // ★ 修正: 全件送るのをやめ、録画数が多いトップ15シリーズだけを「ユーザーの好み」として抽出
        if (groupedSeries.isNotEmpty()) {
            sb.append("\n【ユーザーの好みの傾向（よく録画するシリーズ上位）】\n")
            val topSeries = groupedSeries.values.flatten()
                .sortedByDescending { it.programCount }
                .take(15) // 上位15件なら数十トークンで収まる
                .joinToString(", ") { it.displayTitle }
            sb.append(topSeries).append("\n")
        }
        return sb.toString()
    }

    fun resetState() {
        _internalChatHistory.value = emptyList()
        lastContextData = null
    }
}