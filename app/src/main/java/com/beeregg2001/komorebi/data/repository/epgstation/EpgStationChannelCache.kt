package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.EsChannelItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** EPGStation のチャンネル一覧を全リポジトリで共有するキャッシュ。 */
@Singleton
class EpgStationChannelCache @Inject constructor(
    private val api: EpgStationApi
) {
    companion object {
        private const val CACHE_EXPIRATION_MS = 5 * 60 * 1000L
    }

    private val mutex = Mutex()
    private var channels: List<EsChannelItem> = emptyList()
    private var fetchedAt = 0L
    private var index: EpgStationDataMapper.ChannelIndex? = null
    // ★ 修正: 索引を作った時点のチャンネル一覧を覚えておき、参照が変わった(=再取得された)ときだけ作り直す。
    // 件数だけを見ていると、件数据え置きでチャンネル構成(ID等)が入れ替わった場合に古い索引を使い続けてしまう。
    private var indexedChannels: List<EsChannelItem>? = null

    /**
     * チャンネル番号・サブチャンネル判定の索引を返す。
     * 一覧が更新されたときだけ作り直し、録画 1 件ごとの再計算を避ける。
     */
    suspend fun getChannelIndex(): EpgStationDataMapper.ChannelIndex {
        val current = getChannels()
        return mutex.withLock {
            index?.takeIf { indexedChannels === current } ?: EpgStationDataMapper.ChannelIndex(current).also {
                index = it
                indexedChannels = current
            }
        }
    }

    /** 有効期限内の一覧を返し、期限切れなら一度だけ再取得する。 */
    suspend fun getChannels(): List<EsChannelItem> {
        val now = System.currentTimeMillis()
        if (channels.isNotEmpty() && now - fetchedAt < CACHE_EXPIRATION_MS) {
            return channels
        }
        return mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (channels.isEmpty() || lockedNow - fetchedAt >= CACHE_EXPIRATION_MS) {
                channels = api.getChannels()
                fetchedAt = lockedNow
            }
            channels
        }
    }
}
