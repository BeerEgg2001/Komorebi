package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.repository.EpgProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** EPGStation の番組表を取得するリポジトリ。 */
@Singleton
class EpgStationEpgRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val api: EpgStationApi,
    private val channelCache: EpgStationChannelCache
) : EpgProvider {
    /** 指定時間帯と放送種別の番組表を取得する。 */
    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ): List<EpgChannelWrapper> {
        return try {
            val start = startTime?.let(EpgStationDataMapper::fromIso8601)
                ?.takeIf { it > 0 } ?: System.currentTimeMillis()
            val end = endTime?.let(EpgStationDataMapper::fromIso8601)
                ?.takeIf { it > start } ?: start + 24 * 60 * 60 * 1000
            val type = channelType?.uppercase()
            val schedules = api.getSchedules(
                startAt = start,
                endAt = end,
                broadcastFlags = EpgStationDataMapper.buildBroadcastFlags(type)
            )
            val index = channelCache.getChannelIndex()
            schedules.map { EpgStationDataMapper.toEpgWrapper(it, index) }
                .sortedBy { it.channel.channel_number }
        } catch (e: Exception) {
            throw Exception(
                "EPGStationからの番組表データ取得に失敗しました。\n[詳細]: ${e.message}",
                e
            )
        }
    }

    /** ピン留めされたチャンネルだけの番組表を返す。 */
    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        val ids = pinnedChannelIds.split(',')
            .mapNotNull { EpgStationDataMapper.parseChannelId(it.trim()) }
            .toSet()
        return getEpgPrograms().filter {
            EpgStationDataMapper.parseChannelId(it.channel.id) in ids
        }
    }
}
