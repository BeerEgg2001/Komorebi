package com.example.komorebi.data.util

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.komorebi.data.model.EpgProgram
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * BRAVIA風EPGの座標計算とスタイリングを司るユーティリティ
 */
object EpgUtils {

    // --- レイアウト設定 ---
    // 1分あたりの高さ。4K/高精細パネルなら12dp〜15dpが見やすい
    const val DP_PER_MINUTE = 12

    // 基準時刻（番組表の最上部、例えば00:00）からのオフセットを計算
    @RequiresApi(Build.VERSION_CODES.O)
    fun calculateTopOffset(startTime: String, baseTime: OffsetDateTime): Dp {
        val start = OffsetDateTime.parse(startTime)
        // between は Long を返す
        val diffMinutes = ChronoUnit.MINUTES.between(baseTime, start)
        // Int に変換してから計算し、.dp を適用
        return (diffMinutes.toInt() * DP_PER_MINUTE).dp
    }

    // 放送時間（秒）からセルの高さを計算
    fun calculateHeight(durationSeconds: Int): Dp {
        // 1分未満の端数も考慮するためfloatで計算してからdpに変換
        val minutes = durationSeconds / 60f
        return (minutes * DP_PER_MINUTE).dp
    }

    /**
     * ARIB STD-B10 に基づくジャンルカラー
     * BRAVIA風の落ち着いたトーン（彩度を少し落とした色）に調整
     */
    fun getGenreColor(majorGenre: String?): Color {
        return when (majorGenre) {
            "ニュース・報道" -> Color(0xFF1B5E20) // 深い緑
            "スポーツ" -> Color(0xFF0D47A1) // 深い青
            "情報・ワイドショー" -> Color(0xFF006064) // 青緑
            "ドラマ" -> Color(0xFF880E4F) // 深いピンク（エンジ）
            "音楽" -> Color(0xFF4A148C) // 紫
            "バラエティ" -> Color(0xFFE65100) // 濃いオレンジ
            "映画" -> Color(0xFF4E342E) // 茶色
            "アニメ・特撮" -> Color(0xFF311B92) // 濃い紫
            "ドキュメンタリー・教養" -> Color(0xFF33691E) // 草色
            "劇場・公演" -> Color(0xFFBF360C) // 朱色
            "趣味・教育" -> Color(0xFF1B5E20)
            "福祉" -> Color(0xFF263238)
            else -> Color(0xFF2C2C2C) // 該当なし・放送休止等（ダークグレー）
        }
    }

    /**
     * 表示用の時刻フォーマット (HH:mm)
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun formatTime(iso8601: String): String {
        return try {
            val time = OffsetDateTime.parse(iso8601)
            time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            "--:--"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun formatEndTime(program: EpgProgram): String {
        return try {
            val startTime = OffsetDateTime.parse(program.start_time)
            // 秒数を分に変換して加算（durationが秒単位の場合）
            val endTime = startTime.plusSeconds(program.duration.toLong())
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            endTime.format(formatter)
        } catch (e: Exception) {
            ""
        }
    }
}