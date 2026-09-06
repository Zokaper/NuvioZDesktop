package com.nuvio.app.core.debug

import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * Names whatever is blocking the UI thread, with its stack, **while it is still blocked**.
 *
 * ⚠ **This exists because "the UI stutters here" is not a debuggable statement.** Three rounds of
 * work on the source-to-loading-screen hand-off were spent on plausible causes - a full-screen
 * `graphicsLayer`, a crossfade, an image re-decode - each argued from reading the code and each
 * only half right. Compose Desktop composes, lays out and draws on the AWT event thread, so every
 * stutter the maintainer can see is that one thread being held by something. This says what.
 *
 * The method is a heartbeat rather than a profiler: post a trivial runnable to the event queue,
 * time how long it waits, and if it waits longer than a stutter is worth reporting, sample the
 * event thread's stack *before* it is released. A stack taken after the stall ends is the stack of
 * the next idle frame and explains nothing, which is why the sample is taken from a second thread
 * mid-wait rather than from a wrapper around the work.
 *
 * Debug builds only - `isDebugBuild` is `-Dnuvio.debugTools=true` on desktop - and the lines land
 * in the same `DesktopDebugLog` file as everything else, so a report can be lined up against the
 * `PlaybackStartup` entries by timestamp.
 */
internal object EdtStallWatchdog {

    private val log = Logger.withTag("EdtStall")

    /** Two dropped frames at 60 Hz. Below this a report is scheduler noise, not a stutter. */
    private const val REPORT_THRESHOLD_MS = 34L

    /** How often the blocked event thread is sampled once it is past the threshold. */
    private const val SAMPLE_INTERVAL_MS = 20L

    /** Roughly one frame between heartbeats, so a stall cannot hide between two of them. */
    private const val IDLE_POLL_MS = 16L

    /** Deep enough to cross Compose's dispatch into the call that is actually blocking. */
    private const val STACK_DEPTH = 20

    @Volatile
    private var eventThread: Thread? = null

    fun install() {
        if (!isDebugBuild) return
        Thread({ runCatching { watch() } }, "nuvio-edt-stall-watchdog").apply {
            isDaemon = true
            // Above the event thread, or the watchdog is descheduled by the very contention it
            // exists to measure and reports its own lateness as the application's.
            priority = Thread.MAX_PRIORITY
            start()
        }
        log.i { "watching the AWT event thread; reporting stalls over ${REPORT_THRESHOLD_MS}ms" }
    }

    private fun watch() {
        while (true) {
            val postedAt = System.nanoTime()
            val ranAt = AtomicLong(0L)
            SwingUtilities.invokeLater {
                eventThread = Thread.currentThread()
                ranAt.set(System.nanoTime())
            }

            // The first sample past the threshold, and the last one before the thread was
            // released. One stall usually spans several calls - a native window being created,
            // then its first paint - and only the pair shows that.
            var opening: Array<StackTraceElement>? = null
            var closing: Array<StackTraceElement>? = null
            while (ranAt.get() == 0L) {
                Thread.sleep(SAMPLE_INTERVAL_MS)
                if (elapsedMs(postedAt) < REPORT_THRESHOLD_MS) continue
                val sample = eventThread?.stackTrace ?: continue
                if (opening == null) opening = sample else closing = sample
            }

            val stalledMs = (ranAt.get() - postedAt) / 1_000_000L
            if (stalledMs >= REPORT_THRESHOLD_MS) report(stalledMs, opening, closing)
            Thread.sleep(IDLE_POLL_MS)
        }
    }

    private fun elapsedMs(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000L

    private fun report(
        stalledMs: Long,
        opening: Array<StackTraceElement>?,
        closing: Array<StackTraceElement>?,
    ) {
        val newline = System.lineSeparator()
        val text = buildString {
            append("ui thread stalled ${stalledMs}ms")
            if (opening == null) {
                append(newline)
                append("  (released before it could be sampled)")
                return@buildString
            }
            append(newline)
            append("  entering:")
            append(newline)
            append(format(opening, newline))
            if (closing != null && !closing.contentEquals(opening)) {
                append(newline)
                append("  leaving:")
                append(newline)
                append(format(closing, newline))
            }
        }
        log.w { text }
    }

    private fun format(stack: Array<StackTraceElement>, newline: String): String =
        stack.take(STACK_DEPTH).joinToString(separator = newline) { frame -> "    at $frame" }
}
