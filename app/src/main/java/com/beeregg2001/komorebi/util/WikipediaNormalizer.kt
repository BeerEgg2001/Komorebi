package com.beeregg2001.komorebi.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WikipediaのAPIを利用して、番組名の表記揺れを吸収し、
 * 正式名称（標準名）を取得するための「名寄せ（シリーズグループ化）」ユーティリティです。
 */
object WikipediaNormalizer {

    // WikipediaのAPI利用ポリシーに従い、連絡先を含めたUser-Agentを指定します
    private const val USER_AGENT = "KomorebiApp/0.7.0-beta (tamago8168@gmail.com) Android"

    /**
     * 与えられた検索キーワード（番組名）から、Wikipedia上の正式な記事タイトルを取得します。
     * ネットワーク通信を行うため、IOディスパッチャで実行されます。
     */
    suspend fun getCanonicalTitle(keyword: String): String? = withContext(Dispatchers.IO) {
        // 1. 広範なジャンルや特番になりやすいキーワードは、Wikipedia検索をスキップして固定のカテゴリ名に丸めます
        if (keyword.contains("オリンピック") || keyword.contains("五輪")) return@withContext "オリンピック"
        if (keyword.contains("ワールドカップ") || keyword.contains("W杯")) return@withContext "ワールドカップ"
        if (keyword.contains("ニュース") || keyword.contains("報道")) return@withContext "ニュース・報道"

        // 2. 誤ヒットを防ぐため、2文字以下の短すぎるキーワードは除外します
        if (keyword.length <= 2) return@withContext null

        // 3. まずはそのままのキーワードでWikipediaを検索します
        var result = fetchOpenSearch(keyword)

        // 4. ヒットせず、かつキーワードが長い（7文字以上）場合、
        // サブタイトルなどが付着していると推測し、先頭6文字だけで再検索を試みます
        if (result == null && keyword.length > 6) {
            val truncatedKeyword = keyword.substring(0, 6)
            result = fetchOpenSearch(truncatedKeyword)
        }

        // 5. 検索結果が得られても、元のキーワードと全く無関係な記事がヒットするのを防ぐための妥当性チェック
        if (result != null && !isMatchValid(keyword, result)) {
            return@withContext null
        }
        return@withContext result
    }

    /**
     * Wikipediaの検索結果が、本当に探していた番組名と関連しているかを検証します。
     */
    private fun isMatchValid(originalKeyword: String, wikiResult: String): Boolean {
        // 条件A: どちらか一方が、もう一方の文字列を完全に包含しているか（大文字小文字を無視）
        if (originalKeyword.contains(wikiResult, ignoreCase = true) ||
            wikiResult.contains(originalKeyword, ignoreCase = true)
        ) {
            return true
        }

        // 条件B: 先頭の文字列（最大3文字）が一致しているか。
        // （「名探偵コナン」と「名探偵コナン (アニメ)」のようなケースを救済します）
        val prefixLen = minOf(3, minOf(originalKeyword.length, wikiResult.length))
        if (prefixLen >= 2 && originalKeyword.take(prefixLen) == wikiResult.take(prefixLen)) {
            return true
        }

        // どちらも満たさない場合は、無関係な記事が誤ヒットしたとみなして除外します
        return false
    }

    /**
     * Wikipedia OpenSearch API にHTTPリクエストを送信し、検索結果の1件目を取得します。
     */
    private fun fetchOpenSearch(query: String): String? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // サジェスト用に提供されている軽量なOpenSearch APIを利用します（最大1件取得）
            val urlString =
                "https://ja.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=1&format=json"
            val url = URL(urlString)

            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 5000 // 5秒でタイムアウト
                readTimeout = 5000
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseString)

                // OpenSearch APIのレスポンス形式:
                // [ "検索キーワード", ["結果タイトル1"], ["概要1"], ["URL1"] ]
                // jsonArray[1] にタイトルの配列が格納されています
                if (jsonArray.length() > 1) {
                    val titlesArray = jsonArray.getJSONArray(1)
                    if (titlesArray.length() > 0) {
                        return titlesArray.getString(0) // 最も関連性の高い1件目を返す
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}