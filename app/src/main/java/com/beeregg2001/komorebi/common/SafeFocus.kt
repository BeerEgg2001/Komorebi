package com.beeregg2001.komorebi.common

import android.util.Log
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * FocusRequesterがノードにアタッチされていない、または初期化されていない状態での
 * requestFocus() 呼び出しによるクラッシュを防止するための拡張関数です。
 */
fun FocusRequester.safeRequestFocus(tag: String = "KomorebiFocus") {
    try {
        this.requestFocus()
    } catch (e: IllegalStateException) {
        // ノードがアタッチされていない場合は警告をログに出力し、クラッシュを回避します
        Log.w(tag, "FocusRequester is not initialized or not attached to the layout. Ignoring request.")
    }
}

/**
 * ★追加: 非同期処理(LaunchedEffect内)で使用するための強化版。
 * ノードがアタッチされるまで一定回数リトライします。
 * 10,000件規模のリストで描画が遅延する場合に非常に有効です。
 */
suspend fun FocusRequester.safeRequestFocusWithRetry(
    tag: String = "KomorebiFocus",
    maxRetries: Int = 5,
    delayMillis: Long = 100
) {
    for (i in 0 until maxRetries) {
        try {
            this.requestFocus()
            if (i > 0) Log.i(tag, "Focus successfully attached after ${i + 1} attempts.")
            return
        } catch (e: IllegalStateException) {
            if (i == maxRetries - 1) {
                Log.e(tag, "Final attempt failed: FocusRequester not attached after $maxRetries attempts.")
            } else {
                Log.w(tag, "FocusRequester not attached, retrying... (${i + 1}/$maxRetries)")
                delay(delayMillis)
            }
        }
    }
}