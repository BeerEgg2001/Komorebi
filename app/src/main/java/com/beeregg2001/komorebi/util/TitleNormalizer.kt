package com.beeregg2001.komorebi.util

import java.text.Normalizer

/**
 * 日本のテレビ番組特有の複雑なタイトル表記揺れを吸収し、
 * 「AI名寄せ（シリーズごとのグループ化）」を行うためのユーティリティクラス。
 */
object TitleNormalizer {

    // アニメや深夜ドラマなどに付きがちな、シリーズ名そのものには不要な接頭辞のリスト
    private val RP1_PREFIXES = listOf(
        "ＢＳ深夜アニメ館", "無料≫", "日５『", "日5『", "日５「", "日5「",
        "アニメＡ・", "アニメA・", "TV放送版『", "放送直前特番「", "アニメイズム「",
        "＜アニメ＞『", "＜アニメ＞", "アニメ　ＴＶアニメ『", "ＴＶアニメ「", "TVアニメ「",
        "ＴＶアニメ『", "TVアニメ『", "アニメ「", "アニメ", "ノイタミナ「", "木曜劇場「",
        "オシドラサタデー「", "ドラマプレミア23「", "よるドラ「", "アサバラ", "火アニバル"
    )

    // 個別の作品名よりも、放送枠の名前そのものでグループ化すべき枠のリスト
    // （例：「金曜ロードショー『となりのトトロ』」→「金曜ロードショー」としてまとめる）
    private val FRAME_PREFIXES = listOf(
        "金曜ロードショー", "土曜プレミアム", "月曜プレミア", "日曜洋画劇場",
        "午後のロードショー", "連続テレビ小説", "大河ドラマ", "ドラマプレミア",
        "木曜劇場", "ドラマ24", "夜ドラ", "プレミアムシネマ"
    )

    /**
     * EPGの生の番組タイトルから、装飾や話数、サブタイトルを削ぎ落とし、
     * 「コアとなるシリーズ名（代表名）」を抽出します。
     */
    fun extractDisplayTitle(fullTitle: String?): String {
        // ★修正: エルビス演算子を使って確実に String 型 (非null) として取り出す
        val safeTitle = fullTitle ?: return "不明な番組"
        if (safeTitle.isBlank()) return "不明な番組"

        var title = safeTitle

        // 1. 不要な接頭辞（「TVアニメ『」など）を削除
        for (prefix in RP1_PREFIXES) {
            title = title.replace(prefix, "")
        }

        // 2. 括弧で囲まれた補足情報（【新】、【終】、[字]、(再) など）を削除
        title = title.replace(Regex("\\[.*?\\]|\\(.*?\\)|（.*?）|<.*?>|［.*?］"), "")
        title = title.replace(Regex("^【.*?】"), "") // 行頭の隅付き括弧も削除

        // 3. 全角/半角、ひらがな/カタカナ等の揺れを吸収するため、NFKCで正規化
        title = Normalizer.normalize(title, Normalizer.Form.NFKC)

        // 4. 金曜ロードショーなどの「固定枠」に合致する場合は、作品名ではなく枠名を返す
        for (frame in FRAME_PREFIXES) {
            if (title.contains(frame, ignoreCase = true)) {
                return frame
            }
        }

        // 5. 話数表記（第1話、第2期、シーズン3 など）とその後に続くサブタイトルを丸ごと削ぎ落とす
        title = title.replace(
            Regex("(第|第\\s*)([\\d一二三四五六七八九十百千万]+)\\s*(話|回|夜|弾|週|巻|部|期|章|幕|局|戦|シリーズ|シーズン).*"),
            ""
        )
        // #1 や ＃02 などの表記とそれに続くサブタイトルを削除
        title = title.replace(Regex("(#|＃)\\s*\\d+.*"), "")
        // season 1 や ep.2 などの英語表記を削除
        title = title.replace(Regex("(?i)(season|episode|ep|sec)\\.?\\s*\\d+.*"), "")

        // 安全に文字列をカットするためのヘルパー（3文字以下の場合は過剰なカットを防ぐ）
        fun safeCut(currentTitle: String, cutIndex: Int): String {
            if (cutIndex <= 3) return currentTitle
            return currentTitle.substring(0, cutIndex)
        }

        // 6. 記号によるサブタイトルの分離（「本編タイトル ▽ サブタイトル」や「タイトル -サブ-」など）
        val splitRegex = Regex("(\\s+▽|\\s+▼|\\s+-[\\s\\-]*|\\s+〜|\\s+～|\\s+／|\\s*\\*\\s*)")
        val splitMatch = splitRegex.find(title)
        if (splitMatch != null) {
            title = safeCut(title, splitMatch.range.first)
        }

        // 7. 「！」や「？」の後に続くテキストをサブタイトルとみなしてカット
        val exclRegex = Regex("([！？!?])\\s+.*")
        val exclMatch = exclRegex.find(title)
        if (exclMatch != null) {
            title = safeCut(title, exclMatch.range.first + 1)
        }

        // 8. 「！SP」や「？特番」といった表記の直前でカット（特番表記そのものは消す）
        val spRegex =
            Regex("([！？!?])(SP|スペシャル|特番|ダイジェスト|傑作選|総集編|新春|年末|年始|秋の|冬の|春の|夏の).*")
        val spMatch = spRegex.find(title)
        if (spMatch != null) {
            title = safeCut(title, spMatch.range.first + 1)
        }

        // 9. タイトルの途中に「「」や「『」などの開き括弧がある場合、そこから後ろは副題とみなす
        val bracketMatch = Regex("([「『＜<【]).*").find(title)
        if (bracketMatch != null) {
            title = safeCut(title, bracketMatch.range.first)
        }

        // 10. 最初のスペース（全角・半角）でカット（以降はサブタイトルや出演者名とみなす）
        val spaceIdx = title.indexOfFirst { it == ' ' || it == '　' }
        if (spaceIdx > 0) {
            title = safeCut(title, spaceIdx)
        }

        // 11. 末尾に残った特番や総集編を示すキーワードを削除
        title = title.replace(
            Regex("(拡大版?|直前|直後|豪華|大|超|秋の|冬の|春の|夏の|年末|年始|新春|最終回|完全版)?(SP|スペシャル|特番|ダイジェスト|傑作選|総集編).*$"),
            ""
        )

        // 12. 前後に残った不要な空白や記号をトリミングしてクリーンな文字列を返す
        return title.replace(Regex("^[\\s　・\\-！!？?。、]+|[\\s　・\\-！!？?。、]+$"), "").trim()
    }

