package com.beeregg2001.komorebi.ui.subtitle

import java.util.PriorityQueue

/** プレイヤーのメディア時刻だけを基準に字幕を進める時間線。 */
internal class NativeCaptionTimeline {
    private data class QueuedCue(
        val cue: NativeCaptionCue,
        val sequence: Long
    )

    private val pending = PriorityQueue<QueuedCue>(
        compareBy<QueuedCue> { it.cue.ptsMs }.thenBy { it.sequence }
    )
    private var sequence = 0L
    private var lastOfferedPtsMs: Long? = null
    private var lastPositionMs: Long? = null
    private var visibleCue: NativeCaptionCue? = null

    /** cue を待機列へ追加し、PTS が巻き戻った場合だけ古い時間線を破棄する。 */
    fun offer(cue: NativeCaptionCue) {
        val ptsRegressed = lastOfferedPtsMs?.let { cue.ptsMs < it } == true
        if (ptsRegressed) reset()
        pending += QueuedCue(cue, sequence++)
        lastOfferedPtsMs = cue.ptsMs
    }

    /** 現在のメディア位置に対応する表示 cue を返す。 */
    fun advanceTo(positionMs: Long): NativeCaptionCue? {
        if (lastPositionMs?.let { positionMs < it } == true) {
            reset()
        }
        lastPositionMs = positionMs

        while (pending.peek()?.cue?.ptsMs?.let { it <= positionMs } == true) {
            val cue = pending.remove().cue
            visibleCue = if (cue.clearScreen && cue.images.isEmpty()) null else cue
        }

        val current = visibleCue
        if (current != null && current.durationMs > 0L) {
            val endMs = saturatedAdd(current.ptsMs, current.durationMs)
            if (positionMs >= endMs) visibleCue = null
        }
        return visibleCue
    }

    fun reset() {
        pending.clear()
        visibleCue = null
        lastOfferedPtsMs = null
        lastPositionMs = null
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
