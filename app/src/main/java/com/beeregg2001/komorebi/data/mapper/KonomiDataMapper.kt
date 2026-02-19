package com.beeregg2001.komorebi.data.mapper

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.*
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val TAG = "Komorebi_Debug_Mapper"

object KonomiDataMapper {

    /**
     * APIから取得した履歴（KonomiHistoryProgram）をデータベース保存用（WatchHistoryEntity）に変換します。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun toEntity(apiHistory: KonomiHistoryProgram): WatchHistoryEntity {
        val programId = apiHistory.program.id.toIntOrNull() ?: 0
        val calcDuration = try {
            val start = Instant.parse(apiHistory.program.start_time).epochSecond
            val end = Instant.parse(apiHistory.program.end_time).epochSecond
            (end - start).toDouble()
        } catch (e: Exception) { 0.0 }

        // API履歴にはタイル情報が含まれないため、ここでのログは「引継ぎ前」の状態確認用
        Log.i(TAG, "toEntity(API->DB): ID=$programId, Title=${apiHistory.program.title}, ExistingTiles=${apiHistory.tileColumns}x${apiHistory.tileRows}")

        return WatchHistoryEntity(
            id = programId,
            title = apiHistory.program.title,
            description = apiHistory.program.description,
            startTime = apiHistory.program.start_time,
            endTime = apiHistory.program.end_time,
            duration = calcDuration,
            videoId = apiHistory.videoId ?: programId,
            playbackPosition = apiHistory.playback_position,
            watchedAt = try { Instant.parse(apiHistory.last_watched_at).toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() },
            tileColumns = apiHistory.tileColumns,
            tileRows = apiHistory.tileRows,
            tileInterval = apiHistory.tileInterval,
            tileWidth = apiHistory.tileWidth,
            tileHeight = apiHistory.tileHeight
        )
    }

    /**
     * 再生中の番組（RecordedProgram）を、現在の再生位置を含めてデータベース保存用（WatchHistoryEntity）に変換します。
     */
    fun toEntity(program: RecordedProgram, positionSeconds: Double = 0.0): WatchHistoryEntity {
        val tile = program.recordedVideo.thumbnailInfo?.tile

        // ★重要ログ: ここで tile が null だと、DBにデフォルト値(1x1)が保存されてしまう
        if (tile == null) {
            Log.w(TAG, "toEntity(Domain->DB): WARNING! TileInfo is NULL for ID=${program.id}. Saving defaults.")
        } else {
            Log.i(TAG, "toEntity(Domain->DB): ID=${program.id}, Saving Tile: ${tile.columnCount}x${tile.rowCount}, Interval=${tile.intervalSec}")
        }

        return WatchHistoryEntity(
            id = program.id,
            title = program.title,
            description = program.description,
            startTime = program.startTime,
            endTime = program.endTime,
            duration = program.duration,
            videoId = program.recordedVideo.id,
            playbackPosition = positionSeconds,
            watchedAt = System.currentTimeMillis(),
            // 再生時に保持していたタイル情報を確実にDBへ保存
            tileColumns = tile?.columnCount ?: 1,
            tileRows = tile?.rowCount ?: 1,
            tileInterval = tile?.intervalSec ?: 10.0,
            tileWidth = tile?.tileWidth ?: 320,
            tileHeight = tile?.tileHeight ?: 180
        )
    }

    /**
     * データベースのエンティティを、UI表示用のAPI互換モデル（KonomiHistoryProgram）に変換します。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun toUiModel(entity: WatchHistoryEntity): KonomiHistoryProgram {
        return KonomiHistoryProgram(
            playback_position = entity.playbackPosition,
            last_watched_at = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(entity.watchedAt)),
            program = KonomiProgram(
                id = entity.id.toString(),
                title = entity.title,
                description = entity.description,
                start_time = entity.startTime,
                end_time = entity.endTime,
                channel_id = ""
            ),
            videoId = entity.videoId,
            tileColumns = entity.tileColumns,
            tileRows = entity.tileRows,
            tileInterval = entity.tileInterval,
            tileWidth = entity.tileWidth,
            tileHeight = entity.tileHeight
        )
    }

    /**
     * UI向けの履歴データを、再生画面が必要とするドメインモデル（RecordedProgram）に変換します。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun toDomainModel(uiHistory: KonomiHistoryProgram): RecordedProgram {
        val programId = uiHistory.program.id.toIntOrNull() ?: 0
        val calcDuration = try {
            val start = java.time.Instant.parse(uiHistory.program.start_time).epochSecond
            val end = java.time.Instant.parse(uiHistory.program.end_time).epochSecond
            (end - start).toDouble()
        } catch (e: Exception) { 0.0 }

        Log.i(TAG, "toDomainModel(UI->Domain): Restoring Tile info for ID=$programId. Cols=${uiHistory.tileColumns}, Rows=${uiHistory.tileRows}")

        return RecordedProgram(
            id = programId,
            title = uiHistory.program.title,
            description = uiHistory.program.description,
            startTime = uiHistory.program.start_time,
            endTime = uiHistory.program.end_time,
            duration = calcDuration,
            isPartiallyRecorded = false,
            playbackPosition = uiHistory.playback_position,
            recordedVideo = RecordedVideo(
                id = uiHistory.videoId ?: programId,
                status = "Recorded",
                filePath = "",
                recordingStartTime = uiHistory.program.start_time,
                recordingEndTime = uiHistory.program.end_time,
                duration = calcDuration,
                containerFormat = "",
                videoCodec = "",
                audioCodec = "",
                hasKeyFrames = true,
                // 保存されていたタイル情報を復元してメタデータとしてセット
                thumbnailInfo = ThumbnailInfo(
                    version = 1,
                    tile = TileInfo(
                        imageWidth = uiHistory.tileWidth * uiHistory.tileColumns,
                        imageHeight = uiHistory.tileHeight * uiHistory.tileRows,
                        tileWidth = uiHistory.tileWidth,
                        tileHeight = uiHistory.tileHeight,
                        columnCount = uiHistory.tileColumns,
                        rowCount = uiHistory.tileRows,
                        intervalSec = uiHistory.tileInterval,
                        totalTiles = uiHistory.tileColumns * uiHistory.tileRows
                    )
                )
            )
        )
    }
}