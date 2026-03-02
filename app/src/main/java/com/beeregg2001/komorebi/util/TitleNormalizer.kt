package com.beeregg2001.komorebi.util

import java.text.Normalizer
import java.util.regex.Pattern

object TitleNormalizer {
    private val TAGS_PATTERN = Pattern.compile(
        "\\[(字|二|デ|解|多|S|新|終|再|無|SS|HV|P|W|手|初|生|N|H|複|双|別|カ|英|韓|中|天|擬|吹|撮|録|問|画|前|後|編|回|全|話)\\]|" +
                "\\((字|二|デ|解|多|S|新|終|再|無|SS|HV|P|W|手|初|生|N|H|複|双|別|カ|英|韓|中|天|擬|吹|撮|録|問|画|前|後|編|回|全|話)\\)|" +
                "（(字|二|デ|解|多|S|新|終|再|無|SS|HV|P|W|手|初|生|N|H|複|双|別|カ|英|韓|中|天|擬|吹|撮|録|問|画|前|後|編|回|全|話)）|" +
                "【(新|終|再|初|字|二|デ|解|無料|生|録)】|" +
                "［(新|終|再|初|字|二|デ|解|無料|生|録)］"
    )

    private val LEADING_TAGS_PATTERN = Pattern.compile("^(?:\\[.*?\\]|【.*?】|［.*?］|\\(.*?\\)|（.*?）)+")
    private const val NUM = "[ 0-9０-９一二三四五六七八九十百千万零]+"

    // バラエティ番組で「番組名」と「内容」を分ける可能性が高い境界線
    private val VARIETY_BOUNDARY_PATTERN = Pattern.compile(
        "(?<=[^A-Z0-9])([★☆◆◇■□●○▽▼!！?？])|" + // 記号による境界
                "(?<=[！!？?])|" +                           // 強い句読点の直後
                "(?:[ 　]+)(?=.)"                             // 空白の直後
    )

    private val EPISODE_SUBTITLE_PATTERN = Pattern.compile(
        "(?:[ 　]+)(?:第$NUM[話回幕]?|$NUM[話回幕]|#+$NUM|\\($NUM\\)|（$NUM）|EP\\.?$NUM).*$|\\($NUM\\)|" +
                "(?:[ 　]+)(?:「|『|【|＜|〈|~|〜|—|-|▽|▼).*$|" +
                "(?:第$NUM[話回幕]?|$NUM[話回幕]|#+$NUM|\\($NUM\\)|（$NUM）|EP\\.?$NUM)$|第$NUM[部]|#$NUM|" +
                "(?:[ 　]+)$NUM$|" +
                "(?:[ 　]+)(?:第$NUM[期]|Season$NUM|Part$NUM).*$",
        Pattern.CASE_INSENSITIVE
    )

    private val SEARCH_DELIMITER_PATTERN = Pattern.compile("(?:\\[|【|［|\\(|（|\\s|　|「|『|第|#|EP|▽|▼|~|〜|-|—)")
    private val PREFIX_PATTERN_BRACKETS = Pattern.compile("^(?:映画|アニメ|連続テレビ小説|土曜ドラマ|ドラマ|特別番組|特番|新番組|最終回)(?:「|『)(.*?)(?:」|』)$")
    private val PREFIX_PATTERN_SPACE = Pattern.compile("^(?:映画|アニメ|連続テレビ小説|土曜ドラマ|ドラマ|特別番組|特番|新番組|最終回)[\\s　]+")

    /**
     * UI表示用のタイトルを抽出（単発録画でもバラエティを切り分ける強化版）
     */
    fun extractDisplayTitle(fullTitle: String): String {
        if (fullTitle.isEmpty()) return ""
        var title = Normalizer.normalize(fullTitle, Normalizer.Form.NFKC)
        title = TAGS_PATTERN.matcher(title).replaceAll("")

        // プリフィックス除去
        val prefixMatcher = PREFIX_PATTERN_BRACKETS.matcher(title.trim())
        if (prefixMatcher.matches()) {
            title = prefixMatcher.group(1) ?: title
        } else {
            title = PREFIX_PATTERN_SPACE.matcher(title.trim()).replaceAll("")
        }

        // 1. まず標準的な話数パターンでカット
        val episodeMatcher = EPISODE_SUBTITLE_PATTERN.matcher(title)
        if (episodeMatcher.find()) {
            title = title.substring(0, episodeMatcher.start())
        }

        // 2. バラエティ特有の記号境界でのカット（単発録画対策）
        // 番組名としての長さを考慮し、極端に短くならない範囲でカット
        val boundaryMatcher = VARIETY_BOUNDARY_PATTERN.matcher(title)
        if (boundaryMatcher.find()) {
            val cutIndex = if (boundaryMatcher.group(1) != null) boundaryMatcher.start() else boundaryMatcher.end()
            // 4文字以上残るならカットを採用（短すぎると「Qさま」などが削れすぎるため）
            if (cutIndex >= 4) {
                title = title.substring(0, cutIndex)
            }
        }

        return title.replace(Regex("^[\\s　・-]+|[\\s　・-]+$"), "").trim()
    }

    /**
     * 自動正規化キー
     */
    fun getGroupingKey(fullTitle: String): String {
        val title = extractDisplayTitle(fullTitle)
        return title
            .let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            .uppercase()
            .replace(Regex("[^A-Z0-9\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF]"), "")
    }

    /**
     * 検索キーワード抽出
     */
    fun extractSearchKeyword(fullTitle: String): String {
        if (fullTitle.isEmpty()) return ""
        var title = LEADING_TAGS_PATTERN.matcher(fullTitle.trim()).replaceAll("")
        val prefixMatcher = PREFIX_PATTERN_BRACKETS.matcher(title)
        if (prefixMatcher.matches()) {
            title = prefixMatcher.group(1) ?: title
        } else {
            title = PREFIX_PATTERN_SPACE.matcher(title).replaceAll("")
        }
        val delimiterMatcher = SEARCH_DELIMITER_PATTERN.matcher(title)
        if (delimiterMatcher.find()) {
            val cut = title.substring(0, delimiterMatcher.start()).trim()
            if (cut.length > 1) title = cut
        }
        return if (title.length > 30) title.substring(0, 30).trim() else title.trim()
    }
}