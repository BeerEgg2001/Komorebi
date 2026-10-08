package com.beeregg2001.komorebi.data.model

/**
 * プロジェクト全体で共通の画質定義（動的リスト対応のためData Classに変更）
 */
data class StreamQuality(
    val label: String,
    val value: String,
    // EDCB / EPGStation などの画質・配信プロファイルにおいて、ダイレクト再生を判定するためのフラグ。
    // KonomiTV では画質とエンコード方式を分離しているため、無変換の判定には
    // この値ではなく [StreamEncoding.isRawTs] を使う。
    val isRawTs: Boolean = false,
    val konomiTvHevcValue: String? = null // KonomiTVのHEVCエンコード用API画質値
) {
    /** 選択されたエンコード方式に対応する KonomiTV API の画質パラメータ。 */
    fun getKonomiTvValue(encoding: StreamEncoding): String {
        if (encoding.isRawTs) return "original"
        return when (encoding.value) {
            "h265" -> konomiTvHevcValue ?: value
            else -> value
        }
    }

    companion object {
        // KonomiTVなどのバックエンド用のデフォルト（固定）リスト
        val DEFAULT_QUALITIES = listOf(
            StreamQuality("1080p (60fps)", "1080p-60fps", konomiTvHevcValue = "1080p-60fps-hevc"),
            StreamQuality("1080p", "1080p", konomiTvHevcValue = "1080p-hevc"),
            StreamQuality("810p", "810p", konomiTvHevcValue = "810p-hevc"),
            StreamQuality("720p", "720p", konomiTvHevcValue = "720p-hevc"),
            StreamQuality("540p", "540p", konomiTvHevcValue = "540p-hevc"),
            StreamQuality("480p", "480p", konomiTvHevcValue = "480p-hevc"),
            StreamQuality("360p", "360p", konomiTvHevcValue = "360p-hevc"),
            StreamQuality("240p", "240p", konomiTvHevcValue = "240p-hevc")
        )

        /**
         * 文字列から画質型を取得する（利用可能なリストから検索）
         */
        fun fromValue(
            value: String,
            availableList: List<StreamQuality> = DEFAULT_QUALITIES
        ): StreamQuality {
            return availableList.find { it.value == value }
                ?: availableList.firstOrNull()
                ?: DEFAULT_QUALITIES.first()
        }
    }
}