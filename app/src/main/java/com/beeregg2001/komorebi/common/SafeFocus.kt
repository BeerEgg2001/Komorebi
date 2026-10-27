package com.beeregg2001.komorebi.common

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onPlaced
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

// ============================================================================
// フォーカス要求の新しい作法（docs/design/focus_management.md の段階0）
//
// 従来は「固定時間 delay してから requestFocus を1回」という呼び方が大半で、
// 高速機では無駄な待ち時間、低スペック機では待ち足らずという両方の問題を抱えていた。
// 以下の2つは、待つ代わりに「配置されたことを検知してから要求する」ためのもの。
//
// 既存の safeRequestFocus / safeRequestFocusWithRetry は当面そのまま残す。
// 画面単位の移行が済んだら、呼び出しをこちらへ置き換えていく。
// ============================================================================

/**
 * 例外を飲んで一度だけフォーカスを要求する。リトライ中の試行で使う内部用。
 *
 * [safeRequestFocus] と違い失敗をログに残さない。フレーム単位で何度も試す過程での
 * 失敗は異常ではなく、毎回ログを出すと本当の失敗が埋もれてしまうため。
 */
private fun FocusRequester.tryRequestFocus(): Boolean =
    try {
        requestFocus()
    } catch (e: IllegalStateException) {
        // ノードがまだアタッチされていない。次のフレームで再試行する。
        false
    }

/**
 * フォーカス対象が配置されるまで待ってから要求する。
 *
 * **成功すれば待ち時間はゼロ。** 失敗した場合だけ次のフレームまで待って再試行するため、
 * 固定時間の [delay] と違って「速い端末では待たない、遅い端末では待つ」が自動的に成り立つ。
 *
 * composition と layout が終わった次のフレームには [FocusRequester] がアタッチされているので、
 * 通常は1〜2フレームで成功する。[maxFrames] は保険であり、ここに到達するのは
 * 対象が条件付きで描画されていない（そもそもフォーカスできない）場合がほとんど。
 *
 * 注意: [withFrameNanos] は coroutine context に `MonotonicFrameClock` が必要なため、
 * `LaunchedEffect` など Compose が提供するスコープから呼ぶこと。`viewModelScope` では動かない。
 *
 * @param maxFrames 最大で何フレーム待つか。既定の10はおよそ160ms相当。
 * @param shouldContinue 待っている間に状況が変わったら中断するための判定。
 *   画面が閉じられた、別の要素へフォーカスが移った等の場合に false を返す。
 * @return フォーカス要求が成功したら true。中断・全試行失敗なら false。
 *   呼び出し側は戻り値を見て別のフォーカス先へフォールバックできる。
 */
suspend fun FocusRequester.requestFocusWhenReady(
    tag: String = "KomorebiFocus",
    maxFrames: Int = 10,
    shouldContinue: () -> Boolean = { true }
): Boolean {
    repeat(maxFrames) { attempt ->
        if (!shouldContinue()) return false
        if (tryRequestFocus()) {
            if (attempt > 0) {
                Log.i(tag, "フォーカスを取得しました（${attempt + 1}フレーム目）")
            }
            return true
        }
        // 次のフレームまで待つ。delay と違い端末の描画ペースに追従する。
        withFrameNanos { }
    }
    Log.w(tag, "${maxFrames}フレーム待ってもフォーカスを取得できませんでした")
    return false
}

/**
 * 配置されたタイミングで一度だけフォーカスを要求する [Modifier]。
 *
 * [requestFocusWhenReady] と違いリトライが不要になる。`onPlaced` はレイアウトが確定した
 * 時点で呼ばれ、そこでは [FocusRequester] が必ずアタッチされているため。
 * 「画面を開いたら必ずここへフォーカスを当てたい」という用途はこちらを優先する。
 *
 * `onPlaced` は再レイアウトのたびに呼ばれるので、[key] が変わらない限り一度しか要求しない。
 * タブ切替などで当て直したい場合は、[key] に切り替えの識別子（タブのインデックス等）を渡す。
 *
 * @param enabled false の間は要求しない。条件付きで初期フォーカスを当てたい場合に使う。
 * @param key この値が変わると「まだ要求していない」状態に戻り、改めて1回だけ要求する。
 */
@Composable
fun Modifier.requestFocusWhenPlaced(
    focusRequester: FocusRequester,
    enabled: Boolean = true,
    key: Any? = Unit,
    tag: String = "KomorebiFocus"
): Modifier {
    var requested by remember(key) { mutableStateOf(false) }
    return this.onPlaced {
        if (enabled && !requested) {
            requested = true
            if (!focusRequester.tryRequestFocus()) {
                // onPlaced 時点で失敗するのは、対象が focusable でないか既に
                // 別の要素がフォーカスを持っている場合。次の配置で再試行させる。
                requested = false
                Log.w(tag, "配置時のフォーカス要求が失敗しました")
            }
        }
    }
}
