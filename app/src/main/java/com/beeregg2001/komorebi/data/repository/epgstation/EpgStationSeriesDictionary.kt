package com.beeregg2001.komorebi.data.repository.epgstation

import android.util.Log
import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.EsSeriesListItem
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.util.TitleNormalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EpgStationSeriesDictionary"
private const val SERIES_PAGE_SIZE = 100
private const val MAX_INDEX_ITEMS = 20_000
private const val PAGE_PARALLELISM = 3
private const val DICTIONARY_DELAY_MS = 75L
private const val CACHE_TTL_MS = 30 * 60 * 1_000L
// 一時的な通信エラーで無効化した場合の再試行までの待ち時間
private const val UNAVAILABLE_RETRY_MS = 10 * 60 * 1_000L

@Singleton
class EpgStationSeriesDictionary @Inject constructor(
    private val api: EpgStationApi,
    private val settingsRepository: SettingsRepository
) {
    private data class Cache(
        val endpoint: String,
        val expiresAt: Long,
        val byTitle: Map<String, String>
    )

    private var cache: Cache? = null

    // API 自体が存在しない (フォーク版でない) 場合は恒久的に無効化する
    private var permanentlyUnavailable = false

    // 一時的な通信エラーの場合は一定時間だけ無効化し、その後は再試行する
    private var unavailableUntil = 0L

    suspend fun resolve(baseTitle: String): String? {
        val keyword = baseTitle.trim()
        if (keyword.isEmpty() || isUnavailable()) return null

        val endpoint = settingsRepository.epgStationIp.first().trim()
        if (endpoint.isEmpty()) return null

        val index = getOrBuildIndex(endpoint) ?: return null
        val key = normalizeTitle(keyword)
        index.byTitle[key]?.let { return it }

        return searchDictionary(keyword)
    }

    private suspend fun getOrBuildIndex(endpoint: String): Cache? {
        val now = System.currentTimeMillis()
        cache?.takeIf { it.endpoint == endpoint && it.expiresAt > now }?.let { return it }

        return try {
            val first = api.getSeries(offset = 0, limit = SERIES_PAGE_SIZE)
            if (!first.isSuccessful) {
                markUnavailable(
                    "シリーズ一覧の取得に失敗しました。HTTP " + first.code(),
                    permanent = isUnsupportedStatus(first.code())
                )
                return null
            }

            val firstBody = first.body()
            if (firstBody == null) {
                markUnavailable("シリーズ一覧のレスポンスが空です。")
                return null
            }

            val pageCount = minOf(
                (firstBody.total + SERIES_PAGE_SIZE - 1) / SERIES_PAGE_SIZE,
                (MAX_INDEX_ITEMS + SERIES_PAGE_SIZE - 1) / SERIES_PAGE_SIZE
            )
            val pages = coroutineScope {
                (1 until pageCount).chunked(PAGE_PARALLELISM).flatMap { offsets ->
                    offsets.map { offset ->
                        async {
                            pageRequest(offset)
                        }
                    }.awaitAll()
                }
            }

            if (pages.any { it == null }) {
                markUnavailable("シリーズ一覧のページ取得に失敗しました。")
                return null
            }

            val items = buildList {
                addAll(firstBody.items)
                pages.filterNotNull().forEach { addAll(it) }
            }.take(MAX_INDEX_ITEMS)
            val index = buildIndex(items)
            Cache(endpoint, now + CACHE_TTL_MS, index).also { cache = it }
        } catch (e: Exception) {
            markUnavailable("EPGStation のシリーズ一覧に接続できません。", e)
            null
        }
    }

    private suspend fun pageRequest(offset: Int): List<EsSeriesListItem>? {
        val response = api.getSeries(offset = offset, limit = SERIES_PAGE_SIZE)
        return response.takeIf { it.isSuccessful }?.body()?.items
    }

    private fun buildIndex(items: List<EsSeriesListItem>): Map<String, String> {
        val index = mutableMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()

        for (item in items) {
            val seriesTitle = item.title.trim()
            if (seriesTitle.isEmpty()) continue

            for (title in listOf(item.normalizedTitle, seriesTitle)) {
                val key = normalizeTitle(title)
                if (key.isEmpty() || key in ambiguous) continue

                val previous = index[key]
                if (previous != null && previous != seriesTitle) {
                    index.remove(key)
                    ambiguous += key
                } else {
                    index[key] = seriesTitle
                }
            }
        }
        return index
    }

    private suspend fun searchDictionary(keyword: String): String? {
        return try {
            val response = api.getDictionaryWorks(keyword = keyword, limit = 10)
            if (!response.isSuccessful) {
                markUnavailable(
                    "作品辞書の取得に失敗しました。HTTP " + response.code(),
                    permanent = isUnsupportedStatus(response.code())
                )
                return null
            }

            val normalizedKeyword = normalizeTitle(keyword)
            response.body()?.items
                ?.asSequence()
                ?.filter { it.matchType == "exact" }
                ?.firstOrNull { work ->
                    normalizeTitle(work.title) == normalizedKeyword ||
                        normalizeTitle(work.titleKana.orEmpty()) == normalizedKeyword
                }
                ?.title
                ?.takeIf { it.isNotBlank() }
                .also {
                    delay(DICTIONARY_DELAY_MS)
                }
        } catch (e: Exception) {
            markUnavailable("EPGStation の作品辞書に接続できません。", e)
            null
        }
    }

    private fun normalizeTitle(title: String): String {
        return Normalizer.normalize(TitleNormalizer.extractDisplayTitle(title), Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[\\s　、。，．・:：!！?？「」『』【】［］()（）\\[\\]{}<>＜＞『』「」\\-‐‑‒–—〜～_/／]"), "")
    }

    private fun isUnavailable(): Boolean =
        permanentlyUnavailable || System.currentTimeMillis() < unavailableUntil

    // 未実装エンドポイント (フォーク版以外の EPGStation) は再試行しても回復しない
    private fun isUnsupportedStatus(code: Int): Boolean = code == 404 || code == 501

    private fun markUnavailable(
        message: String,
        cause: Exception? = null,
        permanent: Boolean = false
    ) {
        if (permanent) {
            permanentlyUnavailable = true
        } else {
            unavailableUntil = System.currentTimeMillis() + UNAVAILABLE_RETRY_MS
        }
        cache = null
        if (cause == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, cause)
        }
    }
}
