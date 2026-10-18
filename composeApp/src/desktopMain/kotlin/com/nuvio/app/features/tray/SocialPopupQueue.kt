package com.nuvio.app.features.tray

import com.nuvio.app.features.social.SocialPopup

/**
 * Which desktop pop-ups are on screen, which are waiting, and when each one goes. Pure: the caller
 * passes the time and acts on [nextDueAtMs].
 *
 * - At most [maxVisible] at once, oldest on top. A burst beyond that **waits** and takes the next free
 *   slot. Until 2026-10-18 a fourth pop-up pushed the oldest off screen, and since the ledger had already
 *   marked it seen it was never shown again.
 * - While the pointer is over the stack nothing goes ([hold]). On [release] every card gets the time it had
 *   left, and at least [afterHoverMs], so moving away is not met by everything vanishing at once.
 */
internal class SocialPopupQueue(
    private val maxVisible: Int = 3,
    private val visibleMs: Long = 7_000L,
    private val afterHoverMs: Long = 3_000L,
) {
    private class Shown(val popup: SocialPopup, var dueAtMs: Long?, var remainingMs: Long)

    private val shown = mutableListOf<Shown>()
    private val waiting = ArrayDeque<SocialPopup>()

    var isHeld: Boolean = false
        private set

    /** On screen, oldest first. */
    val visible: List<SocialPopup> get() = shown.map { it.popup }

    val waitingCount: Int get() = waiting.size

    fun offer(popup: SocialPopup, nowMs: Long) {
        if (shown.any { it.popup.key == popup.key } || waiting.any { it.key == popup.key }) return
        waiting.addLast(popup)
        promote(nowMs)
    }

    fun dismiss(key: String, nowMs: Long) {
        shown.removeAll { it.popup.key == key }
        waiting.removeAll { it.key == key }
        promote(nowMs)
    }

    fun hold(nowMs: Long) {
        if (isHeld) return
        isHeld = true
        shown.forEach { card ->
            card.remainingMs = ((card.dueAtMs ?: nowMs) - nowMs).coerceAtLeast(0L)
            card.dueAtMs = null
        }
    }

    fun release(nowMs: Long) {
        if (!isHeld) return
        isHeld = false
        shown.forEach { card -> card.dueAtMs = nowMs + maxOf(card.remainingMs, afterHoverMs) }
    }

    /** Drops every card whose time is up and fills the freed slots. True when anything changed. */
    fun expire(nowMs: Long): Boolean {
        if (isHeld) return false
        val gone = shown.removeAll { card -> card.dueAtMs?.let { it <= nowMs } == true }
        val before = shown.size
        promote(nowMs)
        return gone || shown.size != before
    }

    /** When the next card is due to go; null while held or empty. */
    fun nextDueAtMs(): Long? = if (isHeld) null else shown.mapNotNull { it.dueAtMs }.minOrNull()

    fun clear() {
        shown.clear()
        waiting.clear()
        isHeld = false
    }

    private fun promote(nowMs: Long) {
        while (shown.size < maxVisible && waiting.isNotEmpty()) {
            val popup = waiting.removeFirst()
            shown += Shown(popup, dueAtMs = if (isHeld) null else nowMs + visibleMs, remainingMs = visibleMs)
        }
    }
}
