package com.beeregg2001.komorebi.data.mapper

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.local.entity.EpgChannelEntity
import com.beeregg2001.komorebi.data.local.entity.EpgProgramEntity
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.google.gson.Gson
import java.time.OffsetDateTime

object EpgDataMapper {
    private val gson = Gson()

    fun toChannelEntity(apiModel: EpgChannel): EpgChannelEntity {
        return EpgChannelEntity(
            id = apiModel.id,
            displayChannelId = apiModel.display_channel_id,
            networkId = apiModel.network_id,
            serviceId = apiModel.service_id,
            transportStreamId = apiModel.transport_stream_id,
            remoconId = apiModel.remocon_id,
            channelNumber = apiModel.channel_number,
            type = apiModel.type,
            name = apiModel.name,
            jikkyoForce = apiModel.jikkyo_force,
            isSubchannel = apiModel.is_subchannel,
            isRadiochannel = apiModel.is_radiochannel,
            isWatchable = apiModel.is_watchable
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun toProgramEntity(apiModel: EpgProgram): EpgProgramEntity {
        // "2023-10-01T12:00:00+09:00" などの文字列をミリ秒に変換
        val startEpoch = runCatching { OffsetDateTime.parse(apiModel.start_time).toInstant().toEpochMilli() }.getOrDefault(0L)
        val endEpoch = runCatching { OffsetDateTime.parse(apiModel.end_time).toInstant().toEpochMilli() }.getOrDefault(0L)

        return EpgProgramEntity(
            id = apiModel.id,
            channelId = apiModel.channel_id,
            networkId = apiModel.network_id,
            serviceId = apiModel.service_id,
            eventId = apiModel.event_id,
            title = apiModel.title,
            description = apiModel.description,
            extended = apiModel.extended,
            detailJson = apiModel.detail?.let { gson.toJson(it) },
            startTime = apiModel.start_time,
            endTime = apiModel.end_time,
            startTimeEpoch = startEpoch,
            endTimeEpoch = endEpoch,
            duration = apiModel.duration,
            isFree = apiModel.is_free,
            genresJson = apiModel.genres?.let { gson.toJson(it) },
            videoType = apiModel.video_type,
            audioType = apiModel.audio_type,
            audioSamplingRate = apiModel.audio_sampling_rate
        )
    }
}