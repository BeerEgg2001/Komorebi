package com.beeregg2001.komorebi.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.edcb.EdcbApi
import com.beeregg2001.komorebi.data.api.edcb.EdcbEventInfo
import com.beeregg2001.komorebi.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdcbRepository @Inject constructor(
    @ApplicationContext private val context: Context, // ★ 追加: キャッシュディレクトリ利用のため
    private val settingsRepository: SettingsRepository
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider {

    companion object {
        private const val TAG = "EdcbRepository"
    }

    // LogoData.ini を何度も取りに行かないためのメモリキャッシュ
    private var logoDataIniCache: String? = null
    private var logoDataIniAttempted = false // ★ 追加: 探索済かどうかのフラグ
    private val logoMutex = Mutex()

    /**
     * EDCBのTCP通信を使ってロゴを取得し、ローカルにキャッシュする
     */
    override suspend fun getChannelLogoUrl(channelId: String): String = withContext(Dispatchers.IO) {
        val parts = channelId.split("_")
        if (parts.size != 4 || parts[0] != "edcb") return@withContext ""

        val onid = parts[1].toIntOrNull() ?: return@withContext ""
        val sid = parts[3].toIntOrNull() ?: return@withContext ""

        // 1. 既にAndroid内にキャッシュファイルがあればそれを返す（爆速化）
        val cacheFilePng = File(context.cacheDir, "logo_${onid}_${sid}.png")
        val cacheFileBmp = File(context.cacheDir, "logo_${onid}_${sid}.bmp")
        if (cacheFilePng.exists()) {
            return@withContext "file://${cacheFilePng.absolutePath}"
        }
        if (cacheFileBmp.exists()) {
            return@withContext "file://${cacheFileBmp.absolutePath}"
        }

        val ip = settingsRepository.edcbIp.first()
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
        if (ip.isBlank()) return@withContext ""

        val edcbApi = EdcbApi(ip, port)

        // 2. EDCBから LogoData.ini を探索して取得 (スレッドセーフに1回だけ実行)
        logoMutex.withLock {
            if (!logoDataIniAttempted) {
                logoDataIniAttempted = true

                // ★ 修正: EDCBのバージョンによるパスの違いをすべて探索する
                val possiblePaths = listOf(
                    "Setting\\LogoData.ini",
                    "LogoData.ini",
                    "LogoData\\LogoData.ini"
                )

                for (path in possiblePaths) {
                    Log.d(TAG, "📥 [EdcbLogo] 探索中: $path")
                    val iniBytes = edcbApi.fetchFile(path)

                    if (iniBytes != null && iniBytes.isNotEmpty()) {
                        logoDataIniCache = decodeEdcbString(iniBytes)
                        Log.i(TAG, "✅ [EdcbLogo] 発見！ LogoData.ini を $path から取得しました")
                        break // 見つかったら探索終了
                    }
                }

                if (logoDataIniCache == null) {
                    Log.e(TAG, "❌ [EdcbLogo] どのパスにも LogoData.ini が見つかりませんでした")
                }
            }
        }

        val iniText = logoDataIniCache ?: return@withContext ""

        // 3. LogoData.ini からロゴIDを検索
        val targetKey = String.format("%04X%04X", onid, sid).uppercase()
        var logoId = -1
        iniText.lines().forEach { line ->
            val split = line.split("=")
            if (split.size == 2 && split[0].trim().uppercase() == targetKey) {
                logoId = split[1].trim().toIntOrNull() ?: -1
            }
        }

        if (logoId < 0) {
            Log.w(TAG, "⚠️ [EdcbLogo] ロゴIDが設定されていません: $targetKey")
            return@withContext ""
        }

        // 4. EDCBのファイルシステムから直接画像を引っこ抜く
        // 近年のKonomiTV環境等ではPNGが主流なため、PNGを先に試し、ダメならBMPを試す
        val pngName = String.format("LogoData\\%04X_%03X_01.png", onid, logoId)
        val bmpName = String.format("LogoData\\%04X_%03X_01.bmp", onid, logoId)

        Log.d(TAG, "📥 [EdcbLogo] 画像取得を試行します: $pngName")
        val pngBytes = edcbApi.fetchFile(pngName)
        if (pngBytes != null && pngBytes.isNotEmpty()) {
            cacheFilePng.writeBytes(pngBytes)
            Log.i(TAG, "💾 [EdcbLogo] PNGロゴをキャッシュに保存しました: ${cacheFilePng.name}")
            return@withContext "file://${cacheFilePng.absolutePath}"
        }

        Log.d(TAG, "📥 [EdcbLogo] 画像取得を試行します: $bmpName")
        val bmpBytes = edcbApi.fetchFile(bmpName)
        if (bmpBytes != null && bmpBytes.isNotEmpty()) {
            cacheFileBmp.writeBytes(bmpBytes)
            Log.i(TAG, "💾 [EdcbLogo] BMPロゴをキャッシュに保存しました: ${cacheFileBmp.name}")
            return@withContext "file://${cacheFileBmp.absolutePath}"
        }

        Log.e(TAG, "❌ [EdcbLogo] 画像ファイルがサーバー内に存在しません (LogoID: $logoId)")
        return@withContext ""
    }

    /**
     * EDCBのINIファイルをパースするための文字コード判定ヘルパー
     */
    private fun decodeEdcbString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // BOM付き UTF-16LE
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        // BOM付き UTF-8
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // デフォルト (Windowsは基本Shift_JIS、Linux環境等の場合はUTF-8にフォールバック)
        return try {
            String(bytes, charset("Shift_JIS"))
        } catch (e: Exception) {
            String(bytes)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun getChannels(): ChannelApiResponse = withContext(Dispatchers.Default) {
        val ip = settingsRepository.edcbIp.first()
        val port = settingsRepository.edcbPort.first().toIntOrNull() ?: 4510
        if (ip.isBlank()) throw Exception("IP not set")

        val edcbApi = EdcbApi(ip, port)

        val services = edcbApi.getServices().getOrNull() ?: emptyList()

        val nowMillis = System.currentTimeMillis()
        val startTargetMillis = nowMillis - (1 * 3600 * 1000L)
        val endTargetMillis = nowMillis + (12 * 3600 * 1000L)

        val events = edcbApi.getEventInfos(services, startTargetMillis, endTargetMillis).getOrNull()
            ?: emptyList()

        Log.i(TAG, "📡 EDCB Summary (Live): Services=${services.size}, Events=${events.size}")

        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

        val eventsByService = events.groupBy { "${it.onid}_${it.tsid}_${it.sid}" }

        val presentAndFollowingMap = eventsByService.mapValues { (_, svcEvents) ->
            val sortedEvents = svcEvents.mapNotNull { ev ->
                if (ev.startTime == null) return@mapNotNull null
                try {
                    val start = LocalDateTime.parse(ev.startTime, formatter)
                    val end = start.plusSeconds(ev.durationSec.toLong())
                    Triple(ev, start, end)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.second }

            var present: EdcbEventInfo? = null
            var following: EdcbEventInfo? = null

            for (i in sortedEvents.indices) {
                val (ev, start, end) = sortedEvents[i]
                if (now.isAfter(start) && now.isBefore(end)) {
                    present = ev
                    if (i + 1 < sortedEvents.size) {
                        following = sortedEvents[i + 1].first
                    }
                    break
                } else if (now.isBefore(start) && present == null) {
                    following = ev
                    break
                }
            }
            Pair(present, following)
        }

        @RequiresApi(Build.VERSION_CODES.O)
        fun EdcbEventInfo.toProgram(): Program {
            val isoStartTime = formatToIso(this.startTime)
            val isoEndTime = if (this.startTime != null && this.durationSec > 0) {
                try {
                    val startLdt = LocalDateTime.parse(this.startTime, formatter)
                    val endLdt = startLdt.plusSeconds(this.durationSec.toLong())
                    endLdt.atZone(ZoneId.of("Asia/Tokyo"))
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                } catch (e: Exception) {
                    ""
                }
            } else {
                ""
            }

            return Program(
                id = this.eid.toString(),
                title = this.eventName,
                description = this.eventText,
                detail = emptyMap(),
                startTime = isoStartTime,
                endTime = isoEndTime,
                duration = this.durationSec,
                genres = emptyList(),
                videoResolution = null
            )
        }

        val gr = mutableListOf<Channel>()
        val bs = mutableListOf<Channel>()
        val cs = mutableListOf<Channel>()
        val sky = mutableListOf<Channel>()
        val bs4k = mutableListOf<Channel>()

        services.forEach { svc ->
            if (svc.serviceType != 1 && svc.serviceType != 165) return@forEach
            val type = when {
                svc.onid == 4 -> "BS"
                svc.onid == 6 || svc.onid == 7 -> "CS"
                svc.onid == 10 -> "SKY"
                else -> "GR"
            }

            val key = "${svc.onid}_${svc.tsid}_${svc.sid}"
            val (presentEvent, followingEvent) = presentAndFollowingMap[key] ?: Pair(null, null)

            val channel = Channel(
                id = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}",
                displayChannelId = "edcb_${svc.onid}_${svc.tsid}_${svc.sid}",
                name = svc.serviceName,
                channelNumber = if (svc.remoteControlKeyId > 0) svc.remoteControlKeyId.toString() else svc.sid.toString(),
                networkId = svc.onid.toLong(),
                serviceId = svc.sid.toLong(),
                transportStreamId = svc.tsid.toLong(),
                type = type,
                isWatchable = true,
                isDisplay = true,
                programPresent = presentEvent?.toProgram(),
                programFollowing = followingEvent?.toProgram(),
                remocon_Id = svc.remoteControlKeyId,
                jikkyoForce = 0
            )

            when (type) {
                "GR" -> gr.add(channel)
                "BS" -> bs.add(channel)
                "CS" -> cs.add(channel)
                "SKY" -> sky.add(channel)
                else -> if (svc.serviceType == 165) bs4k.add(channel)
            }
        }

        // ★ 修正: リターンする前に、それぞれの放送波に最適なルールで並び替え（ソート）を実行する
        ChannelApiResponse(
            // 地デジ (GR) はリモコン番号順 (1〜12)。リモコン番号がない/不正なものは最後に回す
            terrestrial = gr.sortedWith(
                compareBy(
                    { if (it.remocon_Id > 0) it.remocon_Id else 9999 },
                    { it.serviceId })
            ),
            // BS/CS/SKY/4K は サービスID (SID) 順。これにより NHK BS(101), BS日テレ(141) と綺麗に並ぶ
            bs = bs.sortedBy { it.serviceId },
            cs = cs.sortedBy { it.serviceId },
            sky = sky.sortedBy { it.serviceId },
            bs4k = bs4k.sortedBy { it.serviceId }
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun formatToIso(edcbTime: String?): String {
        if (edcbTime.isNullOrBlank()) return ""
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            val localDateTime = LocalDateTime.parse(edcbTime, formatter)
            val zonedDateTime = localDateTime.atZone(ZoneId.of("Asia/Tokyo"))
            zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            ""
        }
    }

    // --- 未実装メソッドのスタブ ---
    override suspend fun getLiveStreamUrl(channelId: String, quality: String): String = ""
    override suspend fun getRecordedPrograms(page: Int): RecordedApiResponse =
        RecordedApiResponse(0, emptyList())

    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> =
        Result.failure(Exception("Not implemented"))

    override suspend fun searchRecordedPrograms(keyword: String, page: Int): RecordedApiResponse =
        RecordedApiResponse(0, emptyList())

    override suspend fun getRecordStreamUrl(v: Int, q: String, s: String): String = ""
    override suspend fun getArchivedJikkyo(v: Int): Result<List<ArchivedComment>> =
        Result.success(emptyList())

    @androidx.annotation.OptIn(UnstableApi::class)
    override suspend fun keepAlive(v: Int, q: String, s: String) {
    }

    override suspend fun getReserves(): Result<List<ReserveItem>> = Result.success(emptyList())
    override suspend fun addReserve(r: ReserveRequest): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun updateReserve(i: Int, r: ReserveRequest): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun deleteReservation(i: Int): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> =
        Result.success(emptyList())

    override suspend fun addReservationCondition(r: ReservationConditionAddRequest): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun updateReservationCondition(
        i: Int,
        r: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> = Result.failure(Exception("Not implemented"))

    override suspend fun deleteReservationCondition(i: Int): Result<Unit> =
        Result.failure(Exception("Not implemented"))

    override suspend fun getEpgPrograms(
        s: String?,
        e: String?,
        t: String?
    ): List<EpgChannelWrapper> = emptyList()

    override suspend fun getPinnedEpgPrograms(p: String): List<EpgChannelWrapper> = emptyList()
}