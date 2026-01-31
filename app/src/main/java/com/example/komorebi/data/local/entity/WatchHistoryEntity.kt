package com.example.komorebi.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.komorebi.data.model.RecordedProgram
import com.example.komorebi.data.model.RecordedVideo

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: Int, // RecordedProgram.id
    val title: String,
    val description: String,
    val startTime: String,
    val endTime: String,
    val duration: Double,
    val videoId: Int,
    val watchedAt: Long = System.currentTimeMillis() // 履歴の並び替え用
)

/**
 * RecordedProgram (APIモデル) から WatchHistoryEntity (DBエンティティ) への変換
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
 * WatchHistoryEntity (DBエンティティ) から RecordedProgram (APIモデル) への変換
 * UIコンポーネント（RecordedCardなど）で再利用するために使用します。
 */
fun WatchHistoryEntity.toRecordedProgram(): RecordedProgram {
    return RecordedProgram(
        id = this.id,
        title = this.title,
        description = this.description,
        startTime = this.startTime,
        endTime = this.endTime,
        duration = this.duration,
        // RecordedVideoオブジェクトをDB保存時のIDを用いて再構成
        recordedVideo = RecordedVideo(
            id = this.videoId,
            filePath = "", // 履歴表示には不要なため空文字
            recordingStartTime = this.startTime, // 番組の開始時間を流用
            recordingEndTime = this.endTime,     // 番組の終了時間を流用
            duration = this.duration,
            containerFormat = "",
            videoCodec = "",
            audioCodec = ""
        ),
        isPartiallyRecorded = false
    )
}