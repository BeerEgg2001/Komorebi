package com.beeregg2001.komorebi.util

import com.beeregg2001.komorebi.data.model.CmSection
import com.beeregg2001.komorebi.ui.video.player.ChapterInfo

/**
 * 外部チャプターファイルの読み込みとパースをまとめたもの。
 *
 * 録画リストからの再生（EDCB の resolver.lua 経由）と SMB 再生の両方から使う。
 * 以前は SMB 側だけがチャプターファイルを直接パースして全チャプターを取得し、録画リスト側は
 * CM 区間（`ix` / `ox` のペア）だけを抜き出して、そこからチャプターを逆算していた。
 * そのため録画リストからの再生では**本編中の名前付きチャプターが失われていた**（Issue #79）。
 *
 * 文字コードは環境によって Shift_JIS と UTF-8 のどちらもあり得る（バッチ生成では Shift_JIS、
 * TvtPlay で作成・編集すると UTF-8 になる）。加えて UTF-8 は BOM の有無も分かれる。
 * [decode] と [parse] の両方で BOM を取り除き、経路や生成ツールによらず読めるようにしている。
 */
object ChapterParser {

    /**
     * チャプターファイルのバイト列を文字列へ変換する。
     *
     * BOM があればそれに従い、無ければ UTF-8 として解釈する。UTF-8 として不正なバイト列は
     * 例外ではなく U+FFFD（置換文字）へ静かに置き換えられてしまうため、**置換文字が出たかどうか**で
     * UTF-8 として妥当だったかを判定し、妥当でなければ Shift_JIS として読み直す。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8
        return runCatching { String(bytes, charset("Shift_JIS")) }.getOrDefault(utf8)
    }

    /**
     * チャプターテキストの形式を内容から判定し、チャプター一覧を返す。
     *
     * 形式の判定にファイル拡張子を使わないのは、`.chapter` に ini 形式が入っていたり
     * `.txt` に Lua 形式が入っていたりする運用があり得るため。
     *
     * @param durationSec 動画の長さ（秒）。0 以下なら最後のマーカー + 30 秒を終端として扱う。
     */
    fun parse(text: String, durationSec: Double): List<ChapterInfo> {
        // BOM 付き UTF-8 を素直に読むと先頭に U+FEFF が残り、Lua 形式の判定
        // （"c-" で始まるか）が成立しなくなるため、ここでも取り除く。
        val normalized = text.removePrefix("﻿").trim()
        if (normalized.isEmpty()) return emptyList()
        return when {
            normalized.contains("CHAPTER", ignoreCase = true) && normalized.contains('=') ->
                parseIniFormat(normalized, durationSec)

            normalized.startsWith("c-") -> parseLuaFormat(normalized, durationSec)
            else -> emptyList()
        }
    }

    /**
     * チャプター一覧から CM 区間を導出する。CM 自動スキップ用。
     *
     * 隣接・重複する CM 区間はひとつにまとめる。ini 形式では CM マーカーが連続して並ぶことがあり、
     * 区間を分けたままにするとスキップが小刻みに繰り返されてしまうため。
     */
    fun toCmSections(chapters: List<ChapterInfo>): List<CmSection> {
        val cmRanges = chapters
            .filter { it.isCm && !it.isMarkerOnly }
            .map { it.startTimeMs / 1000.0 to it.endTimeMs / 1000.0 }
            .filter { (start, end) -> end > start }
            .sortedBy { it.first }

        val merged = mutableListOf<CmSection>()
        for ((start, end) in cmRanges) {
            val last = merged.lastOrNull()
            if (last != null && start <= last.endTime + 0.001) {
                merged[merged.lastIndex] = CmSection(last.startTime, maxOf(last.endTime, end))
            } else {
                merged.add(CmSection(start, end))
            }
        }
        return merged
    }

