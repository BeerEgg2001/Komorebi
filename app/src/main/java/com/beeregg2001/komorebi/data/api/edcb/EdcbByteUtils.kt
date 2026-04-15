package com.beeregg2001.komorebi.data.api.edcb

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EdcbFileData(val name: String, val data: ByteArray)

object EdcbByteUtils {

    // ==========================================
    // ★ 絶対にクラッシュしない安全な読み取り関数群
    // ==========================================

    fun safeGet(buffer: ByteBuffer, endPos: Int): Int =
        if (buffer.position() + 1 <= endPos && buffer.hasRemaining()) buffer.get()
            .toInt() and 0xFF else 0

    fun safeShort(buffer: ByteBuffer, endPos: Int): Int =
        if (buffer.position() + 2 <= endPos && buffer.remaining() >= 2) buffer.short.toInt() and 0xFFFF else 0

    fun safeInt(buffer: ByteBuffer, endPos: Int): Int =
        if (buffer.position() + 4 <= endPos && buffer.remaining() >= 4) buffer.int else 0

    fun safeUint(buffer: ByteBuffer, endPos: Int): Long =
        if (buffer.position() + 4 <= endPos && buffer.remaining() >= 4) buffer.int.toLong() and 0xFFFFFFFFL else 0L

    fun safeLong(buffer: ByteBuffer, endPos: Int): Long =
        if (buffer.position() + 8 <= endPos && buffer.remaining() >= 8) buffer.long else 0L

    fun safeString(buffer: ByteBuffer, endPos: Int): String {
        if (buffer.position() + 4 > endPos || buffer.remaining() < 4) return ""
        val size = buffer.int
        val contentSize = size - 4
        if (contentSize <= 0 || buffer.position() + contentSize > endPos || buffer.remaining() < contentSize) {
            return ""
        }
        val bytes = ByteArray(contentSize)
        buffer.get(bytes)
        val actualSize =
            if (contentSize >= 2 && bytes[contentSize - 2] == 0.toByte() && bytes[contentSize - 1] == 0.toByte()) contentSize - 2 else contentSize
        return String(bytes, 0, actualSize, Charsets.UTF_16LE)
    }

    fun safeSystemTime(buffer: ByteBuffer, endPos: Int): String? {
        if (buffer.position() + 16 > endPos || buffer.remaining() < 16) {
            val skip = (buffer.position() + 16).coerceAtMost(endPos)
            if (skip > buffer.position()) buffer.position(skip)
            return null
        }
        val year = safeShort(buffer, endPos)
        val month = safeShort(buffer, endPos)
        val dayOfWeek = safeShort(buffer, endPos)
        val day = safeShort(buffer, endPos)
        val hour = safeShort(buffer, endPos)
        val minute = safeShort(buffer, endPos)
        val second = safeShort(buffer, endPos)
        val millis = safeShort(buffer, endPos)
        if (year < 1900 || year > 2100) return null
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

    fun readStructIntro(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 4) return 0
        return buffer.int
    }

