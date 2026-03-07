package com.beeregg2001.komorebi.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WikipediaNormalizer {

    private const val USER_AGENT = "KomorebiApp/0.7.0-beta (tamago8168@gmail.com) Android"

    suspend fun getCanonicalTitle(keyword: String): String? = withContext(Dispatchers.IO) {
        if (keyword.contains("オリンピック") || keyword.contains("五輪")) return@withContext "オリンピック"
        if (keyword.contains("ワールドカップ") || keyword.contains("W杯")) return@withContext "ワールドカップ"
        if (keyword.contains("ニュース") || keyword.contains("報道")) return@withContext "ニュース・報道"

        if (keyword.length <= 2) return@withContext null

        // 第1波：まずはそのままのキーワードで検索
        var result = fetchOpenSearch(keyword)

        // 第2波：見つからなかった場合、かつ文字数が長い場合は、サブタイトルが邪魔しているとみなして先頭6文字で再検索（豊臣兄弟などの救済）
        if (result == null && keyword.length > 6) {
            val truncatedKeyword = keyword.substring(0, 6)
            result = fetchOpenSearch(truncatedKeyword)
        }

        return@withContext result
    }

    /**
     * 実際にWikipedia opensearch APIを叩くプライベート関数
     */
    private fun fetchOpenSearch(query: String): String? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString =
                "https://ja.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=1&format=json"
            val url = URL(urlString)

            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseString)

                if (jsonArray.length() > 1) {
                    val titlesArray = jsonArray.getJSONArray(1)
                    if (titlesArray.length() > 0) {
                        return titlesArray.getString(0)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}