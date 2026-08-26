package com.beeregg2001.komorebi.common

import android.util.Log
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * FocusRequesterがノードにアタッチされていない、または初期化されていない状態での
 * requestFocus() 呼び出しによるクラッシュを防止するための拡張関数です。
 */
fun FocusRequester.safeRequestFocus(tag: String = "KomorebiFocus"): Boolean {
    return try {
        if (this.requestFocus()) {
            true
        } else {
            Log.w(tag, "FocusRequester.requestFocus() returned false. Focus target is not ready.")
            false
        }
    } catch (e: IllegalStateException) {
        // ノードがアタッチされていない場合は警告をログに出力し、クラッシュを回避します
        Log.w(tag, "FocusRequester is not initialized or not attached to the layout. Ignoring request.")
        false
    }
}

/**
 * ★追加: 非同期処理(LaunchedEffect内)で使用するための強化版。
 * ノードがアタッチされるまで一定回数リトライします。
 * 10,000件規模のリストで描画が遅延する場合に非常に有効です。
 *
 * 戻り値: フォーカス要求が成功した場合は true。中断・全失敗の場合は false。
 * 呼び出し側は戻り値を見て、別のフォーカス先へフォールバックできます。
 */
suspend fun FocusRequester.safeRequestFocusWithRetry(
    tag: String = "KomorebiFocus",
    maxRetries: Int = 5,
    delayMillis: Long = 100,
    shouldContinue: () -> Boolean = { true }
): Boolean {
    for (i in 0 until maxRetries) {
        if (!shouldContinue()) return false
        try {
            if (this.requestFocus()) {
                if (i > 0) Log.i(tag, "Focus successfully attached after ${i + 1} attempts.")
                return true
            }
            if (i == maxRetries - 1) {
                Log.e(tag, "Final attempt failed: requestFocus() returned false after $maxRetries attempts.")
            } else {
                Log.w(tag, "requestFocus() returned false, retrying... (${i + 1}/$maxRetries)")
                delay(delayMillis)
            }
        } catch (e: IllegalStateException) {
            if (i == maxRetries - 1) {
                Log.e(tag, "Final attempt failed: FocusRequester not attached after $maxRetries attempts.")
            } else {
                Log.w(tag, "FocusRequester not attached, retrying... (${i + 1}/$maxRetries)")
                delay(delayMillis)
            }
        }
    }
    return false
}
