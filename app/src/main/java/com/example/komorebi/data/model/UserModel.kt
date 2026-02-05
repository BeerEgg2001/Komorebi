package com.example.komorebi.data.model

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.komorebi.data.local.entity.WatchHistoryEntity
import java.time.Instant
import java.time.format.DateTimeFormatter

data class KonomiUser(
    val id: Int,
    val name: String,
    val pinned_channel_ids: List<String>,
)

data class KonomiHistoryProgram(
    val program: KonomiProgram,
    val playback_position: Double,
    val last_watched_at: String
) {
    /**
     * APIから取得した履歴をDB保存用エンティティに変換
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun toEntity(): WatchHistoryEntity {
        val programId = this.program.id.toIntOrNull() ?: 0
        val watchedTimestamp = try {
            Instant.parse(this.last_watched_at).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        return WatchHistoryEntity(
            id = programId,
            title = this.program.title,
            description = this.program.description,
            startTime = this.program.start_time,
            endTime = this.program.end_time,
            duration = 0.0, // KonomiProgramの定義に合わせる
            videoId = programId,
            playbackPosition = this.playback_position,
            watchedAt = watchedTimestamp
        )
    }

    /**
     * UIクリック時に再生画面(RecordedProgramを要求する画面)へ遷移するための変換
     */
    fun toRecordedProgram(): RecordedProgram {
        val programId = this.program.id.toIntOrNull() ?: 0
        return RecordedProgram(
            id = programId,
            title = this.program.title,
            description = this.program.description,
            startTime = this.program.start_time,
            endTime = this.program.end_time,
            duration = 0.0,
            isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(
                id = programId,
                filePath = "",
                recordingStartTime = this.program.start_time,
                recordingEndTime = this.program.end_time,
                duration = 0.0,
                containerFormat = "",
                videoCodec = "",
                audioCodec = ""
            )
        )
    }
}

data class KonomiProgram(
    val id: String,
    val title: String,
    val description: String,
    val start_time: String,
    val end_time: String,
    val channel_id: String,
)

data class HistoryUpdateRequest(
    val program_id: String,
    val playback_position: Double
)