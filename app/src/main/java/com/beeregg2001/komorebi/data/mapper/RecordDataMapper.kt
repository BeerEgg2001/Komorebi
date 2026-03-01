package com.beeregg2001.komorebi.data.mapper

import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.model.*

object RecordDataMapper {

    fun toEntity(program: RecordedProgram): RecordedProgramEntity {
        val tile = program.recordedVideo.thumbnailInfo?.tile
        return RecordedProgramEntity(
            id = program.id,
            title = program.title,
            startTime = program.startTime,
            endTime = program.endTime,
            // 録画中の不正確な recordedVideo.duration よりも program.duration を優先して保存する
            videoDuration = if (program.duration > 0) program.duration else program.recordedVideo.duration,
            hasKeyFrames = program.recordedVideo.hasKeyFrames,
            isRecording = program.isRecording,
            playbackPosition = program.playbackPosition,
            channelId = program.channel?.id,
            channelType = program.channel?.type,
            channelName = program.channel?.name,
            genres = program.genres,
            // ★タイル情報をEntityに保存
            tileColumns = tile?.columnCount,
            tileRows = tile?.rowCount,
            tileInterval = tile?.intervalSec,
            tileWidth = tile?.tileWidth,
            tileHeight = tile?.tileHeight
        )
    }

    fun toDomainModel(entity: RecordedProgramEntity): RecordedProgram {
        // ★EntityからTileInfoを復元
        val thumbnailInfo = if (entity.tileColumns != null) {
            ThumbnailInfo(
                version = 1,
                tile = TileInfo(
                    imageWidth = 0, // 未使用のため0
                    imageHeight = 0,
                    tileWidth = entity.tileWidth ?: 320,
                    tileHeight = entity.tileHeight ?: 180,
                    columnCount = entity.tileColumns,
                    rowCount = entity.tileRows ?: 1,
                    intervalSec = entity.tileInterval ?: 10.0,
                    totalTiles = 0
                )
            )
        } else null

        return RecordedProgram(
            id = entity.id,
            title = entity.title,
            description = "",
            detail = null,
            startTime = entity.startTime,
            endTime = entity.endTime,
            duration = entity.videoDuration,
            isPartiallyRecorded = false,
            channel = if (entity.channelId != null) {
                RecordedChannel(
                    id = entity.channelId,
                    displayChannelId = "",
                    type = entity.channelType ?: "",
                    name = entity.channelName ?: "",
                    channelNumber = ""
                )
            } else null,
            recordedVideo = RecordedVideo(
                id = entity.id,
                status = if (entity.isRecording) "Recording" else "Recorded",
                filePath = "",
                recordingStartTime = entity.startTime,
                recordingEndTime = entity.endTime,
                duration = entity.videoDuration,
                containerFormat = "",
                videoCodec = "",
                audioCodec = "",
                hasKeyFrames = entity.hasKeyFrames,
                thumbnailInfo = thumbnailInfo // ★復元した情報をセット
            ),
            genres = entity.genres,
            isRecording = entity.isRecording,
            playbackPosition = entity.playbackPosition
        )
    }
}