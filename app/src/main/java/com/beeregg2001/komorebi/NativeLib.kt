package com.beeregg2001.komorebi

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

    external fun pushDataBuffer(handle: Long, inputBuffer: ByteBuffer, inputLength: Int)
    external fun popDataBuffer(handle: Long, outputBuffer: ByteBuffer, maxLen: Int): Int
}