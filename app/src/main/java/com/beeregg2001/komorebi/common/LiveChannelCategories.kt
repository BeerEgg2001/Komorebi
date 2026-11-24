package com.beeregg2001.komorebi.common

/** ライブ一覧とミニチャンネル一覧に共通する既存カテゴリ。 */
object LiveChannelCategories {
    val orderedTypes = listOf("GR", "BS", "CS", "BS4K", "SKY")

    fun label(type: String): String = when (type) {
        "GR" -> "地デジ"
        "BS" -> "BS"
        "CS" -> "CS"
        "BS4K" -> "BS4K"
        "SKY" -> "スカパー"
        else -> type
    }
}
