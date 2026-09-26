package com.beeregg2001.komorebi.ui.subtitle

/** ARIB STD-B24 PES データを必要最小限だけ検査する。 */
internal object AribCaptionData {
    fun isManagementPacket(data: ByteArray): Boolean {
        // data_identifier=0x80 は字幕、0x81 は文字スーパー。
        if (data.size < 8 || data[0].toInt() and 0xff != 0x80) return false
        if (data[1].toInt() and 0xff != 0xff) return false

        val pesDataPacketHeaderLength = data[2].toInt() and 0x0f
        val dataGroupStart = 3 + pesDataPacketHeaderLength
        if (dataGroupStart + DATA_GROUP_HEADER_SIZE > data.size) return false

        val dataGroupId = (data[dataGroupStart].toInt() and 0xfc) ushr 2
        return dataGroupId and 0x0f == 0
    }

    private const val DATA_GROUP_HEADER_SIZE = 5
}
