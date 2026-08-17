package com.beeregg2001.komorebi.data.repository.epgstation

import android.content.Context
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.di.EpgStationClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
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
    @ApplicationContext private val context: Context
) : LiveProvider {
    private val failedLogos = ConcurrentHashMap.newKeySet<Long>()

    /**
     * 局ロゴの同時取得数を制限するためのセマフォ。
     * チャンネル数は数百件になることがあり、一覧を開いた瞬間に全件を並列で取りに行くと
     * サーバー (特にリバースプロキシ越しの構成) に負荷が集中してしまうため絞っている。
     */
    private val logoSemaphore = Semaphore(permits = 4)

    /** 放送中番組と次番組を含むチャンネル一覧を取得する。 */
    override suspend fun getChannels(): ChannelApiResponse {
        return try {
            val (channels, broadcasting) = coroutineScope {
                val channelJob = async { channelCache.getChannels() }
                val broadcastingJob = async {
                    api.getBroadcasting(includeNextProgram = true)
                }
                channelJob.await() to broadcastingJob.await()
            }
            val now = System.currentTimeMillis()
            val needsFallback = channels.any { channel ->
                val programs = broadcasting.firstOrNull { it.channel.id == channel.id }?.programs.orEmpty()
                programs.none { it.startAt <= now && now < it.endAt } ||
                    programs.none { it.startAt >= now }
            }
            val schedules = if (needsFallback) {
                val fallback = api.getSchedules(
                    startAt = now,
                    endAt = now + 6 * 60 * 60 * 1000,
                    broadcastFlags = EpgStationDataMapper.buildBroadcastFlags()
                )
                (broadcasting + fallback).groupBy { it.channel.id }.map { (_, values) ->
                    values.first().copy(
                        programs = values.flatMap { it.programs }.distinctBy { it.id }
                    )
                }
            } else {
                broadcasting
            }
            EpgStationDataMapper.toChannelApiResponse(channels, schedules)
        } catch (e: Exception) {
            throw Exception(
                "チャンネル一覧の取得に失敗しました。\n" +
                    "EPGStationの接続設定とサーバーの稼働状況を確認してください。\n" +
                    "[詳細]: ${e.message}",
                e
            )
        }
    }

    /** 設定されたライブ配信形式を画質選択肢へ変換する。 */
    suspend fun getLiveStreamQualities(): List<StreamQuality> {
        val result = mutableListOf<StreamQuality>()
        return try {
            val config = api.getConfig().streamConfig?.live
            config?.m2ts.orEmpty().forEachIndexed { index, item ->
                result += StreamQuality("m2ts: ${item.name}", "m2ts:$index", item.isUnconverted)
            }
            config?.hls.orEmpty().forEachIndexed { index, label ->
                result += StreamQuality("hls: $label", "hls:$index")
            }
            result
        } catch (_: Exception) {
            emptyList()
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
                delay(3000)
                UrlBuilder.getEpgStationHlsPlaylistUrl(ip, port, stream.streamId)
            } else {
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
        if (failedLogos.contains(id)) return@withContext ""
        try {
            if (channelCache.getChannels().firstOrNull { it.id == id }?.hasLogoData != true) {
                failedLogos.add(id)
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
            "file://${file.absolutePath}"
        } catch (_: Exception) {
            failedLogos.add(id)
            ""
        }
    }
}
