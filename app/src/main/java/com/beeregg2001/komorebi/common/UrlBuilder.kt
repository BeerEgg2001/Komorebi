package com.beeregg2001.komorebi.common

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

object UrlBuilder {

    /**
     * ベースURLを組み立てる
     */
    private fun formatBaseUrl(ip: String, port: String, defaultProtocol: String): String {
        val cleanIp = ip.removeSuffix("/")
        return if (cleanIp.startsWith("http://") || cleanIp.startsWith("https://")) {
            "$cleanIp:$port"
        } else {
            "$defaultProtocol://$cleanIp:$port"
        }
    }

    /**
     * Mirakurun形式のStreamID
     */
    @OptIn(UnstableApi::class)
    fun buildMirakurunStreamId(networkId: Long, serviceId: Long): String {
        val mirakurunId: Long = (networkId * 100000) + serviceId
        return mirakurunId.toString()
    }

    // --- ロゴ関連 ---
    fun getMirakurunLogoUrl(ip: String, port: String, networkId: Long, serviceId: Long): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val streamId = buildMirakurunStreamId(networkId, serviceId)
        return "$baseUrl/api/services/$streamId/logo"
    }

    fun getKonomiTvLogoUrl(ip: String, port: String, displayChannelId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/channels/$displayChannelId/logo"
    }

    // --- サムネイル関連 ---
    fun getThumbnailUrl(ip: String, port: String, videoId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/thumbnail"
    }

    // --- ストリーミング関連 ---
    fun getMirakurunStreamUrl(ip: String, port: String, networkId: Long, serviceId: Long): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val streamId = buildMirakurunStreamId(networkId, serviceId)
        return "$baseUrl/api/services/$streamId/stream"
    }

    fun getKonomiTvLiveStreamUrl(ip: String, port: String, displayChannelId: String, quality: String = "1080p-60fps"): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/live/$displayChannelId/$quality/mpegts"
    }

    fun getKonomiTvLiveEventsUrl(ip: String, port: String, displayChannelId: String, quality: String = "1080p-60fps"): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/live/$displayChannelId/$quality/events"
    }

    @OptIn(UnstableApi::class)
    fun getVideoPlaylistUrl(ip: String, port: String, videoId: Int, sessionId: String, quality: String = "1080p-60fps"): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        Log.d("Komorebi_Debug", "Playing URL: $baseUrl/api/streams/video/$videoId/$quality/playlist?session_id=$sessionId")
        return "$baseUrl/api/streams/video/$videoId/$quality/playlist?session_id=$sessionId"
    }

    /**
     * シークバー用タイル画像取得 (KonomiTV API)
     * URL: /api/videos/{id}/thumbnail/tiled
     * パラメータなしで巨大なシート画像を取得する仕様
     */
    fun getTiledThumbnailUrl(ip: String, port: String, videoId: Int): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/thumbnail/tiled"
    }

    // ★追加: アーカイブ実況コメントAPIのURL
    fun getArchivedJikkyoUrl(ip: String, port: String, videoId: Int): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/jikkyo"
    }
}