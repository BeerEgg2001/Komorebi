package com.beeregg2001.komorebi

import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import java.nio.ByteBuffer

class NativeLib {
    companion object {
        init {
            System.loadLibrary("komorebi-native")
        }
    }

    // フィルタインスタンスの生成（引数を配列で渡す）
    external fun openFilter(args: Array<String>): Long

    // Direct ByteBuffer を使った高速処理
    external fun processDataBuffer(
        handle: Long,
        inputBuffer: ByteBuffer,
        inputLength: Int,
        outputBuffer: ByteBuffer
    ): Int

    // インスタンスの破棄
    external fun closeFilter(handle: Long)

    // 録画TS直接再生のシークでフィルタが作り直される際、PID構成とPTS-PCR学習値を
    // 引き継ぐための状態退避/復元(servicefilter.hpp の CServiceFilter::State 参照)。
    external fun exportFilterState(handle: Long): LongArray
    external fun importFilterState(handle: Long, state: LongArray)

    external fun pushDataBuffer(handle: Long, inputBuffer: ByteBuffer, inputLength: Int)
    external fun popDataBuffer(handle: Long, outputBuffer: ByteBuffer, maxLen: Int): Int

    external fun openCaptionDecoder(): Long
    external fun decodeCaption(handle: Long, data: ByteArray, ptsMs: Long): NativeCaptionCue?
    external fun getCaptionLanguageCodes(handle: Long): IntArray
    external fun switchCaptionLanguage(handle: Long, languageId: Int)
    external fun flushCaptionDecoder(handle: Long)
    external fun closeCaptionDecoder(handle: Long)
}
