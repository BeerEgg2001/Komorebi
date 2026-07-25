package com.beeregg2001.komorebi.data.api.interceptor

import android.util.Log
import com.beeregg2001.komorebi.data.SettingsRepository
import kotlinx.coroutines.flow.first
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
 * 認証情報の漏洩を防ぐため、設定された KonomiTV / Mirakurun サーバーの
 * ホスト宛のリクエストにのみヘッダーを付与する。
 */
@Singleton
class CloudflareAccessInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = runBlocking { settingsRepository.getCfAccessHeaders() }
        if (headers.isEmpty()) return chain.proceed(request)

        val protectedHosts = runBlocking {
            val ip = settingsRepository.konomiIp.first()
            val port = settingsRepository.konomiPort.first()
            val konomiBaseUrl = if (ip.startsWith("http://") || ip.startsWith("https://")) {
                "$ip:$port"
            } else {
                "http://$ip:$port"
            }
            listOfNotNull(
                konomiBaseUrl.toHttpUrlOrNull()?.host,
                settingsRepository.getMirakurunBaseUrl()?.toHttpUrlOrNull()?.host
            )
        }
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
