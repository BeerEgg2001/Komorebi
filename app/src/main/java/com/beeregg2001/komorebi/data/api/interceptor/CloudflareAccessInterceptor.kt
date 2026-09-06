package com.beeregg2001.komorebi.data.api.interceptor

import android.util.Log
import com.beeregg2001.komorebi.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloudflare Zero Trust (Cloudflare Access) のサービストークンを
 * リクエストヘッダーに付与するインターセプター。
 *
 * トークンが未設定の場合は何もしない。
 * 認証情報の漏洩を防ぐため、設定された KonomiTV / Mirakurun / EPGStation サーバーの
 * ホスト宛のリクエストにのみヘッダーを付与する。
 *
 * ★ 性能上の注意:
 * このインターセプターは API 通信だけでなく Coil (AsyncImage) の画像取得にも
 * 適用される。以前は intercept() のたびに runBlocking で DataStore を読んでいたため、
 * ホーム画面が局ロゴやサムネイルを何十枚も読み込む起動直後に、OkHttp のワーカースレッドが
 * 毎回 DataStore の読み出し完了までブロックされていた (初回は実ディスク I/O)。
 * 設定値は Flow で購読してキャッシュしておき、リクエスト経路からは同期読み出しを排除する。
 */
@Singleton
class CloudflareAccessInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository
) : Interceptor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cloudflare Access のヘッダー。未購読完了の間だけ null。 */
    @Volatile
    private var cachedHeaders: Map<String, String>? = null

    /** ヘッダーを付与してよいホスト名。未購読完了の間だけ null。 */
    @Volatile
    private var cachedProtectedHosts: List<String>? = null

    init {
        scope.launch {
            combine(
                settingsRepository.cfAccessClientId,
                settingsRepository.cfAccessClientSecret
            ) { id, secret ->
                SettingsRepository.buildCfAccessHeaders(id, secret)
            }.distinctUntilChanged().collect { cachedHeaders = it }
        }

        scope.launch {
            combine(
                listOf(
                    settingsRepository.konomiIp,
                    settingsRepository.konomiPort,
                    settingsRepository.mirakurunIp,
                    settingsRepository.mirakurunPort,
                    settingsRepository.epgStationIp,
                    settingsRepository.epgStationPort
                )
            ) { values -> buildProtectedHosts(values.toList()) }
                .distinctUntilChanged()
                .collect { cachedProtectedHosts = it }
        }
    }

    private fun buildProtectedHosts(values: List<String>): List<String> {
        val (konomiIp, konomiPort) = values[0] to values[1]
        val (mirakurunIp, mirakurunPort) = values[2] to values[3]
        val (epgStationIp, epgStationPort) = values[4] to values[5]

        val konomiBaseUrl = if (konomiIp.startsWith("http://") || konomiIp.startsWith("https://")) {
            "$konomiIp:$konomiPort"
        } else {
            "http://$konomiIp:$konomiPort"
        }

        val mirakurunBaseUrl = if (mirakurunIp.isBlank()) {
            null
        } else if (mirakurunIp.startsWith("http://") || mirakurunIp.startsWith("https://")) {
            "$mirakurunIp:$mirakurunPort"
        } else {
            "http://$mirakurunIp:$mirakurunPort"
        }

        val epgStationBaseUrl = if (epgStationIp.isBlank()) {
            null
        } else {
            com.beeregg2001.komorebi.common.UrlBuilder.formatBaseUrl(
                epgStationIp,
                epgStationPort,
                "http"
            )
        }

        return listOfNotNull(
            konomiBaseUrl.toHttpUrlOrNull()?.host,
            mirakurunBaseUrl?.toHttpUrlOrNull()?.host,
            // ★ 追加: EPGStation も Cloudflare Access 配下に置かれることがあるため対象に含める
            epgStationBaseUrl?.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()?.host
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // キャッシュが未充填なのは起動直後のごく短い間だけ。その場合のみ従来通り同期読みする。
        val headers = cachedHeaders
            ?: runBlocking { settingsRepository.getCfAccessHeaders() }.also { cachedHeaders = it }
        if (headers.isEmpty()) return chain.proceed(request)

        val protectedHosts = cachedProtectedHosts ?: runBlocking {
            buildProtectedHosts(
                listOf(
                    settingsRepository.konomiIp.first(),
                    settingsRepository.konomiPort.first(),
                    settingsRepository.mirakurunIp.first(),
                    settingsRepository.mirakurunPort.first(),
                    settingsRepository.epgStationIp.first(),
                    settingsRepository.epgStationPort.first()
                )
            )
        }.also { cachedProtectedHosts = it }

        if (request.url.host !in protectedHosts) {
            return chain.proceed(request)
        }

        // ★ 診断用: トークン本体は出さず、長さと前後数文字だけをログに出す
        headers[SettingsRepository.CF_ACCESS_CLIENT_ID_HEADER]?.let {
            Log.d("CloudflareAccessInterceptor", "Client-Id len=${it.length} value=${mask(it)}")
        }
        headers[SettingsRepository.CF_ACCESS_CLIENT_SECRET_HEADER]?.let {
            Log.d("CloudflareAccessInterceptor", "Client-Secret len=${it.length} value=${mask(it)}")
        }

        val newRequest = request.newBuilder().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return chain.proceed(newRequest)
    }

    private fun mask(value: String): String {
        if (value.length <= 8) return "*".repeat(value.length)
        return "${value.take(4)}...${value.takeLast(4)}"
    }
}
