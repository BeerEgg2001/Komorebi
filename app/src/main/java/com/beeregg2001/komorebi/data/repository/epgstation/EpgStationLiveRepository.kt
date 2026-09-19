package com.beeregg2001.komorebi.data.repository.epgstation

import android.content.Context
import android.util.Log
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.jikkyo.JikkyoChannelResolver
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.di.EpgStationClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** EPGStation のライブチャンネル、配信 URL、局ロゴを提供するリポジトリ。 */
@Singleton
class EpgStationLiveRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val api: EpgStationApi,
    private val channelCache: EpgStationChannelCache,
    @EpgStationClient private val client: OkHttpClient,
    private val jikkyoChannelResolver: JikkyoChannelResolver,
    @ApplicationContext private val context: Context
) : LiveProvider {
    private companion object {
        private const val TAG = "EpgStationLiveRepo"

        // ★ 追加: ロゴ取得の通信失敗を恒久扱いにしないための再試行間隔
        private const val LOGO_RETRY_INTERVAL_MS = 5 * 60 * 1000L

        // ★ 追加: 番組表フォールバック(getSchedules)のキャッシュTTLと取得範囲。
        // ChannelViewModelは60秒間隔でgetChannels()をポーリングするが、EPGStation Forkの
        // getBroadcastingSchedule()はEPG未取得局や重複サブチャンネルも含めて返す仕様のため、
        // フォールバック条件がほぼ常に成立してしまう。フォールバック自体は同じprogramDBを
        // 引くだけでEPG未取得局には効果が無い一方、6時間分・全放送波の重いJSONを
        // 60秒おきに取得し続けるのは無駄が大きいため、取得範囲を3時間に縮小し、
        // 結果を数分キャッシュして再取得の頻度を落とす。
        private const val FALLBACK_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val FALLBACK_RANGE_MS = 3 * 60 * 60 * 1000L
    }

    private val fallbackMutex = Mutex()
    @Volatile
    private var cachedFallbackSchedules: List<EsSchedule>? = null
    @Volatile
    private var fallbackFetchedAt = 0L

    // サーバーが「ロゴを持たない」と明言したチャンネル(hasLogoData!=true)。恒久的にスキップしてよい。
    private val noLogoChannels = ConcurrentHashMap.newKeySet<Long>()

    // 通信失敗でロゴ取得できなかったチャンネルと、その失敗時刻。LOGO_RETRY_INTERVAL_MS経過後に再試行する。
    private val logoFetchFailedAt = ConcurrentHashMap<Long, Long>()

    // ★ 追加: ライブHLSのstreamId保持用(EpgStationRecordRepositoryの録画側と同じパターン)。
    // サーバー側はストリーム開始時に15秒の停止タイマーをセットし、PUT /keepでしか
    // リセットされない(stuayu/EPGStation StreamBaseModel.ts)。m2ts/m2tsllはサーバー側の
    // ルートハンドラが自己延命するが、HLSはクライアント側でkeepを送る設計になっており、
    // 以前は一切送っていなかったためライブHLSは再生開始から約15秒で必ず停止していた。
    // streamNumber(0=メイン, 1=サブ)単位で保持し、二画面ライブ視聴で双方が同時に
    // EPGStation HLSを選んでも互いのstreamIdを上書きしないようにする。
    private val liveStreamIdByNumber = ConcurrentHashMap<Int, Int>()

    /**
     * 局ロゴの同時取得数を制限するためのセマフォ。
     * チャンネル数は数百件になることがあり、一覧を開いた瞬間に全件を並列で取りに行くと
     * サーバー (特にリバースプロキシ越しの構成) に負荷が集中してしまうため絞っている。
     */
    private val logoSemaphore = Semaphore(permits = 4)

    /** 放送中番組と次番組を含むチャンネル一覧を取得する。 */
    override suspend fun getChannels(): ChannelApiResponse {
        return try {
            val (channels, broadcasting, forceMap) = coroutineScope {
                val channelJob = async { channelCache.getChannels() }
                val broadcastingJob = async {
                    api.getBroadcasting(includeNextProgram = true)
                }
                // 実況の勢い取得は失敗してもチャンネル一覧取得を巻き込まないようにする。
                val forceJob = async(Dispatchers.IO) {
                    runCatching { jikkyoChannelResolver.fetchForceMap() }
                        .onFailure { Log.w(TAG, "Failed to fetch jikkyo force map", it) }
                        .getOrDefault(emptyMap())
                }
                Triple(channelJob.await(), broadcastingJob.await(), forceJob.await())
            }
            val now = System.currentTimeMillis()
            val needsFallback = channels.any { channel ->
                val programs = broadcasting.firstOrNull { it.channel.id == channel.id }?.programs.orEmpty()
                programs.none { it.startAt <= now && now < it.endAt } ||
                    programs.none { it.startAt >= now }
            }
            // ★ 修正: フォールバック条件(needsFallback)は、Fork仕様上EPG未取得局や
            // 重複サブチャンネルが1局でもあればほぼ常にtrueになる(EpgStationLiveRepository末尾の
            // FALLBACK_CACHE_TTL_MS定義部のコメント参照)。60秒ポーリングのたびに6時間・全放送波の
            // 重いJSONを取り直していたのを、取得範囲の縮小+数分キャッシュで緩和する。
            val schedules = if (needsFallback) {
                val fallback = getOrFetchFallbackSchedules(now)
                (broadcasting + fallback).groupBy { it.channel.id }.map { (_, values) ->
                    values.first().copy(
                        programs = values.flatMap { it.programs }.distinctBy { it.id }
                    )
                }
            } else {
                broadcasting
            }
            EpgStationDataMapper.toChannelApiResponse(channels, schedules) { networkId, serviceId ->
                val jkId = jikkyoChannelResolver.getJikkyoId(networkId.toInt(), serviceId.toInt())
                jkId?.let { forceMap[it] } ?: 0
            }
        } catch (e: Exception) {
            throw Exception(
                "チャンネル一覧の取得に失敗しました。\n" +
                    "EPGStationの接続設定とサーバーの稼働状況を確認してください。\n" +
                    "[詳細]: ${e.message}",
                e
            )
        }
    }

    /**
     * 番組表フォールバック(getSchedules)の結果を数分キャッシュしつつ返す。
     * EPG未取得局はフォールバックしても0件のままなので再取得しても改善しないが、
     * それでもポーリングのたびに毎回叩くと無駄が大きいため、キャッシュで頻度を落とす。
     */
    private suspend fun getOrFetchFallbackSchedules(now: Long): List<EsSchedule> {
        val cached = cachedFallbackSchedules
        if (cached != null && now - fallbackFetchedAt < FALLBACK_CACHE_TTL_MS) {
            return cached
        }
        return fallbackMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val current = cachedFallbackSchedules
            if (current != null && lockedNow - fallbackFetchedAt < FALLBACK_CACHE_TTL_MS) {
                return@withLock current
            }
            try {
                val fallback = api.getSchedules(
                    startAt = lockedNow,
                    endAt = lockedNow + FALLBACK_RANGE_MS,
                    broadcastFlags = EpgStationDataMapper.buildBroadcastFlags()
                )
                cachedFallbackSchedules = fallback
                fallbackFetchedAt = lockedNow
                fallback
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch fallback schedules. Keeping previous cache.", e)
                cachedFallbackSchedules ?: emptyList()
            }
        }
    }

    /** 設定されたライブ配信形式を画質選択肢へ変換する。 */
    suspend fun getLiveStreamQualities(): List<StreamQuality> {
        val result = mutableListOf<StreamQuality>()
        return try {
            // ★ 修正: サーバーの/api/configレスポンスはstreamConfig.live.ts.m2tsのように
            // 1段深い構造で返るため、以前の"streamConfig?.live"直下参照では常にnullになり
            // このメソッドは常に空リストを返していた(呼び出し元のLivePlayerViewModelで
            // ハードコードされたフォールバックに常に落ちていた)。
            val config = api.getConfig().streamConfig?.live?.ts
            config?.m2ts.orEmpty().forEachIndexed { index, item ->
                result += StreamQuality("m2ts: ${item.name}", "m2ts:$index", item.isUnconverted)
            }
            config?.m2tsll.orEmpty().forEachIndexed { index, label ->
                result += StreamQuality("m2tsll: $label", "m2tsll:$index")
            }
            config?.hls.orEmpty().forEachIndexed { index, label ->
                result += StreamQuality("hls: $label", "hls:$index")
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * ライブHLSストリームの停止タイマーをリセットする。
     * HLS再生中のみ呼び出し元(LivePlayerViewModel)が定期的に呼ぶ想定。
     * ストリームIDが無い(HLS以外を再生中、またはstreamNumberが一致しない)場合は何もしない。
     */
    suspend fun keepLiveStream(streamNumber: Int) {
        val id = liveStreamIdByNumber[streamNumber] ?: return
        try {
            api.keepStream(id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to keep live HLS stream alive (streamId=$id)", e)
        }
    }

    /** 指定画質でライブストリームを開始し、再生 URL を返す。 */
    override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int): String {
        val id = EpgStationDataMapper.parseChannelId(channelId)
            ?: throw Exception("EPGStationのチャンネルIDが不正です。")
        val parts = quality.split(":", limit = 2)
        val format = parts.firstOrNull()?.lowercase() ?: "m2ts"
        val mode = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val ip = settings.epgStationIp.first()
        val port = settings.epgStationPort.first()
        return try {
            if (format == "hls") {
                val stream = api.startLiveHls(id, mode)
                if (stream.streamId <= 0) {
                    throw Exception("HLSストリームの開始に失敗しました (streamId=${stream.streamId})")
                }
                liveStreamIdByNumber[streamNumber] = stream.streamId
                delay(3000)
                UrlBuilder.getEpgStationHlsPlaylistUrl(ip, port, stream.streamId)
            } else if (format == "m2tsll") {
                liveStreamIdByNumber.remove(streamNumber)
                UrlBuilder.getEpgStationLiveM2tsLlUrl(ip, port, id, mode)
            } else {
                liveStreamIdByNumber.remove(streamNumber)
                UrlBuilder.getEpgStationLiveM2tsUrl(ip, port, id, mode)
            }
        } catch (e: Exception) {
            throw Exception("ライブストリームの開始に失敗しました。\n[詳細]: ${e.message}", e)
        }
    }

    /**
     * 局ロゴをキャッシュへ保存して file URI を返す。
     * UI から直接呼ばれてもメインスレッドを塞がないよう、必ず IO ディスパッチャで実行する。
     */
    override suspend fun getChannelLogoUrl(channelId: String): String = withContext(Dispatchers.IO) {
        val id = EpgStationDataMapper.parseChannelId(channelId) ?: return@withContext ""
        val file = File(context.cacheDir, "channel_logos/$id.png")
        if (file.exists()) return@withContext "file://${file.absolutePath}"
        // ★ 修正: サーバーが「ロゴを持たない」と明言しているチャンネル(hasLogoData!=true)は
        // 恒久的にスキップして問題ないが、通信失敗(サーバー起動直後・ネットワーク瞬断等)も
        // 同じfailedLogosに入れて恒久化していたため、アプリ再起動までロゴが復活しなかった。
        // 両者を分離し、通信失敗はLOGO_RETRY_INTERVAL_MS経過後に再試行できるようにする。
        if (noLogoChannels.contains(id)) return@withContext ""
        val lastFailedAt = logoFetchFailedAt[id]
        if (lastFailedAt != null && System.currentTimeMillis() - lastFailedAt < LOGO_RETRY_INTERVAL_MS) {
            return@withContext ""
        }
        try {
            if (channelCache.getChannels().firstOrNull { it.id == id }?.hasLogoData != true) {
                noLogoChannels.add(id)
                return@withContext ""
            }
            val url = UrlBuilder.getEpgStationLogoUrl(
                settings.epgStationIp.first(),
                settings.epgStationPort.first(),
                id
            )
            logoSemaphore.withPermit {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val bytes = response.body?.bytes() ?: error("空のロゴです")
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                }
            }
            logoFetchFailedAt.remove(id)
            "file://${file.absolutePath}"
        } catch (_: Exception) {
            logoFetchFailedAt[id] = System.currentTimeMillis()
            ""
        }
    }
}
