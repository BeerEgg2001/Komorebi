package com.beeregg2001.komorebi.data.mapper

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.format.DateTimeFormatter

object KonomiDataMapper {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    @RequiresApi(Build.VERSION_CODES.O)
    fun toEntity(apiHistory: KonomiHistoryProgram): WatchHistoryEntity {
        val programId = apiHistory.program.id.toIntOrNull() ?: 0
        val calcDuration = try {
            val start = Instant.parse(apiHistory.program.start_time).epochSecond
            val end = Instant.parse(apiHistory.program.end_time).epochSecond
            (end - start).toDouble()
        } catch (e: Exception) { 0.0 }

        return WatchHistoryEntity(
            id = programId,
            title = apiHistory.program.title,
            description = apiHistory.program.description,
            detailJson = gson.toJson(apiHistory.program.detail), // ★ 手動変換
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

    fun toEntity(program: RecordedProgram, positionSeconds: Double = 0.0): WatchHistoryEntity {
        val tile = program.recordedVideo.thumbnailInfo?.tile
        return WatchHistoryEntity(
            id = program.id,
            title = program.title,
            description = program.description,
            detailJson = gson.toJson(program.detail), // ★ 手動変換
            startTime = program.startTime,
            endTime = program.endTime,
            duration = program.duration,
            videoId = program.recordedVideo.id,
            playbackPosition = positionSeconds,
            watchedAt = System.currentTimeMillis(),
            tileColumns = tile?.columnCount ?: 1,
            tileRows = tile?.rowCount ?: 1,
            tileInterval = tile?.intervalSec ?: 10.0,
            tileWidth = tile?.tileWidth ?: 320,
            tileHeight = tile?.tileHeight ?: 180
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun toUiModel(entity: WatchHistoryEntity): KonomiHistoryProgram {
        return KonomiHistoryProgram(
            playback_position = entity.playbackPosition,
            last_watched_at = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(entity.watchedAt)),
            program = KonomiProgram(
                id = entity.id.toString(),
                title = entity.title,
                description = entity.description,
                detail = gson.fromJson(entity.detailJson, mapType), // ★ 手動変換
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

    @RequiresApi(Build.VERSION_CODES.O)
    fun toDomainModel(uiHistory: KonomiHistoryProgram): RecordedProgram {
        val programId = uiHistory.program.id.toIntOrNull() ?: 0
        val calcDuration = try {
            val start = Instant.parse(uiHistory.program.start_time).epochSecond
            val end = Instant.parse(uiHistory.program.end_time).epochSecond
            (end - start).toDouble()
        } catch (e: Exception) { 0.0 }

        return RecordedProgram(
            id = programId,
            title = uiHistory.program.title,
            description = uiHistory.program.description,
            detail = uiHistory.program.detail,
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