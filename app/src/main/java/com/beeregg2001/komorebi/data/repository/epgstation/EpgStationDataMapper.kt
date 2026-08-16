package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.api.edcb.EdcbConstants
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.util.TitleNormalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** EPGStation の DTO を Komorebi 共通モデルへ変換するマッパー。 */
object EpgStationDataMapper {
    /** 番組表取得に必要な放送波フラグを、指定された種別に応じて組み立てる。 */
    fun buildBroadcastFlags(channelType: String? = null): Map<String, Boolean> {
        val type = channelType?.uppercase()
        val broadcastTypes = listOf("GR", "BS", "CS", "SKY", "BS4K", "CS4K") +
            (1..40).map { "NW$it" }
        return broadcastTypes.associateWith { broadcastType ->
            type == null || when {
                type == broadcastType -> true
                type.startsWith("NW") && broadcastType == type -> true
                else -> false
            }
        }
    }

    /** EPGStation の数値 ID をアプリ内で衝突しない文字列 ID に変換する。 */
    fun buildChannelId(id: Long): String = "epgstation_$id"

    /** アプリ内チャンネル ID から EPGStation の数値 ID を取り出す。 */
    fun parseChannelId(channelId: String): Long? = channelId.removePrefix("epgstation_").toLongOrNull()

    /** UnixtimeMS を端末のタイムゾーン付き ISO8601 へ変換する。 */
    fun toIso8601(unixtimeMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(unixtimeMs).atZone(ZoneId.systemDefault())
        )

