package com.beeregg2001.komorebi.data.api.edcb

import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EdcbFileData(val name: String, val data: ByteArray)

object EdcbByteUtils {

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

    fun writeStringVector(strings: List<String>): ByteArray {
        val stringBytesList = strings.map { writeString(it) }
        val vectorTotalSize = 4 + 4 + stringBytesList.sumOf { it.size }
        val buffer = ByteBuffer.allocate(vectorTotalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(vectorTotalSize)
        buffer.putInt(strings.size)
        for (strBytes in stringBytesList) {
            buffer.put(strBytes)
        }
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

    // ==========================================
    // ★ 送信用バイナリ組み立て (エンコード)
    // ==========================================

    fun writeIntVector(values: List<Int>): ByteArray {
        val totalSize = 4 + 4 + (values.size * 4)
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.putInt(values.size)
        for (v in values) buffer.putInt(v)
        return buffer.array()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun writeSystemTime(edcbTime: String?): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        if (edcbTime.isNullOrBlank()) {
            buffer.putShort(1970); buffer.putShort(1); buffer.putShort(4); buffer.putShort(1)
            buffer.putShort(9); buffer.putShort(0); buffer.putShort(0); buffer.putShort(0)
            return buffer.array()
        }
        try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            val ldt = java.time.LocalDateTime.parse(edcbTime, formatter)
            val winDayOfWeek = (ldt.dayOfWeek.value % 7).toShort()

            buffer.putShort(ldt.year.toShort())
            buffer.putShort(ldt.monthValue.toShort())
            buffer.putShort(winDayOfWeek)
            buffer.putShort(ldt.dayOfMonth.toShort())
            buffer.putShort(ldt.hour.toShort())
            buffer.putShort(ldt.minute.toShort())
            buffer.putShort(ldt.second.toShort())
            buffer.putShort(0)
            return buffer.array()
        } catch (e: Exception) {
            buffer.clear()
            buffer.putShort(1970); buffer.putShort(1); buffer.putShort(4); buffer.putShort(1)
            buffer.putShort(9); buffer.putShort(0); buffer.putShort(0); buffer.putShort(0)
            return buffer.array()
        }
    }

    private fun writeRecFileSetInfo(v: EdcbRecFileSetInfo): ByteArray {
        val f1 = writeString(v.recFolder)
        val f2 = writeString(v.writePlugIn)
        val f3 = writeString(v.recNamePlugIn)
        val f4 = writeString("") // Dummy for recFileName
        val totalSize = 4 + f1.size + f2.size + f3.size + f4.size

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.put(f1); buffer.put(f2); buffer.put(f3); buffer.put(f4)
        return buffer.array()
    }

    private fun writeRecFileSetInfoVector(list: List<EdcbRecFileSetInfo>): ByteArray {
        val bytesList = list.map { writeRecFileSetInfo(it) }
        val totalSize = 4 + 4 + bytesList.sumOf { it.size }
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.putInt(list.size)
        for (b in bytesList) buffer.put(b)
        return buffer.array()
    }

    fun writeRecSettingData(v: EdcbRecSettingData): ByteArray {
        val fBat = writeString(v.batFilePath)
        val fFolderList = writeRecFileSetInfoVector(v.recFolderList)
        val fPartialFolderList = writeRecFileSetInfoVector(v.partialRecFolder)

        val totalSize =
            4 + 1 + 1 + 1 + 4 + 1 + fBat.size + fFolderList.size + 1 + 1 + 1 + 4 + 4 + 1 + 1 + 4 + fPartialFolderList.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.put(v.recMode.toByte())
        buffer.put(v.priority.toByte())
        buffer.put(v.tuijyuuFlag.toByte())
        buffer.putInt(v.serviceMode)
        buffer.put(v.pittariFlag.toByte())
        buffer.put(fBat)
        buffer.put(fFolderList)
        buffer.put(v.suspendMode.toByte())
        buffer.put(v.rebootFlag.toByte())
        buffer.put(v.useMargineFlag.toByte())
        buffer.putInt(v.startMargine)
        buffer.putInt(v.endMargine)
        buffer.put(v.continueRecFlag.toByte())
        buffer.put(v.partialRecFlag.toByte())
        buffer.putInt(v.tunerID)
        buffer.put(fPartialFolderList)

        return buffer.array()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun writeReserveData(v: EdcbReserveData): ByteArray {
        val fTitle = writeString(v.title)
        val fStartTime = writeSystemTime(v.startTime)
        val fStationName = writeString(v.stationName)
        val fComment = writeString(v.comment)
        val fStrPadding = writeString("")
        val fStartTimeEpg = writeSystemTime(v.startTimeEpg ?: v.startTime)
        val fRecSetting = writeRecSettingData(v.recSetting)
        val fRecFileNameList = writeStringVector(v.recFileNameList)

        val totalSize =
            4 + fTitle.size + 16 + 4 + fStationName.size + 8 + fComment.size + 4 + 1 + 1 + fStrPadding.size + 16 + fRecSetting.size + 4 + fRecFileNameList.size + 4
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(totalSize)
        buffer.put(fTitle)
        buffer.put(fStartTime)
        buffer.putInt(v.durationSec)
        buffer.put(fStationName)
        buffer.putShort(v.originalNetworkID.toShort())
        buffer.putShort(v.transportStreamID.toShort())
        buffer.putShort(v.serviceID.toShort())
        buffer.putShort(v.eventID.toShort())
        buffer.put(fComment)
        buffer.putInt(v.reserveID)
        buffer.put(0.toByte())
        buffer.put(v.overlapMode.toByte())
        buffer.put(fStrPadding)
        buffer.put(fStartTimeEpg)
        buffer.put(fRecSetting)
        buffer.putInt(0)
        buffer.put(fRecFileNameList)
        buffer.putInt(0)

        return buffer.array()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun writeReserveDataVector(list: List<EdcbReserveData>): ByteArray {
        val bytesList = list.map { writeReserveData(it) }
        val totalSize = 4 + 4 + bytesList.sumOf { it.size }
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.putInt(list.size)
        for (b in bytesList) buffer.put(b)
        return buffer.array()
    }

    // ★修正: ContentData は単なる Short x2 ではなく構造体（サイズヘッダ付き）にする
    private fun writeContentData(c: EdcbContentData): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8) // struct header size
        val rCn = ((c.contentNibble shr 8) or (c.contentNibble shl 8)) and 0xFFFF
        val rUn = ((c.userNibble shr 8) or (c.userNibble shl 8)) and 0xFFFF
        buffer.putShort(rCn.toShort())
        buffer.putShort(rUn.toShort())
        return buffer.array()
    }

    private fun writeContentDataVector(list: List<EdcbContentData>): ByteArray {
        val bytesList = list.map { writeContentData(it) }
        val totalSize = 4 + 4 + bytesList.sumOf { it.size }
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.putInt(list.size)
        for (b in bytesList) buffer.put(b)
        return buffer.array()
    }

    // ★修正: DateData も構造体（サイズヘッダ付き）にする
    private fun writeDateData(d: EdcbDateData): ByteArray {
        val buffer = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(14) // struct header size
        buffer.put(d.startDayOfWeek.toByte())
        buffer.putShort(d.startHour.toShort())
        buffer.putShort(d.startMin.toShort())
        buffer.put(d.endDayOfWeek.toByte())
        buffer.putShort(d.endHour.toShort())
        buffer.putShort(d.endMin.toShort())
        return buffer.array()
    }

    private fun writeDateDataVector(list: List<EdcbDateData>): ByteArray {
        val bytesList = list.map { writeDateData(it) }
        val totalSize = 4 + 4 + bytesList.sumOf { it.size }
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.putInt(list.size)
        for (b in bytesList) buffer.put(b)
        return buffer.array()
    }

    // ★修正: AutoAdd の検索条件エンコード (サイズ計算を厳密に修正)
    fun writeSearchKeyInfo(v: EdcbSearchInfo): ByteArray {
        var andKey = v.andKey
        if (v.chkDurationMax > 0 || v.chkDurationMin > 0) {
            val chkDuration = (v.chkDurationMin * 10000 + v.chkDurationMax) % 100000000
            andKey = String.format("D!{1%08d}", chkDuration) + andKey
        }
        if (v.caseSensitive) andKey = "C!{999}$andKey"
        if (v.keyDisabled) andKey = "^!{999}$andKey"

        val fAndKey = writeString(andKey)
        val fNotKey = writeString(v.notKey)
        val fContentList = writeContentDataVector(v.contentList)
        val fDateList = writeDateDataVector(v.dateList)

        val tsService = 4 + 4 + (v.serviceList.size * 8)
        val bService = ByteBuffer.allocate(tsService).order(ByteOrder.LITTLE_ENDIAN)
        bService.putInt(tsService); bService.putInt(v.serviceList.size)
        v.serviceList.forEach { bService.putLong(it) }

        val tsVideo = 4 + 4 + (v.videoList.size * 2)
        val bVideo = ByteBuffer.allocate(tsVideo).order(ByteOrder.LITTLE_ENDIAN)
        bVideo.putInt(tsVideo); bVideo.putInt(v.videoList.size)
        v.videoList.forEach { bVideo.putShort(it.toShort()) }

        val tsAudio = 4 + 4 + (v.audioList.size * 2)
        val bAudio = ByteBuffer.allocate(tsAudio).order(ByteOrder.LITTLE_ENDIAN)
        bAudio.putInt(tsAudio); bAudio.putInt(v.audioList.size)
        v.audioList.forEach { bAudio.putShort(it.toShort()) }

        // 4(size) + fAndKey + fNotKey + 4(reg) + 4(title) + fContentList + fDateList + tsService + tsVideo + tsAudio
        // + aimai(1) + notContent(1) + notDate(1) + freeCA(1) + chkRecEnd(1) + chkRecDay(2)
        val totalSize =
            4 + fAndKey.size + fNotKey.size + 4 + 4 + fContentList.size + fDateList.size + tsService + tsVideo + tsAudio + 1 + 1 + 1 + 1 + 1 + 2

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.put(fAndKey)
        buffer.put(fNotKey)
        buffer.putInt(v.regExpFlag)
        buffer.putInt(v.titleOnlyFlag)
        buffer.put(fContentList)
        buffer.put(fDateList)
        buffer.put(bService.array())
        buffer.put(bVideo.array())
        buffer.put(bAudio.array())
        buffer.put(v.aimaiFlag.toByte())
        buffer.put(v.notContetFlag.toByte())
        buffer.put(v.notDateFlag.toByte())
        buffer.put(v.freeCAFlag.toByte())
        buffer.put(v.chkRecEnd.toByte())

        val chkRecDay = if (v.chkRecNoService != 0) (v.chkRecDay % 10000) + 40000 else v.chkRecDay
        buffer.putShort(chkRecDay.toShort())

        return buffer.array()
    }

    fun writeAutoAddData(v: EdcbAutoAddData): ByteArray {
        val fSearchInfo = writeSearchKeyInfo(v.searchInfo)
        val fRecSetting = writeRecSettingData(v.recSetting)

        val totalSize = 4 + 4 + fSearchInfo.size + fRecSetting.size + 4
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.putInt(v.dataID)
        buffer.put(fSearchInfo)
        buffer.put(fRecSetting)
        buffer.putInt(v.addCount)

        return buffer.array()
    }

    fun writeAutoAddDataVector(list: List<EdcbAutoAddData>): ByteArray {
        val bytesList = list.map { writeAutoAddData(it) }
        val totalSize = 4 + 4 + bytesList.sumOf { it.size }
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalSize)
        buffer.putInt(list.size)
        for (b in bytesList) buffer.put(b)
        return buffer.array()
    }
}