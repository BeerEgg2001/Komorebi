package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.model.EpgProgram
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 番組表データのバリデーションと構造変換を担当する
 */
@RequiresApi(Build.VERSION_CODES.O)
object EpgDataConverter {

    // 日時パースの保護
    fun safeParseTime(timeStr: String?, fallback: OffsetDateTime): OffsetDateTime {
        if (timeStr == null) return fallback
        return try {
            OffsetDateTime.parse(timeStr)
        } catch (e: DateTimeParseException) {
            fallback
        }
    }

    // 番組同士のオフセット計算（Canvas用）
    fun calculateSafeOffsets(p: EpgProgram, base: OffsetDateTime): Pair<Float, Float> {
        val s = safeParseTime(p.start_time, base)
        val e = safeParseTime(p.end_time, s.plusMinutes(30)) // 最低30分確保

        val startMin = Duration.between(base, s).toMinutes().toFloat()
        val durationMin = Duration.between(s, e).toMinutes().toFloat()
        return startMin to durationMin
    }

    // チャンネルごとの番組リストをバリデーションしつつ穴埋め
    fun getFilledPrograms(
        channelId: String,
        programs: List<EpgProgram>,
        baseTime: OffsetDateTime,
        limitTime: OffsetDateTime
    ): List<EpgProgram> {
        // 1. 不正な時間データの除外とソート
        val validSorted = programs.filter {
            val s = safeParseTime(it.start_time, OffsetDateTime.MIN)
            val e = safeParseTime(it.end_time, OffsetDateTime.MIN)
            s != OffsetDateTime.MIN && e != OffsetDateTime.MIN && e.isAfter(s)
        }.sortedBy { it.start_time }

        val filled = mutableListOf<EpgProgram>()
        var lastTrackTime = baseTime

        // 2. 隙間を「番組情報なし」で埋める
        validSorted.forEach { p ->
            val pStart = safeParseTime(p.start_time, lastTrackTime)
            if (pStart.isAfter(lastTrackTime)) {
                filled.add(createEmptyProgram(channelId, lastTrackTime, pStart))
            }
            filled.add(p)
            lastTrackTime = safeParseTime(p.end_time, pStart.plusMinutes(1))
        }

        // 3. 末尾の埋め合わせ
        if (lastTrackTime.isBefore(limitTime)) {
            filled.add(createEmptyProgram(channelId, lastTrackTime, limitTime))
        }

        return filled
    }

    private fun createEmptyProgram(cid: String, s: OffsetDateTime, e: OffsetDateTime) = EpgProgram(
        id = "empty_${cid}_${s.toEpochSecond()}",
        channel_id = cid,
        title = "（番組情報なし）",
        description = "",
        start_time = s.toString(),
        end_time = e.toString(),
        // ... 他のフィールドはデフォルト値
        network_id = 0, service_id = 0, event_id = 0, extended = null, detail = null,
        genres = null, duration = 0, is_free = true, video_type = "", audio_type = "", audio_sampling_rate = ""
    )
}