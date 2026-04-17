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
    @OptIn(UnstableApi::class)
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
    // ★修正: backendTypeを受け取り、システムごとに正しいパスを生成する
    fun getThumbnailUrl(backendType: String, ip: String, port: String, videoId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "http") // サムネイルは基本的にhttpフォールバックで安全に組む
        return when (backendType) {
            // ★修正: TODOを削除し、EMWUIの標準サムネイルAPIパスを設定
            "EDCB" -> "$baseUrl/api/Thumbnail?id=$videoId"
            "EPGSTATION" -> "$baseUrl/api/thumbnails/$videoId" // EPGStationの標準サムネイルAPI
            else -> { // KonomiTV (デフォルト)
                val secureBaseUrl = formatBaseUrl(ip, port, "https")
                "$secureBaseUrl/api/videos/$videoId/thumbnail"
            }
        }
    }

    // --- ストリーミング関連 ---
    fun getMirakurunStreamUrl(ip: String, port: String, networkId: Long, serviceId: Long): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val streamId = buildMirakurunStreamId(networkId, serviceId)
        return "$baseUrl/api/services/$streamId/stream"
    }

    fun getKonomiTvLiveStreamUrl(
        ip: String,
        port: String,
        displayChannelId: String,
        quality: String = "1080p-60fps"
    ): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/live/$displayChannelId/$quality/mpegts"
    }

    fun getKonomiTvLiveEventsUrl(
        ip: String,
        port: String,
        displayChannelId: String,
        quality: String = "1080p-60fps"
    ): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/live/$displayChannelId/$quality/events"
    }

    @OptIn(UnstableApi::class)
    fun getVideoPlaylistUrl(
        ip: String,
        port: String,
        videoId: Int,
        sessionId: String,
        quality: String = "1080p-60fps"
    ): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
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

    // アーカイブ実況コメントAPIのURL
    fun getArchivedJikkyoUrl(ip: String, port:  String, videoId: Int): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/jikkyo"
    }

    /**
     * EDCBの録画フォルダにある静的サムネイル (録画ファイル名.ts.jpg) を直接取得するURL
     */
    fun getEdcbDirectThumbnailUrl(ip: String, port: String, recFilePath: String): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val relativePath = recFilePath
            .replace(Regex("^[a-zA-Z]:\\\\"), "")
            .replace("\\", "/")

        // 録画ファイルの末尾に .jpg を足すことで "hoge.ts.jpg" を指定
        val encodedPath = android.net.Uri.encode(relativePath, "/")
        return "$baseUrl/rec/$encodedPath.jpg"
    }
}