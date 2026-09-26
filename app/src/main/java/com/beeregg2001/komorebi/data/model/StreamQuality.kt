package com.beeregg2001.komorebi.data.model

/**
 * プロジェクト全体で共通の画質定義（動的リスト対応のためData Classに変更）
 */
data class StreamQuality(
    val label: String,
    val value: String,
    val isRawTs: Boolean = false // 生TS(TS-Live!)かどうかを判定するフラグ
) {
    companion object {
        // KonomiTVなどのバックエンド用のデフォルト（固定）リスト
        val DEFAULT_QUALITIES = listOf(
            // ★ 修正: サーバー側で再エンコードせず、MPEG-TSをtsreadex経由でそのまま配信する画質。
            // クライアント側でtsreadex相当の処理(NativeLib)を通す必要があるためisRawTs=true。
            // ラベルは以前"オリジナル (MPEG-2)"だったが、BS4K(HEVC)の録画・ライブでもこの画質を
            // 選べるようになったため、コーデック非依存の表記へ変更した
            // (映像はMPEG-2 / H.264 / H.265のいずれもあり得る)。
            StreamQuality("オリジナル (無変換)", "original", isRawTs = true),
            StreamQuality("1080p (60fps)", "1080p-60fps"),
            StreamQuality("1080p", "1080p"),
            StreamQuality("810p", "810p"),
            StreamQuality("720p", "720p"),
            StreamQuality("540p", "540p"),
            StreamQuality("480p", "480p"),
            StreamQuality("360p", "360p"),
            StreamQuality("240p", "240p")
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