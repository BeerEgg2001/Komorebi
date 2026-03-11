package com.beeregg2001.komorebi.data.mapper

import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.util.TitleNormalizer

object RecordDataMapper {

    fun toEntity(program: RecordedProgram): RecordedProgramEntity {
        val tile = program.recordedVideo.thumbnailInfo?.tile

        // API取得直後、未成形の場合の仮設定 (Null安全対応)
        val majorGenre = program.genres?.firstOrNull()?.major ?: ""
        val isEpisodic = program.isEpisodic == true ||
                majorGenre == "アニメ・特撮" ||
                majorGenre == "ドラマ" ||
                TitleNormalizer.hasEpisodeNumber(program.title)

        val seriesName = if (!program.seriesName.isNullOrBlank()) program.seriesName!!
        else TitleNormalizer.extractDisplayTitle(program.title)

        // ★修正: DBに保存する前に、KonomiTVの2種類の録画中ステータスを確実にマージする
        val isCurrentlyRecording =
            program.isRecording || program.recordedVideo.status == "Recording"

        return RecordedProgramEntity(
            id = program.id,
            title = program.title,
            seriesName = seriesName,
            isEpisodic = isEpisodic,
            startTime = program.startTime,
            endTime = program.endTime,
            videoDuration = if (program.recordedVideo.duration > 0) program.recordedVideo.duration else program.duration,
            // ★descriptionの保存を削除
            hasKeyFrames = program.recordedVideo.hasKeyFrames,
            isRecording = isCurrentlyRecording, // ★修正: マージした確実なフラグをDBに保存
            playbackPosition = program.playbackPosition,
            channelId = program.channel?.id,
            channelType = program.channel?.type,
            channelName = program.channel?.name,
            genres = program.genres,
            tileColumns = tile?.columnCount,
            tileRows = tile?.rowCount,
            tileInterval = tile?.intervalSec,
            tileWidth = tile?.tileWidth,
            tileHeight = tile?.tileHeight
        )
    }

    fun toDomainModel(entity: RecordedProgramEntity): RecordedProgram {
        val thumbnailInfo = if (entity.tileColumns != null) {
            ThumbnailInfo(
                version = 1,
                tile = TileInfo(
                    imageWidth = 0, imageHeight = 0,
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
            seriesName = entity.seriesName,
            isEpisodic = entity.isEpisodic,
            description = "", // ★DBからは取得せず空文字をセット（詳細画面を開いた時にAPIから再取得されます）
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
                containerFormat = "", videoCodec = "", audioCodec = "",
                hasKeyFrames = entity.hasKeyFrames,
                thumbnailInfo = thumbnailInfo
            ),
            genres = entity.genres,
            isRecording = entity.isRecording,
            playbackPosition = entity.playbackPosition
        )
    }
}