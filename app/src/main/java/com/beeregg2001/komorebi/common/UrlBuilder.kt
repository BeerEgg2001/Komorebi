package com.beeregg2001.komorebi.common

object UrlBuilder {

    /**
     * ベースURLを組み立てる
     * @param ip 設定されたIPアドレス
     * @param port 設定されたポート番号
     * @param defaultProtocol IPにプロトコルが含まれていない場合に使用するデフォルト ("http" または "https")
     */
    private fun formatBaseUrl(ip: String, port: String, defaultProtocol: String): String {
        // 末尾のスラッシュを削除して整形
        val cleanIp = ip.removeSuffix("/")

        // 設定値に既に http:// や https:// が含まれている場合はそれを優先
        return if (cleanIp.startsWith("http://") || cleanIp.startsWith("https://")) {
            "$cleanIp:$port"
        } else {
            // 含まれていない場合はデフォルトのプロトコルを付与
            "$defaultProtocol://$cleanIp:$port"
        }
    }

    /**
     * Mirakurun形式のStreamID (ServiceId + NetworkId由来) を構築
     */
    fun buildMirakurunStreamId(networkId: Long, serviceId: Long, type: String?): String {
        val networkIdPart = when (type?.uppercase()) {
            "GR" -> networkId.toString()
            "BS", "CS", "SKY", "BS4K" -> "${networkId}00"
            else -> networkId.toString()
        }
        return "$networkIdPart$serviceId"
    }

    // --- ロゴ関連 ---

    /**
     * Mirakurun経由のロゴURL
     * デフォルトプロトコル: http
     */
    fun getMirakurunLogoUrl(ip: String, port: String, networkId: Long, serviceId: Long, type: String?): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val streamId = buildMirakurunStreamId(networkId, serviceId, type)
        return "$baseUrl/api/services/$streamId/logo"
    }

    /**
     * KonomiTV経由のロゴURL (DisplayChannelId指定)
     * デフォルトプロトコル: https
     */
    fun getKonomiTvLogoUrl(ip: String, port: String, displayChannelId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/channels/$displayChannelId/logo"
    }

    // --- サムネイル関連 ---

    /**
     * 番組サムネイル取得 (KonomiTV API)
     * デフォルトプロトコル: https
     */
    fun getThumbnailUrl(ip: String, port: String, videoId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/thumbnail"
    }

    // --- ストリーミング関連 ---

    /**
     * MirakurunのTSストリームURL
     * デフォルトプロトコル: http
     */
    fun getMirakurunStreamUrl(ip: String, port: String, networkId: Long, serviceId: Long, type: String?): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val streamId = buildMirakurunStreamId(networkId, serviceId, type)
        return "$baseUrl/api/services/$streamId/stream"
    }

    /**
     * KonomiTVのライブストリームURL
     * デフォルトプロトコル: https
     */
    fun getKonomiTvLiveStreamUrl(ip: String, port: String, displayChannelId: String, quality: String = "1080p-60fps"): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/live/$displayChannelId/$quality/mpegts"
    }

    /**
     * 録画ビデオのプレイリストURL (KonomiTV API)
     * デフォルトプロトコル: https
     */
    fun getVideoPlaylistUrl(ip: String, port: String, videoId: Int, sessionId: String, quality: String = "1080p-60fps"): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/video/$videoId/$quality/playlist?session_id=$sessionId"
    }

    /**
     * タイル状サムネイル取得 (KonomiTV API)
     * シーンサーチ等で使用
     */
    fun getTiledThumbnailUrl(ip: String, port: String, videoId: Int, time: Long): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        // 指定されたパス形式に time パラメータを付与
        return "$baseUrl/api/videos/$videoId/thumbnail/tiled?time=$time"
    }
}