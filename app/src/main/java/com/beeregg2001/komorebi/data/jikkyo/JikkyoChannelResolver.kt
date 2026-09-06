package com.beeregg2001.komorebi.data.jikkyo

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * networkId/serviceId から NX-Jikkyo のチャンネルID(jkID)を解決したり、
 * 実況の勢い(コメント数/分)を取得したりする処理を一元化するクラス。
 *
 * 元々は EdcbLiveRepository / LiveJikkyoManager / EpgStationRecordRepository の
 * 3箇所にほぼ同じロジックが重複していたため、ここへ集約した。
 */
@Singleton
class JikkyoChannelResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "JikkyoChannelResolver"

        // 実況の勢い取得キャッシュの有効期間 (連続呼び出しで毎回 HTTP を叩かないようにするため)
        private const val FORCE_CACHE_TTL_MS = 60_000L

        // 実況対応チャンネルIDのマップ
        val JIKKYO_CHANNEL_ID_MAP = mapOf(
            "jk1" to "ch2646436", "jk2" to "ch2646437", "jk4" to "ch2646438",
            "jk5" to "ch2646439", "jk6" to "ch2646440", "jk7" to "ch2646441",
            "jk8" to "ch2646442", "jk9" to "ch2646485", "jk10" to null,
            "jk11" to null, "jk12" to null, "jk13" to null, "jk14" to null,
            "jk101" to "ch2647992", "jk103" to null, "jk141" to null,
            "jk151" to null, "jk161" to null, "jk171" to null, "jk181" to null,
            "jk191" to null, "jk192" to null, "jk193" to null, "jk200" to null,
            "jk201" to null, "jk211" to "ch2646846", "jk222" to null,
            "jk236" to null, "jk252" to null, "jk260" to null, "jk263" to null,
            "jk265" to null, "jk333" to null
        )
    }

    // NX-Jikkyo は外部サービスのため、バックエンド向けヘッダを送らない専用クライアントを使う。
    private val externalHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().apply {
            interceptors().clear()
        }.build()
    }

    private var jikkyoChannelsCache: JSONArray? = null

    private val forceMapMutex = Mutex()
    private var forceMapCache: Map<String, Int> = emptyMap()
    private var forceMapCachedAt = 0L

    /** assets の jikkyo_channels.json を1回だけ読み、以降はキャッシュを返す。 */
    private fun getJikkyoChannels(): JSONArray {
        if (jikkyoChannelsCache != null) return jikkyoChannelsCache!!
        return try {
            val jsonString =
                context.assets.open("jikkyo_channels.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonString)
            jikkyoChannelsCache = array
            array
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /**
     * networkId/serviceId から NX-Jikkyo のチャンネルID(jkID)を解決する。
     *
     * [restrictToKnownChannels] が true の場合、[JIKKYO_CHANNEL_ID_MAP] に存在する jkID のみ返す
     * (ライブ実況・実況の勢い表示など、実際に視聴可能なチャンネルに絞りたい用途向け)。
     * false の場合はこの絞り込みを行わない。録画実況(過去ログ)は NX-Jikkyo の kakolog API を
     * 使っており、マップに無いチャンネルでも過去ログが取得できる可能性があるため、
     * 録画実況側の呼び出しはこちらを使うこと。
     */
    fun getJikkyoId(networkId: Int, serviceId: Int, restrictToKnownChannels: Boolean = true): String? {
        val channels = getJikkyoChannels()
        for (i in 0 until channels.length()) {
            val jc = channels.optJSONObject(i) ?: continue
            val jcNid = jc.optInt("network_id", -1)

            val sidRaw = jc.opt("service_id")?.toString() ?: "-1"
            val jcSid = if (sidRaw.startsWith("0x", ignoreCase = true)) {
                sidRaw.substring(2).toIntOrNull(16) ?: -1
            } else {
                sidRaw.toIntOrNull() ?: -1
            }
            val jkJikkyoId = jc.optInt("jikkyo_id", -1)

            var matched = false
            if (networkId == jcNid && serviceId == jcSid) {
                matched = true
            } else if (networkId in 0x7880..0x7FEF && jcNid == 15) {
                if (serviceId == jcSid || serviceId - 1 == jcSid || serviceId - 2 == jcSid) {
                    matched = true
                }
            }

            if (matched && jkJikkyoId != -1) {
                val jkId = "jk$jkJikkyoId"
                if (!restrictToKnownChannels || JIKKYO_CHANNEL_ID_MAP.containsKey(jkId)) {
                    return jkId
                }
            }
        }
        return null
    }

    /**
     * NX-Jikkyo の各チャンネルの実況の勢い(直近スレッドの最大コメント数/分)を取得する。
     * 60秒程度の短命キャッシュを持ち、連続呼び出しで毎回 HTTP を叩かないようにする。
     * タイムアウトは2秒とし、失敗時は例外を投げず空 Map を返す
     * (実況 API がダウンしていてもチャンネル一覧取得を巻き込まないため)。
     */
    suspend fun fetchForceMap(): Map<String, Int> = withContext(Dispatchers.IO) {
        forceMapMutex.withLock {
            val now = System.currentTimeMillis()
            if (forceMapCache.isNotEmpty() && now - forceMapCachedAt < FORCE_CACHE_TTL_MS) {
                return@withContext forceMapCache
            }

            val forceMap = mutableMapOf<String, Int>()
            try {
                val request = Request.Builder()
                    .url("https://nx-jikkyo.tsukumijima.net/api/v1/channels")
                    .build()

                val client = externalHttpClient.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseJson = response.body?.string()
                        if (responseJson != null) {
                            val jsonArray = JSONArray(responseJson)
                            for (i in 0 until jsonArray.length()) {
                                val channelObj = jsonArray.optJSONObject(i) ?: continue
                                val jkId = channelObj.optString("id", "")
                                if (jkId.isBlank()) continue

                                val threads = channelObj.optJSONArray("threads") ?: continue
                                var maxForce = 0
                                for (j in 0 until threads.length()) {
                                    val thread = threads.optJSONObject(j) ?: continue
                                    val force = thread.optInt("jikkyo_force", 0)
                                    if (force > maxForce) maxForce = force
                                }
                                forceMap[jkId] = maxForce
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch NX-Jikkyo force (Timeout or Network error)")
            }

            // 取得できた場合だけキャッシュが効く (空Mapは isNotEmpty 判定に掛からないため、
            // 次回呼び出しで再試行される)。失敗時に空Mapを返す従来の挙動は維持する。
            forceMapCache = forceMap
            forceMapCachedAt = now
            return@withContext forceMap
        }
    }
}
