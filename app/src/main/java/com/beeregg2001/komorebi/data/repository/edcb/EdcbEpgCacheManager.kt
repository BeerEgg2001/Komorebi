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

    // ★ 修正: 以前は"^https?://"を剥がすだけでポート・パスは剥がしていなかったため、
    // EDCBのIP欄にスキーム付きかつポート込みのURL(例: "http://192.168.1.5:5510")を
    // 入力すると、ホスト名が"192.168.1.5:5510"のままSocketに渡され必ず接続に失敗していた。
    private suspend fun getTcpIpAndPort(): Pair<String, Int> {
        val rawIp = settingsRepository.edcbIp.first()
        val cleanIp = com.beeregg2001.komorebi.common.UrlBuilder.extractBareHost(rawIp)
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
                    // ★ 修正: このメソッドはEDCBバックエンドのLive/Epg/Reserve各Repositoryから
                    // 呼ばれており、番組表画面を開いていなくてもライブタブや予約一覧を開くだけで
                    // 15分経過後に到達しうる。以前は全期間取得済み(isFullEpgFetched=true)の
                    // 状態でも無条件でクイックロード(過去1時間〜未来24時間)に入り、
                    // 全期間キャッシュを24時間分で上書きしてしまっていた。既に全期間取得済みなら
                    // 上書きせず、タイマーだけリセットしてバックグラウンドで再取得するに留める。
                    if (cachedServices.isNotEmpty() && cachedEvents.isNotEmpty() && isFullEpgFetched) {
                        lastEpgFetchTime = System.currentTimeMillis()
                        if (fullEpgFetchJob?.isActive != true) {
                            val (ip, port) = getTcpIpAndPort()
                            if (ip.isNotBlank()) {
                                fetchFullEpgDataInBackground(cachedServices, ip, port)
                            }
                        }
                        return@withLock
                    }
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

                        // ★ 修正: EDCB本家(BonCtrl/ChSetUtil.h の IsVideoServiceType())は
                        // 0x01(デジタルTV) / 0xA5(プロモーション映像) / 0xAD(超高精細度4K専用TV)
                        // の3種を通しているが、以前は0xADが漏れており、ケーブル4K(高度リマックス等)
                        // のサービスが一覧から取りこぼされていた。KonomiTV本家(Channel.py)も
                        // 同様に0xADを含めている。
                        val targetServices =
                            services.filter {
                                it.serviceType == 0x01 || it.serviceType == 0xA5 || it.serviceType == 0xAD
                            }

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

    // ★ 修正: BS4K/CATVのONIDを追加。ARIB STD-B10第2部付録N/KonomiTV本家の
    // TSInformation.getNetworkType()で裏付け済み。ただしBS4K(0x000B)は高度BS/MMT・TLV
    // 伝送のため、EDCB(SI解析ベース)経由では受信自体が原理的に不可能で、実際には
    // 経路が無い。将来的な整合性・診断のために型としては追加しておく。
    // CATV(0xFFFE/0xFFFA/0xFFF9/0xFFF7)はJ:COM等のトランスモジュレーション環境で
    // 現実に届きうるため、これまで一覧から無言で消えていた。
    fun getChannelType(onid: Int): String {
        return when {
            onid in 0x7880..0x7FE8 -> "GR"
            onid == 0x0004 -> "BS"
            onid == 0x0006 || onid == 0x0007 -> "CS"
            onid == 0x000A || onid == 0x0001 || onid == 0x0003 -> "SKY" // 0x0001/0x0003は運用終了
            onid == 0x000B || onid == 0x000C -> "BS4K" // 高度BS / 高度110度CS。EDCB経由では到達不能
            onid == 0xFFFE || onid == 0xFFFD || onid == 0xFFFA ||
                onid == 0xFFF9 || onid == 0xFFF7 -> "CATV"
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