    /** ISO8601 を UnixtimeMS へ変換し、変換できない場合は 0 を返す。 */
    fun fromIso8601(iso: String): Long {
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    /** EPGStation のジャンル番号を EDCB 共通のジャンル名へ変換する。 */
    fun mapGenres(vararg values: Int?): List<EpgGenre> {
        return values.toList().chunked(2).mapNotNull { pair ->
            val major = pair.getOrNull(0) ?: return@mapNotNull null
            val contentType = EdcbConstants.CONTENT_TYPE[major] ?: return@mapNotNull null
            EpgGenre(
                major = contentType.first,
                middle = contentType.second[pair.getOrNull(1)] ?: "未定義"
            )
        }
    }

    /** extended または rawExtended を共通の見出し付き詳細へ変換する。 */
    private fun mapDetail(extended: String?, rawExtended: Map<String, String>?): Map<String, String>? {
        if (!rawExtended.isNullOrEmpty()) {
            return rawExtended
        }
        if (extended.isNullOrBlank()) {
            return null
        }
        val detail = linkedMapOf<String, String>()
        stripDropLog(extended).lineSequence().forEach { line ->
            val parts = line.split(Regex("[：:]"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) {
                detail[parts[0].trim()] = parts[1].trim()
            }
        }
        return if (detail.isEmpty()) mapOf("番組内容" to extended) else detail
    }

    /**
     * 録画の extended 末尾には ffmpeg/録画時に付与された
     * 「PID: 0x0000 Total: ... Drop: ...」形式のドロップ統計が連結されていることがある。
     * そのまま見出しとして解釈するとゴミだらけになるため、統計の開始位置から後ろを捨てる。
     */
    private fun stripDropLog(extended: String): String {
        val index = extended.indexOf("PID: 0x")
        return if (index >= 0) extended.substring(0, index).trimEnd() else extended
    }

    /**
     * 地上波かどうかを判定する。
     * EPGStation はチューナーのネットワーク単位で GR を NW1〜NW40 に分けて返すことがあり、
     * 他地域の地上波が NW* として現れる。表示上はどちらも地上波として扱う。
     */
    fun isTerrestrial(channelType: String): Boolean =
        channelType == "GR" || channelType.startsWith("NW")

    /**
     * EPGStation の放送波種別を、アプリ共通モデルが扱う種別へ寄せる。
     * 他地域の地上波 (NW*) は "GR"、CS4K は受け皿がないため "BS4K" として扱う。
     */
    fun normalizeChannelType(channelType: String): String = when {
        isTerrestrial(channelType) -> "GR"
        channelType == "CS4K" -> "BS4K"
        else -> channelType
    }

    /** EDCB と同じ規則でチャンネル番号を算出する。 */
    fun channelNumber(channel: EsChannelItem, allChannels: List<EsChannelItem>): String {
        val sameNetwork = allChannels
            .filter { it.networkId == channel.networkId }
            .sortedBy { it.serviceId }
        return if (isTerrestrial(channel.channelType) && channel.remoteControlKeyId in 1..12) {
            val branch = (sameNetwork.indexOfFirst { it.id == channel.id } + 1).coerceIn(1, 8)
            "%03d".format(channel.remoteControlKeyId!! * 10 + branch)
        } else if (isTerrestrial(channel.channelType)) {
            "%03d".format((channel.serviceId % 1000).toInt())
        } else {
            "%03d".format(channel.serviceId.toInt())
        }
    }

    /** チャンネル群内の最小サービス ID との差分からサブチャンネルを判定する。 */
    fun isSubChannel(channel: EsChannelItem, allChannels: List<EsChannelItem>): Boolean {
        return when {
            isTerrestrial(channel.channelType) -> allChannels
                .filter { it.networkId == channel.networkId }
                .minOfOrNull { it.serviceId }?.let { channel.serviceId != it } == true
            channel.channelType == "BS" -> if (channel.serviceId in 101..189) {
                allChannels
                    .filter { it.serviceId in 101..189 && it.serviceId / 10 == channel.serviceId / 10 }
                    .minOfOrNull { it.serviceId }?.let { channel.serviceId != it } == true
            } else {
                false
            }
            else -> false
        }
    }

    /** チャンネル一覧と番組表からライブ画面用の共通レスポンスを生成する。 */
    fun toChannelApiResponse(
        channels: List<EsChannelItem>,
        schedules: List<EsSchedule> = emptyList()
    ): ChannelApiResponse {
        val now = System.currentTimeMillis()
        fun convert(channel: EsChannelItem): Channel {
            val programs = schedules.firstOrNull { it.channel.id == channel.id }?.programs.orEmpty()
            val current = programs.firstOrNull { it.startAt <= now && now < it.endAt }
            val next = programs.filter { it.startAt >= now }.minByOrNull { it.startAt }
            return Channel(
                id = buildChannelId(channel.id),
                displayChannelId = buildChannelId(channel.id),
                name = channel.name,
                channelNumber = channelNumber(channel, channels),
                networkId = channel.networkId,
                serviceId = channel.serviceId,
                transportStreamId = 0,
                type = normalizeChannelType(channel.channelType),
                isWatchable = true,
                is_subchannel = isSubChannel(channel, channels),
                isDisplay = true,
                programPresent = toProgram(current),
                programFollowing = toProgram(next),
                remocon_Id = channel.remoteControlKeyId ?: 0,
                jikkyoForce = 0
            )
        }
        fun byType(type: String): List<Channel> = channels
            .filter { it.channelType == type }
            .sortedBy { channelNumber(it, channels) }
            .map(::convert)
        return ChannelApiResponse(
            // GR と NW1〜NW40 (他地域の地上波) はまとめて地上波タブに出す。
            terrestrial = channels
                .filter { isTerrestrial(it.channelType) }
                .sortedBy { channelNumber(it, channels) }
                .map(::convert),
            bs = byType("BS"),
            cs = byType("CS"),
            sky = byType("SKY"),
            // 共通モデルに CS4K の受け皿がないため、4K衛星放送を BS4K にまとめる。
            bs4k = byType("BS4K") + byType("CS4K")
        )
    }

    /** 番組表の番組をライブ画面の番組モデルへ変換する。 */
    private fun toProgram(program: EsScheduleProgramItem?): Program? = program?.let {
        Program(
            id = it.id.toString(),
            title = it.name,
            description = it.description ?: "",
            detail = mapDetail(it.extended, it.rawExtended),
            startTime = toIso8601(it.startAt),
            endTime = toIso8601(it.endAt),
            duration = ((it.endAt - it.startAt) / 1000).toInt(),
            genres = mapGenres(it.genre1, it.subGenre1, it.genre2, it.subGenre2, it.genre3, it.subGenre3)
                .map { genre -> Genre(major = genre.major, middle = genre.middle) },
            videoResolution = it.videoResolution
        )
    }

    /** EPGStation の番組表を EpgChannelWrapper へ変換する。 */
    fun toEpgWrapper(schedule: EsSchedule, allChannels: List<EsChannelItem>): EpgChannelWrapper {
        val channel = schedule.channel
        val epgChannel = EpgChannel(
            id = buildChannelId(channel.id),
            display_channel_id = buildChannelId(channel.id),
            network_id = channel.networkId.toInt(),
            service_id = channel.serviceId.toInt(),
            transport_stream_id = 0,
            remocon_id = channel.remoteControlKeyId ?: 0,
            channel_number = channelNumber(channel, allChannels),
            type = normalizeChannelType(channel.channelType),
            name = channel.name,
            jikkyo_force = 0,
            is_subchannel = isSubChannel(channel, allChannels),
            is_radiochannel = false,
            is_watchable = true
        )
        val programs = schedule.programs.sortedBy { it.startAt }.map { program ->
            EpgProgram(
                id = program.id.toString(),
                channel_id = buildChannelId(channel.id),
                network_id = channel.networkId.toInt(),
                service_id = channel.serviceId.toInt(),
                event_id = program.id.toInt(),
                title = program.name,
                description = program.description ?: "",
                extended = program.extended,
                detail = mapDetail(program.extended, program.rawExtended),
                start_time = toIso8601(program.startAt),
                end_time = toIso8601(program.endAt),
                duration = ((program.endAt - program.startAt) / 1000).toInt(),
                is_free = program.isFree,
                genres = mapGenres(program.genre1, program.subGenre1, program.genre2, program.subGenre2, program.genre3, program.subGenre3),
                video_type = program.videoType,
                audio_type = null,
                audio_sampling_rate = program.audioSamplingRate?.toString()
            )
        }
        return EpgChannelWrapper(channel = epgChannel, programs = programs)
    }

    /** 録画 DTO と付加情報を再生画面用の共通モデルへまとめる。 */
    fun toRecordedProgram(
        item: EsRecordedItem,
        channel: EsChannelItem?,
        allChannels: List<EsChannelItem>,
        mapping: EsSeriesMappingValue? = null,
        chapters: List<EsVideoChapter> = emptyList(),
        durationOverride: Double? = null,
        playbackPosition: Double = 0.0,
        ip: String = "",
        port: String = ""
    ): RecordedProgram {
        val file = item.videoFiles?.firstOrNull { it.type == "ts" }
            ?: item.videoFiles?.firstOrNull()
        val duration = file?.duration ?: durationOverride
            ?: ((item.endAt - item.startAt) / 1000.0)
        val series = mapping ?: item.series?.let {
            EsSeriesMappingValue(
                recordedId = item.id,
                seriesId = it.seriesId,
                seriesTitle = it.seriesTitle,
                seasonNumber = it.seasonNumber,
                episodeNumber = it.episodeNumber,
                episodeLabel = it.episodeLabel,
                episodeTitle = it.episodeTitle,
                episodeComment = it.episodeComment,
                episodeCommentSource = it.episodeCommentSource,
                airType = it.airType
            )
        }
        val seriesName = series?.seriesTitle?.takeIf { it.isNotBlank() }
            ?: TitleNormalizer.extractDisplayTitle(item.name)
        val isEpisodic = series?.episodeNumber != null || series?.episodeLabel != null ||
            TitleNormalizer.hasEpisodeNumber(item.name)
        val recordChannel = channel?.let {
            RecordedChannel(
                id = buildChannelId(it.id),
                networkId = it.networkId.toInt(),
                serviceId = it.serviceId.toInt(),
                displayChannelId = buildChannelId(it.id),
                type = normalizeChannelType(it.channelType),
                name = item.tsChannelName ?: item.channelName ?: it.name.ifBlank { "不明なチャンネル" },
                channelNumber = channelNumber(it, allChannels)
            )
        } ?: RecordedChannel(
            id = "",
            displayChannelId = "",
            type = "",
            name = item.tsChannelName ?: item.channelName ?: "不明なチャンネル",
            channelNumber = ""
        )
        val recordedVideo = RecordedVideo(
            id = file?.id ?: 0,
            status = if (item.isRecording) "Recording" else "Recorded",
            filePath = file?.filename ?: file?.name ?: "",
            recordingStartTime = toIso8601(item.startAt),
            recordingEndTime = toIso8601(item.endAt),
            duration = duration,
            containerFormat = file?.type ?: "ts",
            videoCodec = file?.videoCodec ?: "",
            audioCodec = file?.audioCodec ?: "",
            hasKeyFrames = true,
            thumbnailInfo = null,
            // EPGStation のチャプターは本編 (A/B/C…) と CM の両方を含むため、
            // CM 区間だけを抜き出す。ここを絞らないと本編まで CM 扱いでスキップされてしまう。
            cmSections = chapters
                .filter { it.title?.contains("CM", ignoreCase = true) == true }
                .map { CmSection(startTime = it.startAt, endTime = it.endAt) }
        )
        return RecordedProgram(
            id = item.id,
            title = item.name,
            seriesName = seriesName,
            isEpisodic = isEpisodic,
            description = item.description ?: "",
            detail = mapDetail(item.extended, item.rawExtended),
            startTime = toIso8601(item.startAt),
            endTime = toIso8601(item.endAt),
            duration = duration,
            isPartiallyRecorded = false,
            channel = recordChannel,
            recordedVideo = recordedVideo,
            genres = mapGenres(item.genre1, item.subGenre1, item.genre2, item.subGenre2, item.genre3, item.subGenre3),
            isRecording = item.isRecording,
            playbackPosition = file?.watchHistory?.position ?: playbackPosition,
            directThumbnailUrl = null,
            apiThumbnailUrl = item.thumbnails?.firstOrNull()?.let {
                UrlBuilder.getEpgStationThumbnailUrl(ip, port, it)
            }
        )
    }
}
