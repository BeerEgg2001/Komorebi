package com.beeregg2001.komorebi.util

import java.text.Normalizer

object TitleNormalizer {

    private val RP1_PREFIXES = listOf(
        "ＢＳ深夜アニメ館", "無料≫", "日５『", "日5『", "日５「", "日5「",
        "アニメＡ・", "アニメA・", "TV放送版『", "放送直前特番「", "アニメイズム「",
        "＜アニメ＞『", "＜アニメ＞", "アニメ　ＴＶアニメ『", "ＴＶアニメ「", "TVアニメ「",
        "ＴＶアニメ『", "TVアニメ『", "アニメ「", "アニメ", "ノイタミナ「", "木曜劇場「",
        "オシドラサタデー「", "ドラマプレミア23「", "よるドラ「", "アサバラ"
    )

    private val FRAME_PREFIXES = listOf(
        "金曜ロードショー", "土曜プレミアム", "月曜プレミア", "日曜洋画劇場",
        "午後のロードショー", "連続テレビ小説", "大河ドラマ", "ドラマプレミア",
        "木曜劇場", "ドラマ24", "夜ドラ", "プレミアムシネマ"
    )

    fun extractDisplayTitle(fullTitle: String?): String {
        // ★修正: エルビス演算子を使って確実に String 型 (非null) として取り出す
        val safeTitle = fullTitle ?: return "不明な番組"
        if (safeTitle.isBlank()) return "不明な番組"

        var title = safeTitle

        for (prefix in RP1_PREFIXES) {
            title = title.replace(prefix, "")
        }

        title = title.replace(Regex("\\[.*?\\]|\\(.*?\\)|（.*?）|<.*?>|［.*?］"), "")
        title = title.replace(Regex("^【.*?】"), "")
        title = Normalizer.normalize(title, Normalizer.Form.NFKC)

        for (frame in FRAME_PREFIXES) {
            if (title.contains(frame, ignoreCase = true)) {
                return frame
            }
        }

        title = title.replace(
            Regex("(第|第\\s*)([\\d一二三四五六七八九十百千万]+)\\s*(話|回|夜|弾|週|巻|部|期|章|幕|局|戦|シリーズ|シーズン).*"),
            ""
        )
        title = title.replace(Regex("(#|＃)\\s*\\d+.*"), "")
        title = title.replace(Regex("(?i)(season|episode|ep|sec)\\.?\\s*\\d+.*"), "")

        fun safeCut(currentTitle: String, cutIndex: Int): String {
            if (cutIndex <= 3) return currentTitle
            return currentTitle.substring(0, cutIndex)
        }

        val splitRegex = Regex("(\\s+▽|\\s+▼|\\s+-[\\s\\-]*|\\s+〜|\\s+～|\\s+／|\\s*\\*\\s*)")
        val splitMatch = splitRegex.find(title)
        if (splitMatch != null) {
            title = safeCut(title, splitMatch.range.first)
        }

        val exclRegex = Regex("([！？!?])\\s+.*")
        val exclMatch = exclRegex.find(title)
        if (exclMatch != null) {
            title = safeCut(title, exclMatch.range.first + 1)
        }

        val spRegex =
            Regex("([！？!?])(SP|スペシャル|特番|ダイジェスト|傑作選|総集編|新春|年末|年始|秋の|冬の|春の|夏の).*")
        val spMatch = spRegex.find(title)
        if (spMatch != null) {
            title = safeCut(title, spMatch.range.first + 1)
        }

        val bracketMatch = Regex("([「『＜<【]).*").find(title)
        if (bracketMatch != null) {
            title = safeCut(title, bracketMatch.range.first)
        }

        val spaceIdx = title.indexOfFirst { it == ' ' || it == '　' }
        if (spaceIdx > 0) {
            title = safeCut(title, spaceIdx)
        }

        title = title.replace(
            Regex("(拡大版?|直前|直後|豪華|大|超|秋の|冬の|春の|夏の|年末|年始|新春|最終回|完全版)?(SP|スペシャル|特番|ダイジェスト|傑作選|総集編).*$"),
            ""
        )

        return title.replace(Regex("^[\\s　・\\-！!？?。、]+|[\\s　・\\-！!？?。、]+$"), "").trim()
    }

    fun getGroupingKey(fullTitle: String?): String {
        return extractDisplayTitle(fullTitle)
            .uppercase()
            .replace(Regex("[^A-Z0-9\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF]"), "")
    }

    fun toSqlSearchQuery(baseTitle: String?): String {
        // ★修正: こちらも安全に String 型として取り扱う
        val safeTitle = baseTitle ?: return ""
        if (safeTitle.isBlank()) return ""
        return safeTitle.replace(Regex("[%_]"), "").trim()
    }

    fun extractSearchKeyword(fullTitle: String?): String {
        return toSqlSearchQuery(extractDisplayTitle(fullTitle))
    }

    fun hasEpisodeNumber(fullTitle: String?): Boolean {
        // ★修正: 同様に安全に取り扱う
        val safeTitle = fullTitle ?: return false
        val regex =
            Regex("(第\\s*[\\d一二三四五六七八九十百千万]+\\s*(話|回|夜|弾|週|巻|部|期|章|幕|局|戦|シリーズ|シーズン))|([#＃]\\s*\\d+)|((?i)(season|episode|ep|sec)\\.?\\s*\\d+)")
        return regex.containsMatchIn(safeTitle)
    }
}