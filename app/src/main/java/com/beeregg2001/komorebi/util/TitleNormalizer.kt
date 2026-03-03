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

    // 放送枠名のプレフィックスを強制除去
    private val GENRE_PREFIX_PATTERN = Pattern.compile(
        "^(?:【|\\[|［|\\(|（)(?:連続テレビ小説|土曜ドラマ|よるドラ|夜ドラ|ドラマ|アニメ|映画|特番|特別番組|新番組|最終回)(?:】|\\]|］|\\)|）)\\s*"
    )

    private val LEADING_TAGS_PATTERN = Pattern.compile("^(?:\\[.*?\\]|【.*?】|［.*?］|\\(.*?\\)|（.*?）)+")
    private const val NUM = "[ 0-9０-９一二三四五六七八九十百千万零]+"

    // ★修正: 暴発の元だった「!」「?」「空白」で切るルールを全廃止。
    // 「★」などの明らかな装飾記号の直前でだけ切る、安全なルールに変更。
    private val VARIETY_BOUNDARY_PATTERN = Pattern.compile(
        "(?<=[^A-Z0-9a-z])(?=[★☆◆◇■□●○▽▼])"
    )

    private val EPISODE_SUBTITLE_PATTERN = Pattern.compile(
        "(?:[ 　]+)(?:第$NUM[話回幕]?|$NUM[話回幕]|#+$NUM|\\($NUM\\)|（$NUM）|EP\\.?$NUM).*$|\\($NUM\\)|" +
                "(?:[ 　]+)(?:「|『|【|＜|〈|~|〜|—|-|▽|▼).*$|" +
                "(?:第$NUM[話回幕]?|$NUM[話回幕]|#+$NUM|\\($NUM\\)|（$NUM）|EP\\.?$NUM)$|第$NUM[部]|#$NUM|" +
                "(?:[ 　]+)$NUM$|" +
                "(?:[ 　]+)(?:第$NUM[期]|Season$NUM|Part$NUM).*$",
        Pattern.CASE_INSENSITIVE
    )

    // ★修正: 検索キーワードの区切り文字から「\\s」や「　」（空白）を削除。「Sound Shower」が生き残るように。
    private val SEARCH_DELIMITER_PATTERN = Pattern.compile("(?:\\[|【|［|\\(|（|「|『|第|#|EP|▽|▼|~|〜|-|—)")

    private val PREFIX_PATTERN_BRACKETS = Pattern.compile("^(?:映画|アニメ|連続テレビ小説|土曜ドラマ|ドラマ|特別番組|特番|新番組|最終回)(?:「|『)(.*?)(?:」|』)$")
    private val PREFIX_PATTERN_SPACE = Pattern.compile("^(?:映画|アニメ|連続テレビ小説|土曜ドラマ|ドラマ|特別番組|特番|新番組|最終回)[\\s　]+")

    fun extractDisplayTitle(fullTitle: String): String {
        if (fullTitle.isEmpty()) return ""
        var title = Normalizer.normalize(fullTitle, Normalizer.Form.NFKC)
        title = TAGS_PATTERN.matcher(title).replaceAll("")

        // 映画枠などは作品名ではなく枠名そのものをシリーズ名とする
        val movieBlockMatcher = Pattern.compile("^(金曜ロードショー|金曜ロードSHOW!|土曜プレミアム|日曜洋画劇場|月曜プレミア8|水曜エンタ|木曜洋画劇場)").matcher(title.trim())
        if (movieBlockMatcher.find()) {
            return movieBlockMatcher.group(1) ?: title
        }

        title = GENRE_PREFIX_PATTERN.matcher(title.trim()).replaceAll("")

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

        val boundaryMatcher = VARIETY_BOUNDARY_PATTERN.matcher(title)
        if (boundaryMatcher.find()) {
            val cutIndex = boundaryMatcher.start()
            if (cutIndex >= 4) {
                title = title.substring(0, cutIndex)
            }
        }

        // ★修正: 末尾に残る「。」や「、」などの句読点を削除（放送局ごとの表記揺れを吸収）
        return title.replace(Regex("^[\\s　・\\-]+|[\\s　・。、\\-]+$"), "").trim()
    }

    fun getGroupingKey(fullTitle: String): String {
        val title = extractDisplayTitle(fullTitle)
        return title
            .let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            .uppercase()
            .replace(Regex("[^A-Z0-9\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF]"), "")
    }

    fun extractSearchKeyword(fullTitle: String): String {
        if (fullTitle.isEmpty()) return ""
        var title = LEADING_TAGS_PATTERN.matcher(fullTitle.trim()).replaceAll("")
        title = GENRE_PREFIX_PATTERN.matcher(title).replaceAll("")

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

        // ★修正: 検索キーワードでも末尾の「。」を消す
        title = title.replace(Regex("^[\\s　・\\-]+|[\\s　・。、\\-]+$"), "").trim()

        return if (title.length > 30) title.substring(0, 30).trim() else title.trim()
    }
}