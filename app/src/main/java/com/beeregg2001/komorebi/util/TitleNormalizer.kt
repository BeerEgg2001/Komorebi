package com.beeregg2001.komorebi.util

import java.text.Normalizer
import java.util.regex.Pattern

object TitleNormalizer {
    // 1. 放送記号の徹底的な除去（表示用）
    private val TAGS_PATTERN = Pattern.compile(
        "\\[(字|二|デ|解|多|S|新|終|再|無|SS|HV|P|W|手|初|生|N|H|複|双|別|カ|英|韓|中|天|擬|吹|撮|録|問|画|前|後|編|回|全|話)\\]|" +
                "\\((字|二|デ|解|多|S|新|終|再|無|SS|HV|P|W|手|初|生|N|H|複|双|別|カ|英|韓|中|天|擬|吹|撮|録|問|画|前|後|編|回|全|話)\\)|" +
                "（(字|二|デ|解|多|S|新|終|再|無|SS|HV|P|W|手|初|生|N|H|複|双|別|カ|英|韓|中|天|擬|吹|撮|録|問|画|前|後|編|回|全|話)）|" +
                "【(新|終|再|初|字|二|デ|解|無料|生|録)】|" +
                "［(新|終|再|初|字|二|デ|解|無料|生|録)］"
    )

    // 2. 先頭のタグのみを除去するパターン（検索用）
    private val LEADING_TAGS_PATTERN = Pattern.compile("^(?:\\[.*?\\]|【.*?】|［.*?］|\\(.*?\\)|（.*?）)+")

    // ★追加: 半角・全角数字に加えて、漢数字もマッチさせるパターン定数
    private const val NUM = "[ 0-9０-９一二三四五六七八九十百千万零]+"

    // 3. 話数・サブタイトルの分離パターン（表示用）
    // $NUM を使うことで、アラビア数字だけでなく「第一話」「第百回」「第一期」なども検知可能に
    private val EPISODE_SUBTITLE_PATTERN = Pattern.compile(
        "(?:[ 　]+)(?:第$NUM[話回幕]?|$NUM[話回幕]|#+$NUM|\\($NUM\\)|（$NUM）|EP\\.?$NUM).*$|\\($NUM\\)|" +
                "(?:[ 　]+)(?:「|『|【|＜|〈|~|〜|—|-|▽|▼).*$|" +
                "(?:第$NUM[話回幕]?|$NUM[話回幕]|#+$NUM|\\($NUM\\)|（$NUM）|EP\\.?$NUM)$|第$NUM[部]|#$NUM|" +
                "(?:[ 　]+)$NUM$|" +
                "(?:[ 　]+)(?:第$NUM[期]|Season$NUM|Part$NUM).*$",
        Pattern.CASE_INSENSITIVE
    )

    // 4. 検索キーワードを分断するデリミタのパターン（検索用）
    private val SEARCH_DELIMITER_PATTERN = Pattern.compile("(?:\\[|【|［|\\(|（|\\s|　|「|『|第|#|EP|▽|▼|~|〜|-|—)")

    // 5. アニメやドラマなどのジャンル文字列の除去
    private val PREFIX_PATTERN_BRACKETS = Pattern.compile("^(?:映画|アニメ|連続テレビ小説|土曜ドラマ|ドラマ|特別番組|特番|新番組|最終回)(?:「|『)(.*?)(?:」|』)$")
    private val PREFIX_PATTERN_SPACE = Pattern.compile("^(?:映画|アニメ|連続テレビ小説|土曜ドラマ|ドラマ|特別番組|特番|新番組|最終回)[\\s　]+")

    /**
     * UI表示用のタイトルを抽出
     */
    fun extractDisplayTitle(fullTitle: String): String {
        if (fullTitle.isEmpty()) return ""
        var title = Normalizer.normalize(fullTitle, Normalizer.Form.NFKC)

        title = TAGS_PATTERN.matcher(title).replaceAll("")

        val prefixMatcher = PREFIX_PATTERN_BRACKETS.matcher(title.trim())
        if (prefixMatcher.matches()) {
            title = prefixMatcher.group(1) ?: title
        } else {
            title = PREFIX_PATTERN_SPACE.matcher(title.trim()).replaceAll("")
        }

        val episodeMatcher = EPISODE_SUBTITLE_PATTERN.matcher(title)
        if (episodeMatcher.find()) {
            title = title.substring(0, episodeMatcher.start())
        }

        title = title.replace(Regex("^[\\s　・-]+|[\\s　・-]+$"), "")
        return title.trim()
    }

    /**
     * 検索用のキーワードを抽出
     * （途中のタグが抜けることによる連続性の喪失＝検索漏れを防ぐ）
     */
    fun extractSearchKeyword(fullTitle: String): String {
        if (fullTitle.isEmpty()) return ""

        // 1. 先頭の邪魔なタグを除去 (例: "[字]アニメ" -> "アニメ")
        var title = LEADING_TAGS_PATTERN.matcher(fullTitle.trim()).replaceAll("")

        // 2. プレフィックスを除去 (例: "映画「〇〇」" -> "〇〇")
        val prefixMatcher = PREFIX_PATTERN_BRACKETS.matcher(title)
        if (prefixMatcher.matches()) {
            title = prefixMatcher.group(1) ?: title
        } else {
            title = PREFIX_PATTERN_SPACE.matcher(title).replaceAll("")
        }

        // 3. 最初のデリミタ（タグの開始や空白など）が見つかったら、そこまででカット
        // これにより「番組名[字]サブタイ」が「番組名」となり、確実にヒットする
        val delimiterMatcher = SEARCH_DELIMITER_PATTERN.matcher(title)
        if (delimiterMatcher.find()) {
            val cut = title.substring(0, delimiterMatcher.start()).trim()
            if (cut.length > 1) { // カットした結果が極端に短すぎなければ採用
                title = cut
            }
        }

        // 4. 後方の揺れを回避するため、最大30文字程度に丸める
        return if (title.length > 30) title.substring(0, 30).trim() else title.trim()
    }
}