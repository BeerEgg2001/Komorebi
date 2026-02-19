package com.beeregg2001.komorebi.common

import android.util.Log
import androidx.compose.ui.focus.FocusRequester

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