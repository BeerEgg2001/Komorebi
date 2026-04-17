package com.beeregg2001.komorebi.data.repository

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.local.dao.EpgCacheDao
import com.beeregg2001.komorebi.data.local.entity.EpgCacheEntity
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

data class EpgSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel
)

class EpgRepository @Inject constructor(
    @ApplicationContext private val context: Context, // ★ 追加: キャッシュディレクトリを使用するためContextを注入
    private val epgProvider: EpgProvider,
    private val epgCacheDao: EpgCacheDao,
    private val gson: Gson
) {
    private val memoryCache = ConcurrentHashMap<String, List<EpgChannelWrapper>>()

    // ==========================================
    // ★ 高速ファイルキャッシュ処理
    // ==========================================

    @OptIn(UnstableApi::class)
    private suspend fun saveToFileCache(channelType: String, channels: List<EpgChannelWrapper>) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = java.io.File(context.cacheDir, "epg_cache_${channelType}.json.gz")
            // 直接GZIP圧縮しながらファイルへ書き込む（Base64のオーバーヘッドを削減）
            java.io.FileOutputStream(cacheFile).use { fos ->
                GZIPOutputStream(fos).use { gzip ->
                    val jsonString = gson.toJson(channels)
                    gzip.write(jsonString.toByteArray(Charsets.UTF_8))
                }
            }

            // DBにはメタデータと「FILE_BASED」という文字列だけを保存して2MB制限を回避
            epgCacheDao.insertOrUpdate(
                EpgCacheEntity(
                    channelType = channelType,
                    dataJson = "FILE_BASED",
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e("EPG", "File Cache Save Error for $channelType", e)
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun readFromFileCache(channelType: String): List<EpgChannelWrapper>? = withContext(Dispatchers.IO) {
        try {
            val entity = try {
                epgCacheDao.getCache(channelType)
            } catch (e: android.database.sqlite.SQLiteException) {
                // 古い巨大なBLOBがDBに残っていてクラッシュした場合、握りつぶして新規取得させる
                Log.w("EPG", "Huge legacy DB blob found for $channelType. Ignoring to force refresh.", e)
                return@withContext null
            }

            if (entity == null) return@withContext null

            val cacheFile = java.io.File(context.cacheDir, "epg_cache_${channelType}.json.gz")
            if (!cacheFile.exists()) return@withContext null

            // ファイルから解凍しながら直接文字列として読み込む
            val jsonString = java.io.FileInputStream(cacheFile).use { fis ->
                GZIPInputStream(fis).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val listType = object : TypeToken<List<EpgChannelWrapper>>() {}.type
            return@withContext gson.fromJson(jsonString, listType)

        } catch (e: Exception) {
            Log.e("EPG", "File Cache Read Error for $channelType", e)
            return@withContext null
        }
    }

    // ==========================================

    private fun normalizeForSearch(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return normalized.lowercase()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun searchFuturePrograms(
        query: String = "",
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ): List<EpgSearchResultItem> {

        val isOrSearch = query.contains(",") || query.contains("、")
        val delimiters = if (isOrSearch) Regex("[,、]+") else Regex("[\\s]+")
        val keywords =
            query.split(delimiters).map { normalizeForSearch(it.trim()) }.filter { it.isNotBlank() }

        if (keywords.isEmpty() && genre.isNullOrBlank() && dateStr.isNullOrBlank() && channelName.isNullOrBlank() && !isLiveOnly) {
            return emptyList()
        }

        val results = mutableListOf<EpgSearchResultItem>()
        val nowMs = System.currentTimeMillis()

        val targetTvDate = try {
            dateStr?.takeIf { it.isNotBlank() }?.let {
                java.time.LocalDate.parse(it.replace("/", "-"))
            }
        } catch (e: Exception) {
            null
        }

        memoryCache.values.flatten().forEach { wrapper ->
            if (!channelName.isNullOrBlank() && !wrapper.channel.name.contains(
                    channelName,
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            wrapper.programs.forEach { prog ->
                try {
                    val startTimeMs =
                        OffsetDateTime.parse(prog.start_time).toInstant().toEpochMilli()
                    if (startTimeMs > nowMs) {

                        if (targetTvDate != null) {
                            val startDt = OffsetDateTime.parse(prog.start_time)
                            val base = startDt.withHour(4).withMinute(0).withSecond(0).withNano(0)
                            val tvDate = if (startDt.hour < 4) base.minusDays(1)
                                .toLocalDate() else base.toLocalDate()
                            if (tvDate != targetTvDate) return@forEach
                        }

                        if (!genre.isNullOrBlank()) {
                            val matchGenre = (prog.genres?.any {
                                it.major.contains(genre) || it.middle.contains(genre)
                            } == true) || prog.title.contains(genre)
                            if (!matchGenre) return@forEach
                        }

                        if (isLiveOnly) {
                            val detailText =
                                prog.detail?.entries?.joinToString(" ") { "${it.key} ${it.value}" }
                                    ?: ""
                            val title = prog.title ?: ""
                            val desc = prog.description ?: ""
                            val matchLive =
                                title.contains("[生]") || title.contains("【生】") || title.contains("生中継") || title.contains(
                                    "生放送"
                                ) || title.contains("LIVE", ignoreCase = true) ||
                                        desc.contains("生中継") || desc.contains("生放送") || detailText.contains(
                                    "生中継"
                                ) || detailText.contains("生放送")
                            if (!matchLive) return@forEach
                        }

                        if (keywords.isNotEmpty()) {
                            val detailText =
                                prog.detail?.entries?.joinToString(" ") { "${it.key} ${it.value}" }
                                    ?: ""
                            val combinedDesc =
                                "${wrapper.channel.name} ${prog.title} ${prog.description} $detailText"
                            val normalizedDesc = normalizeForSearch(combinedDesc)

                            val isMatch = if (isOrSearch) {
                                keywords.any { k -> normalizedDesc.contains(k) }
                            } else {
                                keywords.all { k -> normalizedDesc.contains(k) }
                            }
                            if (!isMatch) return@forEach
                        }

                        results.add(EpgSearchResultItem(prog, wrapper.channel))
                    }
                } catch (e: Exception) { /* ignore */
                }
            }
        }

        return results.sortedBy {
            try {
                OffsetDateTime.parse(it.program.start_time).toInstant().toEpochMilli()
            } catch (e: Exception) {
                0L
            }
        }
    }

    fun hasCacheForType(channelType: String): Boolean {
        return memoryCache.containsKey(channelType)
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchAndCacheEpgDataSilently(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String
    ) {
        if (hasCacheForType(channelType)) return

        var isFreshCacheAvailable = false

        // ★修正: DBではなくファイルから読み込む
        val cachedData = readFromFileCache(channelType)
        if (cachedData != null) {
            memoryCache[channelType] = cachedData

            val oneDayLater = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
            val isFresh = cachedData.flatMap { it.programs }.any {
                try {
                    OffsetDateTime.parse(it.start_time).toInstant().toEpochMilli() > oneDayLater
                } catch (e: Exception) {
                    false
                }
            }

            if (isFresh) {
                isFreshCacheAvailable = true
            }
        }

        if (isFreshCacheAvailable) return

        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val channels = epgProvider.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            memoryCache[channelType] = channels

            // ★修正: ファイルへ保存する
            saveToFileCache(channelType, channels)

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Silent Fetch Error for $channelType", e)
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    fun getEpgDataStream(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String
    ): Flow<Result<List<EpgChannelWrapper>>> = flow {

        memoryCache[channelType]?.let { emit(Result.success(it)) }

        if (memoryCache[channelType] == null) {
            // ★修正: DBではなくファイルから読み込む
            val cachedData = readFromFileCache(channelType)
            if (cachedData != null) {
                memoryCache[channelType] = cachedData
                emit(Result.success(cachedData))
            }
        }

        try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val channels = epgProvider.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )

            memoryCache[channelType] = channels
            emit(Result.success(channels))

            CoroutineScope(Dispatchers.IO).launch {
                // ★修正: ファイルへ保存する
                saveToFileCache(channelType, channels)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            if (memoryCache[channelType] == null) emit(Result.failure(e))
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun fetchEpgData(
        startTime: OffsetDateTime,
        endTime: OffsetDateTime,
        channelType: String? = null
    ): Result<List<EpgChannelWrapper>> {
        return try {
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            val startStr = startTime.format(formatter)
            val endStr = endTime.format(formatter)

            val channels = epgProvider.getEpgPrograms(
                startTime = startStr,
                endTime = endStr,
                channelType = channelType
            )
            Result.success(channels)
        } catch (e: Exception) {
            Log.e("EPG", "Fetch Error: $startTime to $endTime", e)
            Result.failure(e)
        }
    }

    suspend fun fetchPinnedChannels(pinnedIds: List<String>): Result<List<EpgChannelWrapper>> {
        return try {
            val channels = epgProvider.getPinnedEpgPrograms(pinnedIds.joinToString(","))
            Result.success(channels)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}