    /**
     * DBでのシリーズグループ化（GROUP BY）に使用するための、厳密な名寄せキーを生成します。
     * 表示用タイトルからさらに記号や空白を完全に排除し、英数字と日本語のみを残します。
     */
    fun getGroupingKey(fullTitle: String?): String {
        return extractDisplayTitle(fullTitle)
            .uppercase()
            .replace(Regex("[^A-Z0-9\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF]"), "")
    }

    /**
     * SQLite（Room）のLIKE検索において、ワイルドカード文字（% や _）として
     * 解釈されてしまうのを防ぐため、それらの文字を取り除きます。
     */
    fun toSqlSearchQuery(baseTitle: String?): String {
        // ★修正: こちらも安全に String 型として取り扱う
        val safeTitle = baseTitle ?: return ""
        if (safeTitle.isBlank()) return ""
        return safeTitle.replace(Regex("[%_]"), "").trim()
    }

    /**
     * 表示用タイトルから、データベース検索用の安全なキーワードを生成します。
     */
    fun extractSearchKeyword(fullTitle: String?): String {
        return toSqlSearchQuery(extractDisplayTitle(fullTitle))
    }

    /**
     * 番組のフルタイトル内に、エピソード（話数）を示す文字列が含まれているかを判定します。
     * これにより、単発特番か連ドラ（アニメ）かをAI名寄せエンジンが判断します。
     */
    fun hasEpisodeNumber(fullTitle: String?): Boolean {
        // ★修正: 同様に安全に取り扱う
        val safeTitle = fullTitle ?: return false
        val regex =
            Regex("(第\\s*[\\d一二三四五六七八九十百千万]+\\s*(話|回|夜|弾|週|巻|部|期|章|幕|局|戦|シリーズ|シーズン))|([#＃]\\s*\\d+)|((?i)(season|episode|ep|sec)\\.?\\s*\\d+)")
        return regex.containsMatchIn(safeTitle)
    }
}