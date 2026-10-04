package com.beeregg2001.komorebi.data.model

/** KonomiTV に要求する映像エンコード方式。 */
data class StreamEncoding(
    val label: String,
    val value: String,
    val isRawTs: Boolean = false // 無変換のMPEG-TSを直接再生する。
) {
    companion object {
        val DEFAULT_ENCODINGS = listOf(
            // ★ 修正: サーバー側で再エンコードせず、MPEG-TSをtsreadex経由でそのまま配信する画質。
            // クライアント側でtsreadex相当の処理(NativeLib)を通す必要があるためisRawTs=true。
            // ラベルは以前"オリジナル (MPEG-2)"だったが、BS4K(HEVC)の録画・ライブでもこの画質を
            // 選べるようになったため、コーデック非依存の表記へ変更した
            // (映像はMPEG-2 / H.264 / H.265のいずれもあり得る)。
            StreamEncoding("オリジナル (無変換)", "original", isRawTs = true),
            StreamEncoding("H.264/AVC (標準)", "h264"),
            StreamEncoding("H.265/HEVC (通信節約モード)", "h265")
        )

        fun available(originalAvailable: Boolean): List<StreamEncoding> =
            DEFAULT_ENCODINGS.filter { originalAvailable || !it.isRawTs }

        /**
         * 文字列からエンコード方式を取得する（利用可能なリストから検索）
         */
        fun fromValue(
            value: String,
            availableList: List<StreamEncoding> = DEFAULT_ENCODINGS
        ): StreamEncoding {
            return availableList.find { it.value.equals(value, ignoreCase = true) }
                ?: availableList.firstOrNull()
                ?: DEFAULT_ENCODINGS.first()
        }
    }
}