    fun dateTimeToFileTime(millis: Long): Long = (millis + 11644473600000L) * 10000L
    fun writeInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    fun writeUshort(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    fun writeLong(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    fun writeString(text: String): ByteArray {
        val strBytes = text.toByteArray(Charsets.UTF_16LE)
        val totalSize = 4 + strBytes.size + 2
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.put(strBytes)
        buffer.putShort(0)
        return buffer.array()
    }

    fun writeStringVectorWithVersion(version: Int, strings: List<String>): ByteArray {
        val stringBytesList = strings.map { writeString(it) }
        val vectorTotalSize = 4 + 4 + stringBytesList.sumOf { it.size }
        val buffer = ByteBuffer.allocate(2 + vectorTotalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(version.toShort())
        buffer.putInt(vectorTotalSize)
        buffer.putInt(strings.size)
        for (strBytes in stringBytesList) {
            buffer.put(strBytes)
        }
        return buffer.array()
    }

    fun readInt(buffer: ByteBuffer): Int = if (buffer.remaining() >= 4) buffer.int else 0
    fun readByte(buffer: ByteBuffer): Int =
        if (buffer.remaining() >= 1) buffer.get().toInt() and 0xFF else 0

    fun readUint(buffer: ByteBuffer): Long =
        if (buffer.remaining() >= 4) buffer.int.toLong() and 0xFFFFFFFFL else 0L

    fun readUshort(buffer: ByteBuffer): Int =
        if (buffer.remaining() >= 2) buffer.short.toInt() and 0xFFFF else 0

    fun readLong(buffer: ByteBuffer): Long = if (buffer.remaining() >= 8) buffer.long else 0L
    fun readString(buffer: ByteBuffer): String =
        safeString(buffer, buffer.position() + buffer.remaining())

    fun readSystemTime(buffer: ByteBuffer): String =
        safeSystemTime(buffer, buffer.position() + buffer.remaining()) ?: ""

    inline fun <T> readVector(
        buffer: ByteBuffer,
        parentEndPos: Int,
        reader: (ByteBuffer, Int) -> T
    ): List<T> {
        val list = mutableListOf<T>()
        if (buffer.position() + 8 > parentEndPos || buffer.remaining() < 8) return list
        val vectorStart = buffer.position()
        val vectorSize = buffer.int
        val count = buffer.int
        val vectorEnd = vectorStart + vectorSize
        val actualEnd = if (vectorEnd <= parentEndPos) vectorEnd else parentEndPos

        if (count in 1..200000) {
            for (i in 0 until count) {
                if (buffer.position() >= actualEnd) break
                list.add(reader(buffer, actualEnd))
            }
        }
        if (actualEnd > vectorStart && actualEnd <= buffer.limit()) {
            buffer.position(actualEnd)
        }
        return list
    }

    inline fun <T> readVector(buffer: ByteBuffer, reader: (ByteBuffer) -> T): List<T> =
        readVector(buffer, buffer.limit()) { buf, _ -> reader(buf) }

    fun readFileData(buffer: ByteBuffer): EdcbFileData? {
        try {
            val structSize = readStructIntro(buffer)
            val startPos = buffer.position()
            val endPos = startPos + structSize

            val name = safeString(buffer, endPos)
            if (buffer.position() + 8 > endPos || buffer.remaining() < 8) return null

            val vectorTotalSize = safeInt(buffer, endPos)
            val count = safeInt(buffer, endPos)

            val dataBytes = ByteArray(count)
            if (count > 0 && buffer.position() + count <= endPos && buffer.remaining() >= count) {
                buffer.get(dataBytes)
            }

            if (endPos > startPos && endPos <= buffer.limit()) buffer.position(endPos)
            return EdcbFileData(name, dataBytes)
        } catch (e: Exception) {
            return null
        }
    }

    // ==========================================
    // ★ 録画予約・自動予約用のバイナリ読み取り処理
    // ==========================================

    fun readRecSettingData(buffer: ByteBuffer, parentEndPos: Int): EdcbRecSettingData {
        val startPos = buffer.position()
        val structSize = readStructIntro(buffer)
        val endPos = startPos + structSize
        val actualEnd = if (endPos <= parentEndPos) endPos else parentEndPos

        val data = EdcbRecSettingData(
            recMode = safeGet(buffer, actualEnd),
            priority = safeGet(buffer, actualEnd),
            tuijyuuFlag = safeGet(buffer, actualEnd),
            serviceMode = safeUint(buffer, actualEnd).toInt(),
            pittariFlag = safeGet(buffer, actualEnd),
            batFilePath = safeString(buffer, actualEnd),
            recFolderList = readVector(buffer, actualEnd) { buf, vEnd ->
                val iStart = buf.position()
                val iSize = readStructIntro(buf)
                val cEnd = if (iStart + iSize <= vEnd) iStart + iSize else vEnd
                val info = EdcbRecFileSetInfo(
                    safeString(buf, cEnd),
                    safeString(buf, cEnd),
                    safeString(buf, cEnd)
                )
                safeString(buf, cEnd) // Dummy (recFileName)
                if (cEnd > iStart && cEnd <= buf.limit()) buf.position(cEnd)
                info
            },
            suspendMode = safeGet(buffer, actualEnd),
            rebootFlag = safeGet(buffer, actualEnd),
            useMargineFlag = safeGet(buffer, actualEnd),
            startMargine = safeInt(buffer, actualEnd),
            endMargine = safeInt(buffer, actualEnd),
            continueRecFlag = safeGet(buffer, actualEnd),
            partialRecFlag = safeGet(buffer, actualEnd),
            tunerID = safeUint(buffer, actualEnd).toInt(),
            partialRecFolder = readVector(buffer, actualEnd) { buf, vEnd ->
                val iStart = buf.position()
                val iSize = readStructIntro(buf)
                val cEnd = if (iStart + iSize <= vEnd) iStart + iSize else vEnd
                val info = EdcbRecFileSetInfo(
                    safeString(buf, cEnd),
                    safeString(buf, cEnd),
                    safeString(buf, cEnd)
                )
                safeString(buf, cEnd) // Dummy (recFileName)
                if (cEnd > iStart && cEnd <= buf.limit()) buf.position(cEnd)
                info
            }
        )

        if (actualEnd > startPos && actualEnd <= buffer.limit()) buffer.position(actualEnd)
        return data
    }

    fun readReserveData(buffer: ByteBuffer, parentEndPos: Int): EdcbReserveData {
        val startPos = buffer.position()
        val structSize = readStructIntro(buffer)
        val endPos = startPos + structSize
        val actualEnd = if (endPos <= parentEndPos) endPos else parentEndPos

        val data = EdcbReserveData(
            title = safeString(buffer, actualEnd),
            startTime = safeSystemTime(buffer, actualEnd),
            durationSec = safeUint(buffer, actualEnd).toInt(),
            stationName = safeString(buffer, actualEnd),
            originalNetworkID = safeShort(buffer, actualEnd),
            transportStreamID = safeShort(buffer, actualEnd),
            serviceID = safeShort(buffer, actualEnd),
            eventID = safeShort(buffer, actualEnd),
            comment = safeString(buffer, actualEnd),
            reserveID = safeInt(buffer, actualEnd),
            bPadding = safeGet(buffer, actualEnd),
            overlapMode = safeGet(buffer, actualEnd),
            strPadding = safeString(buffer, actualEnd),
            startTimeEpg = safeSystemTime(buffer, actualEnd),
            recSetting = readRecSettingData(buffer, actualEnd),
            reserveStatus = safeInt(buffer, actualEnd),
            recFileNameList = readVector(buffer, actualEnd) { buf, vEnd -> safeString(buf, vEnd) },
            trailingInt = safeInt(buffer, actualEnd)
        )

        if (actualEnd > startPos && actualEnd <= buffer.limit()) buffer.position(actualEnd)
        return data
    }

    fun readAutoAddData(buffer: ByteBuffer, parentEndPos: Int): EdcbAutoAddData {
        val startPos = buffer.position()
        val structSize = readStructIntro(buffer)
        val endPos = startPos + structSize
        val actualEnd = if (endPos <= parentEndPos) endPos else parentEndPos

        val dataID = safeInt(buffer, actualEnd)

        val sStartPos = buffer.position()
        val sStructSize = readStructIntro(buffer)
        val sActualEnd =
            if (sStartPos + sStructSize <= actualEnd) sStartPos + sStructSize else actualEnd

        var chkDurationMin = 0
        var chkDurationMax = 0
        var andKey = safeString(buffer, sActualEnd)

        val disabled = andKey.startsWith("^!{999}")
        if (disabled) andKey = andKey.removePrefix("^!{999}")
        val caseSensitive = andKey.startsWith("C!{999}")
        if (caseSensitive) andKey = andKey.removePrefix("C!{999}")

        if (andKey.length >= 13 && andKey.startsWith("D!{1") && andKey[12] == '}') {
            val numStr = andKey.substring(4, 12)
            if (numStr.all { it.isDigit() }) {
                val chkDur = numStr.toInt()
                chkDurationMax = chkDur % 10000
                chkDurationMin = (chkDur / 10000) % 10000
                andKey = andKey.substring(13)
            }
        }

        val searchInfo = EdcbSearchInfo(
            andKey = andKey,
            notKey = safeString(buffer, sActualEnd),
            keyDisabled = disabled,
            caseSensitive = caseSensitive,
            regExpFlag = safeInt(buffer, sActualEnd),
            titleOnlyFlag = safeInt(buffer, sActualEnd),
            contentList = readVector(buffer, sActualEnd) { buf, vEnd ->
                val iStart = buf.position()
                val iSize = readStructIntro(buf)
                val cEnd = if (iStart + iSize <= vEnd) iStart + iSize else vEnd

                val cn = safeShort(buf, cEnd)
                val un = safeShort(buf, cEnd)
                val contentNibble = ((cn shr 8) or (cn shl 8)) and 0xFFFF
                val userNibble = ((un shr 8) or (un shl 8)) and 0xFFFF
                val data = EdcbContentData(contentNibble, userNibble)

                if (cEnd > iStart && cEnd <= buf.limit()) buf.position(cEnd)
                data
            },
            dateList = readVector(buffer, sActualEnd) { buf, vEnd ->
                val iStart = buf.position()
                val iSize = readStructIntro(buf)
                val cEnd = if (iStart + iSize <= vEnd) iStart + iSize else vEnd
                val data = EdcbDateData(
                    safeGet(buf, cEnd), safeShort(buf, cEnd), safeShort(buf, cEnd),
                    safeGet(buf, cEnd), safeShort(buf, cEnd), safeShort(buf, cEnd)
                )
                if (cEnd > iStart && cEnd <= buf.limit()) buf.position(cEnd)
                data
            },
            serviceList = readVector(buffer, sActualEnd) { buf, vEnd -> safeLong(buf, vEnd) },
            videoList = readVector(buffer, sActualEnd) { buf, vEnd -> safeShort(buf, vEnd) },
            audioList = readVector(buffer, sActualEnd) { buf, vEnd -> safeShort(buf, vEnd) },
            aimaiFlag = safeGet(buffer, sActualEnd),
            notContetFlag = safeGet(buffer, sActualEnd),
            notDateFlag = safeGet(buffer, sActualEnd),
            freeCAFlag = safeGet(buffer, sActualEnd),
            chkRecEnd = safeGet(buffer, sActualEnd)
        )

        val chkRecDayRaw = safeShort(buffer, sActualEnd)
        searchInfo.chkRecNoService = if (chkRecDayRaw >= 40000) 1 else 0
        searchInfo.chkRecDay = if (chkRecDayRaw >= 40000) chkRecDayRaw % 10000 else chkRecDayRaw
        searchInfo.chkDurationMin = chkDurationMin
        searchInfo.chkDurationMax = chkDurationMax

        if (sActualEnd > sStartPos && sActualEnd <= buffer.limit()) buffer.position(sActualEnd)

        val recSetting = readRecSettingData(buffer, actualEnd)
        val addCount = safeInt(buffer, actualEnd)

        if (actualEnd > startPos && actualEnd <= buffer.limit()) buffer.position(actualEnd)
        return EdcbAutoAddData(dataID, searchInfo, recSetting, addCount)
    }
}