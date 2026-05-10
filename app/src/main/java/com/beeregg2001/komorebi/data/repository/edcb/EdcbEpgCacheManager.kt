package com.beeregg2001.komorebi.data.repository.edcb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.edcb.EdcbApi
import com.beeregg2001.komorebi.data.api.edcb.EdcbEventInfo
import com.beeregg2001.komorebi.data.api.edcb.EdcbServiceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EDCBのEPGデータ（サービス一覧・イベント一覧）のTCP取得とメモリキャッシュを管理するクラス。
 * 複数のRepository（Live, Reserve, Epg等）から共有される。
 */
@Singleton
class EdcbEpgCacheManager @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "EdcbEpgCache"
        private const val CACHE_EXPIRATION_MS = 15 * 60 * 1000L // 15分

        // バックグラウンド更新完了通知用
        val epgBackgroundUpdateEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    private val epgMutex = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fullEpgFetchJob: Job? = null

    // --- キャッシュデータ ---
    var cachedServices: List<EdcbServiceInfo> = emptyList()
        private set
    var cachedEvents: List<EdcbEventInfo> = emptyList()
        private set

    var lastEpgFetchTime = 0L
        private set
    var isFullEpgFetched = false
        private set

    // サブチャンネル判定用マップ
    private var tsidToSidsMap: Map<Int, List<Int>> = emptyMap()
    private var bsPrefixToSidsMap: Map<Int, List<Int>> = emptyMap()

    // ========================================================================
    // データ取得ロジック
    // ========================================================================

    private suspend fun getTcpIpAndPort(): Pair<String, Int> {
        val rawIp = settingsRepository.edcbIp.first()
        val cleanIp = rawIp.replace(Regex("^https?://"), "")
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
        return Pair(cleanIp, port)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchEpgDataIfNeeded() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedServices.isEmpty() || cachedEvents.isEmpty() || (now - lastEpgFetchTime) > CACHE_EXPIRATION_MS) {
            epgMutex.withLock {
                // ロック取得後にもう一度チェック（複数スレッドからの同時呼び出し対策）
                if (cachedServices.isEmpty() || cachedEvents.isEmpty() || (System.currentTimeMillis() - lastEpgFetchTime) > CACHE_EXPIRATION_MS) {
                    try {
                        Log.i(TAG, "🔄 Fetching fresh EPG data from EDCB (Quick Load)...")
                        val (ip, port) = getTcpIpAndPort()
                        if (ip.isBlank()) throw Exception("EDCBのIPアドレスが設定されていません。")

                        val edcbApi = EdcbApi(ip, port)
                        val services = edcbApi.getServices().getOrNull() ?: emptyList()

                        // ★ 修正: サービス一覧が取得できない場合はエラーとして明確に投げる
                        if (services.isEmpty()) {
                            throw Exception("サービス一覧が0件です。EDCB側でEPG取得が完了しているか確認してください。")
                        }

                        // 映像・音声サービスのみフィルタリング
                        val targetServices =
                            services.filter { it.serviceType == 1 || it.serviceType == 165 }

                        // クイックロード: 過去1時間〜未来24時間分のデータだけを同期取得
                        val fetchStartTime = LocalDateTime.now().minusHours(1)
                        val fetchEndTime = LocalDateTime.now().plusHours(24)

                        val events =
                            edcbApi.getEventInfos(targetServices, fetchStartTime, fetchEndTime)
                                .getOrNull() ?: emptyList()

                        cachedServices = targetServices
                        cachedEvents = events
                        lastEpgFetchTime = System.currentTimeMillis()
                        isFullEpgFetched = false

                        // サブチャンネル・枝番計算用のマップを構築
                        tsidToSidsMap = targetServices
                            .filter { getChannelType(it.onid) == "GR" }
                            .groupBy { it.tsid }
                            .mapValues { (_, svcs) -> svcs.map { it.sid }.sorted() }

                        bsPrefixToSidsMap = targetServices
                            .filter { getChannelType(it.onid) == "BS" }
                            .groupBy { it.sid / 10 }
                            .mapValues { (_, svcs) -> svcs.map { it.sid }.sorted() }

                        Log.i(
                            TAG,
                            "✅ Quick EPG Cache updated! Services=${cachedServices.size}, Events=${cachedEvents.size}"
                        )

                        // 裏側で全期間のEPG取得を開始
                        fetchFullEpgDataInBackground(targetServices, ip, port)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch EPG data", e)
                        // ★ 修正: エラーを握りつぶさず、分かりやすい日本語でスローする
                        throw Exception("EDCBサーバーからの番組表データ取得に失敗しました。\nIPアドレスやポート設定、EDCBの稼働状況を確認してください。\n[詳細]: ${e.message}")
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun fetchFullEpgDataInBackground(
        services: List<EdcbServiceInfo>,
        ip: String,
        port: Int
    ) {
        fullEpgFetchJob?.cancel()
        fullEpgFetchJob = managerScope.launch {
            try {
                Log.i(TAG, "⏳ Starting full EPG data fetch in background...")
                val edcbApi = EdcbApi(ip, port)
                // 期間指定なしで全取得
                val allEvents = edcbApi.getEventInfos(services).getOrNull()

                if (allEvents != null) {
                    epgMutex.withLock {
                        cachedEvents = allEvents
                        isFullEpgFetched = true
                        lastEpgFetchTime = System.currentTimeMillis()
                        Log.i(
                            TAG,
                            "✅ Full EPG Cache updated in background! Events=${cachedEvents.size}"
                        )
                    }
                    epgBackgroundUpdateEvent.tryEmit(Unit)
                }
            } catch (e: Exception) {
                // バックグラウンド処理のエラーはUIを邪魔しないようログ出力のみ
                Log.e(TAG, "❌ Failed to fetch full EPG data in background", e)
            }
        }
    }

    /**
     * バックエンド切り替え時などにキャッシュをクリアする
     */
    fun clearCache() {
        managerScope.launch {
            epgMutex.withLock {
                cachedServices = emptyList()
                cachedEvents = emptyList()
                lastEpgFetchTime = 0L
                isFullEpgFetched = false
                tsidToSidsMap = emptyMap()
                bsPrefixToSidsMap = emptyMap()
                fullEpgFetchJob?.cancel()
            }
        }
    }

    // ========================================================================
    // チャンネル解析・判定ロジック
    // ========================================================================

    fun getChannelType(onid: Int): String {
        return when {
            onid == 4 -> "BS"
            onid == 6 || onid == 7 -> "CS"
            onid == 10 -> "SKY"
            onid in 0x7880..0x7FE8 -> "GR"
            else -> "UNKNOWN"
        }
    }

    fun isSubChannel(type: String, sid: Int, tsid: Int): Boolean {
        return when (type) {
            "GR" -> {
                val sidsInTs = tsidToSidsMap[tsid]
                sidsInTs != null && sidsInTs.isNotEmpty() && sidsInTs[0] != sid
            }

            "BS" -> {
                if (sid in 101..189) {
                    val prefix = sid / 10
                    val sidsForPrefix = bsPrefixToSidsMap[prefix]
                    sidsForPrefix != null && sidsForPrefix.isNotEmpty() && sidsForPrefix[0] != sid
                } else {
                    false
                }
            }

            else -> false
        }
    }

    fun formatChannelNumber(type: String, remoconId: Int, serviceId: Int, tsid: Int): String {
        return if (type == "GR") {
            if (remoconId in 1..12) {
                val sidsInTs = tsidToSidsMap[tsid]
                val index = sidsInTs?.indexOf(serviceId) ?: 0
                val branchNum = (index + 1).coerceIn(1, 8)
                String.format("%03d", remoconId * 10 + branchNum)
            } else {
                String.format("%03d", serviceId % 1000)
            }
        } else {
            String.format("%03d", serviceId)
        }
    }
}