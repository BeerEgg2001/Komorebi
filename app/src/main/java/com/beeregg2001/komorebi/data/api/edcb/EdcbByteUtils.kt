package com.beeregg2001.komorebi.data.api.edcb

import java.nio.ByteBuffer
import java.nio.ByteOrder

object EdcbByteUtils {

    fun dateTimeToFileTime(millis: Long): Long {
        return (millis + 11644473600000L) * 10000L
    }

    fun writeInt(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    fun writeUshort(value: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
            .array()
    }

    fun writeLong(value: Long): ByteArray {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }

    fun writeString(text: String): ByteArray {
        val strBytes = text.toByteArray(Charsets.UTF_16LE)
        val totalSize = 4 + strBytes.size + 2
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.put(strBytes)
        buffer.putShort(0)
        return buffer.array()
    }

    fun readStructIntro(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 4) return 0
        return buffer.int
    }

    fun readInt(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 4) return 0
        return buffer.int
    }

    fun readByte(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 1) return 0
        return buffer.get().toInt() and 0xFF
    }

    fun readUint(buffer: ByteBuffer): Long {
        if (buffer.remaining() < 4) return 0L
        return buffer.int.toLong() and 0xFFFFFFFFL
    }

    fun readUshort(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 2) return 0
        return buffer.short.toInt() and 0xFFFF
    }

    fun readLong(buffer: ByteBuffer): Long {
        if (buffer.remaining() < 8) return 0L
        return buffer.long
    }

    fun readString(buffer: ByteBuffer): String {
        if (buffer.remaining() < 4) return ""
        val totalSize = buffer.int
        val contentSize = totalSize - 4

        if (contentSize <= 0) return ""
        if (buffer.remaining() < contentSize) return ""

        val strBytes = ByteArray(contentSize)
        buffer.get(strBytes)

        val actualStringSize =
            if (contentSize >= 2 && strBytes[contentSize - 2] == 0.toByte() && strBytes[contentSize - 1] == 0.toByte()) {
                contentSize - 2
            } else {
                contentSize
            }

        return String(strBytes, 0, actualStringSize, Charsets.UTF_16LE)
    }

    inline fun <T> readVector(buffer: ByteBuffer, reader: (ByteBuffer) -> T): List<T> {
        if (buffer.remaining() < 8) return emptyList()
        val startPos = buffer.position()
        val totalSize = readInt(buffer)
        val count = readInt(buffer)

        val list = mutableListOf<T>()
        if (count in 1..200000) { // 万が一の大規模データにも対応
            for (i in 0 until count) {
                list.add(reader(buffer))
            }
        }

        val endPos = startPos + totalSize
        if (endPos > startPos && endPos <= buffer.limit()) {
            buffer.position(endPos)
        }
        return list
    }

    fun readSystemTime(buffer: ByteBuffer): String {
        if (buffer.remaining() < 16) return ""
        val year = readUshort(buffer)
        val month = readUshort(buffer)
        val dayOfWeek = readUshort(buffer)
        val day = readUshort(buffer)
        val hour = readUshort(buffer)
        val minute = readUshort(buffer)
        val second = readUshort(buffer)
        val milliseconds = readUshort(buffer)
        if (year == 0xFFFF || year == 0) return ""
        return String.format(
            "%04d/%02d/%02d %02d:%02d:%02d",
            year,
            month,
            day,
            hour,
            minute,
            second
        )
    }
}