    /**
     * TvtPlay 等で使われる Lua 形式（`c-{位置}{c|d|e}{名前}-…-c`）をパースする。
     *
     * 仕様を満たさない場合は部分的に読まず全体を捨てる。中途半端に解釈すると、
     * 実際とずれた位置のチャプターが出てしまい、かえって混乱するため。
     */
    private fun parseLuaFormat(text: String, durationSec: Double): List<ChapterInfo> {
        val trimmed = text.trim()

        // 仕様: "c-" で始めて "c" で終わる
        if (!trimmed.startsWith("c-") || !trimmed.endsWith("c")) return emptyList()

        // 先頭の "c-" と末尾の "c" を取り除く ("c-c" の場合は coreContent が空になる)
        val coreContent = trimmed.substring(2, trimmed.length - 1)
        if (coreContent.isEmpty()) return emptyList()

        // 仕様を満たさないコマンドは全体を無視するための事前バリデーション
        // パターン: {正整数}{c|d|e}{文字列}- の連続であること
        if (!coreContent.matches(Regex("^(?:\\d+[cde][^-]*-)+$"))) return emptyList()

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
                // 直前の終了位置から今回の CM 開始位置までにギャップがあれば「本編」として追加
                if (lastChapterEndMs < timeMs) {
                    chapters.add(
                        ChapterInfo(
                            startTimeMs = lastChapterEndMs,
                            endTimeMs = timeMs,
                            isCm = false,
                            isMarkerOnly = false,
                            label = ""
                        )
                    )
                }
                currentCmStartMs = timeMs
            } else if (isCmEnd && currentCmStartMs != null) {
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

            // ix でも ox でもない通常のマーカー（C5Sec など）
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

        // 終端処理 (CM が閉じられずに終わった場合)
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

        // 最後のマーカーから終端までの本編区間を補完する
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

    /**
     * Matroska/OGM 系の ini 形式（`CHAPTER01=00:00:00.000` / `CHAPTER01NAME=…`）をパースする。
     *
     * 時刻の「時」は 1 桁で書かれることもあるため `\d{1,2}` で受ける。
     */
    private fun parseIniFormat(text: String, durationSec: Double): List<ChapterInfo> {
        val rawMarkers = mutableListOf<Pair<Long, String>>()
        val lines = text.split("\n")
        var currentStartMs = -1L

        val timeRegex = Regex("""CHAPTER\d+=(\d{1,2}):(\d{2}):(\d{2})\.(\d{3})""")
        val nameRegex = Regex("""CHAPTER\d+NAME=(.*)""")

        for (line in lines) {
            timeRegex.find(line)?.let { tMatch ->
                val h = tMatch.groupValues[1].toLong()
                val m = tMatch.groupValues[2].toLong()
                val s = tMatch.groupValues[3].toLong()
                val ms = tMatch.groupValues[4].toLong()
                currentStartMs = (h * 3600000) + (m * 60000) + (s * 1000) + ms
            }

            val nMatch = nameRegex.find(line)
            if (nMatch != null && currentStartMs >= 0L) {
                rawMarkers.add(Pair(currentStartMs, nMatch.groupValues[1].trim()))
                currentStartMs = -1L
            }
        }

        // 名前行が無く時刻行だけのファイルでも、位置だけは活かせるようにする。
        if (rawMarkers.isEmpty() && currentStartMs >= 0L) {
            rawMarkers.add(Pair(currentStartMs, ""))
        }
        if (rawMarkers.isEmpty()) return emptyList()

        val lastTimeMs = rawMarkers.last().first
        val safeDurationMs =
            if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
        val chapters = mutableListOf<ChapterInfo>()

        for (i in rawMarkers.indices) {
            val (timeMs, name) = rawMarkers[i]
            val nextTimeMs =
                if (i + 1 < rawMarkers.size) rawMarkers[i + 1].first else safeDurationMs
            val isCm = name.contains("CM", ignoreCase = true) ||
                name.contains("Sponsor", ignoreCase = true)

            if (isCm) {
                chapters.add(
                    ChapterInfo(timeMs, nextTimeMs, isCm = true, isMarkerOnly = false, label = "")
                )
            }
            chapters.add(
                ChapterInfo(timeMs, nextTimeMs, isCm = false, isMarkerOnly = true, label = name)
            )
        }

        return chapters.sortedBy { it.startTimeMs }
    }
}
