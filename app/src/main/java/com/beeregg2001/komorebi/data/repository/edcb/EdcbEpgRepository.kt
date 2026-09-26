package com.beeregg2001.komorebi.data.repository.edcb

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.repository.EpgProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdcbEpgRepository @Inject constructor(
    private val cacheManager: EdcbEpgCacheManager
) : EpgProvider {

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ): List<EpgChannelWrapper> = withContext(Dispatchers.Default) {
        try {
            // キャッシュマネージャー経由でEPGデータを取得（必要時のみ通信）
            cacheManager.fetchEpgDataIfNeeded()

            val filteredServices = if (channelType != null) {
                cacheManager.cachedServices.filter { cacheManager.getChannelType(it.onid) == channelType }
            } else {
                cacheManager.cachedServices
            }

            val eventsByService =
                cacheManager.cachedEvents.groupBy { "${it.onid}_${it.tsid}_${it.sid}" }

            val wrappers = filteredServices.map { svc ->
                val type = cacheManager.getChannelType(svc.onid)
                val channelId = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}"
                val isSubChannel = cacheManager.isSubChannel(type, svc.sid, svc.tsid)

                val epgChannel = EpgChannel(
                    id = channelId,
                    display_channel_id = channelId,
                    network_id = svc.onid,
                    service_id = svc.sid,
                    transport_stream_id = svc.tsid,
                    remocon_id = svc.remoteControlKeyId,
                    channel_number = cacheManager.formatChannelNumber(
                        type,
                        svc.remoteControlKeyId,
                        svc.sid,
                        svc.tsid
                    ),
                    type = type,
                    name = svc.serviceName,
                    jikkyo_force = 0,
                    is_subchannel = isSubChannel,
                    is_radiochannel = false,
                    is_watchable = true
                )

                val svcEvents = eventsByService["${svc.onid}_${svc.tsid}_${svc.sid}"] ?: emptyList()

                // Mapperに委譲して変換
                val epgPrograms = svcEvents.mapNotNull { ev ->
                    EdcbDataMapper.toEpgProgram(ev, channelId, svc.onid, svc.sid)
                }

                EpgChannelWrapper(
                    channel = epgChannel,
                    programs = epgPrograms.sortedBy { it.start_time }
                )
            }

            return@withContext wrappers.sortedBy { it.channel.channel_number.toIntOrNull() ?: 9999 }
        } catch (e: Exception) {
            // ★ 修正: エラーを握りつぶさずラップしてUIに返す
            throw Exception("番組表データの生成に失敗しました。\n[詳細]: ${e.message}")
        }
    }

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        // EDCBバックエンドではピン留め(お気に入り)番組表は未対応
        return emptyList()
    }
}