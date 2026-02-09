package com.beeregg2001.komorebi.data.local.entity

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.KonomiProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import java.time.format.DateTimeFormatter
import java.time.Instant

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val description: String,
    val startTime: String,
    val endTime: String,
    val duration: Double,
    val videoId: Int,
    val playbackPosition: Double = 0.0,
    val watchedAt: Long = System.currentTimeMillis()
)

/**
 * ローカルで再生した RecordedProgram を DB に保存する際の変換
 */
fun RecordedProgram.toEntity(): WatchHistoryEntity {
    return WatchHistoryEntity(
        id = this.id,
        title = this.title,
        description = this.description,
        startTime = this.startTime,
        endTime = this.endTime,
        duration = this.duration,
        videoId = this.recordedVideo.id,
        watchedAt = System.currentTimeMillis()
    )
}

/**
 * DBから読み出したデータをUI（HomeContents）に表示するための変換
 */
@RequiresApi(Build.VERSION_CODES.O)
fun WatchHistoryEntity.toKonomiHistoryProgram(): KonomiHistoryProgram {
    return KonomiHistoryProgram(
        playback_position = this.playbackPosition,
        last_watched_at = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(this.watchedAt)),
        program = KonomiProgram(
            id = this.videoId.toString(),
            title = this.title,
            description = this.description,
            start_time = this.startTime,
            end_time = this.endTime,
            channel_id = ""
        )
    )
}

/**
 * DBから読み出したデータを再生画面に直接渡す際の変換
 */
fun WatchHistoryEntity.toRecordedProgram(): RecordedProgram {
    return RecordedProgram(
        id = this.id,
        title = this.title,
        description = this.description,
        startTime = this.startTime,
        endTime = this.endTime,
        duration = this.duration,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = this.videoId,
            filePath = "",
            recordingStartTime = this.startTime,
            recordingEndTime = this.endTime,
            duration = this.duration,
            containerFormat = "",
            videoCodec = "",
            audioCodec = ""
        )
    )
}