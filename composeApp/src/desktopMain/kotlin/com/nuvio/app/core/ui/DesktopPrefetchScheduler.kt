package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.LocalPlatformPrefetchScheduler
import androidx.compose.ui.platform.PlatformPrefetchRequest
import androidx.compose.ui.platform.PlatformPrefetchRequestScope
import androidx.compose.ui.platform.PlatformPrefetchScheduler
import java.awt.AWTEvent
import java.awt.DisplayMode
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * Lets lazy lists compose the rows and cards that are about to scroll into view between frames.
 *
 * Compose Multiplatform's desktop target ships a no-op prefetch scheduler, so every Home row and
 * every card in it was composed and measured inside the frame that first showed it: 30-140 ms per
 * row on a warm app, the jitter left once the JDK 17 event-queue cost was gone. Android drains the
 * same requests between frames; this does it on the AWT event thread, which is where composition
 * runs on desktop.
 *
 * Each pass gets half a frame. Compose's requests stop themselves when [PlatformPrefetchRequestScope]
 * runs out and resume in the next pass, and a pass is posted behind whatever input and frames are
 * already queued, so a frame waits for at most one slice. A request can also decline to start when
 * its measured average does not fit (a whole poster row measures as one unit); the slot then grows
 * across frame-spaced retries, but only while the user is not scrolling or typing, because a long
 * slice in the middle of a scroll would be the very hitch this exists to remove.
 */
@OptIn(InternalComposeUiApi::class)
internal class DesktopPrefetchScheduler(
    private val nanoTime: () -> Long,
    private val frameIntervalNanos: () -> Long,
    private val lastInteractionNanos: () -> Long,
    private val post: (Runnable) -> Unit,
    private val postAfter: (delayNanos: Long, Runnable) -> Unit,
) : PlatformPrefetchScheduler {
    private val high = ArrayDeque<PlatformPrefetchRequest>()
    private val low = ArrayDeque<PlatformPrefetchRequest>()
    private var passPending = false
    private var declinedPasses = 0
    private var disposed = false

    override fun scheduleHighPriorityPrefetch(request: PlatformPrefetchRequest) {
        high.addLast(request)
        ensurePass()
    }

    override fun scheduleLowPriorityPrefetch(request: PlatformPrefetchRequest) {
        low.addLast(request)
        ensurePass()
    }

    fun dispose() {
        disposed = true
        high.clear()
        low.clear()
    }

    private fun ensurePass() {
        if (passPending || disposed) return
        passPending = true
        post(::runPass)
    }

    internal fun runPass() {
        passPending = false
        if (disposed) return
        val interval = frameIntervalNanos()
        val start = nanoTime()
        val interacting = start - lastInteractionNanos() < interval * INTERACTION_FRAMES
        val slice = if (interacting) {
            interval / 2
        } else {
            ((interval / 2) shl declinedPasses.coerceAtMost(MAX_GROWTH_STEPS)).coerceAtMost(MAX_SLICE_NANOS)
        }
        val deadline = start + slice
        val scope = object : PlatformPrefetchRequestScope {
            override fun availableTimeNanos(): Long = (deadline - nanoTime()).coerceAtLeast(0L)
        }

        var progressed = false
        while (nanoTime() < deadline) {
            val queue = when {
                high.isNotEmpty() -> high
                low.isNotEmpty() -> low
                else -> break
            }
            val request = queue.first()
            val before = nanoTime()
            val needsMoreTime = with(request) { scope.execute() }
            if (!needsMoreTime) {
                queue.removeFirst()
                progressed = true
                continue
            }
            // Either the slice ran out part-way through, or the request would not start in it.
            if (nanoTime() - before >= PROGRESS_NANOS) progressed = true
            break
        }

        if (high.isEmpty() && low.isEmpty()) {
            declinedPasses = 0
            return
        }
        passPending = true
        if (progressed) {
            declinedPasses = 0
            post(::runPass)
        } else {
            // Nothing fitted: wait a frame instead of spinning the event thread.
            declinedPasses++
            postAfter(interval, ::runPass)
        }
    }

    internal companion object {
        /** Input this recent (in frames) means the user is mid-scroll: keep slices short. */
        const val INTERACTION_FRAMES = 6L
        const val MAX_GROWTH_STEPS = 3
        const val MAX_SLICE_NANOS = 50_000_000L
        const val PROGRESS_NANOS = 500_000L
    }
}

private const val DEFAULT_FRAME_INTERVAL_NANOS = 16_666_667L
private const val DISPLAY_MODE_REFRESH_NANOS = 5_000_000_000L

/** Provides [DesktopPrefetchScheduler] to every lazy list in [content]. Call on the UI thread. */
@OptIn(InternalComposeUiApi::class)
@Composable
fun ProvideDesktopPrefetchScheduler(content: @Composable () -> Unit) {
    val holder = remember { DesktopPrefetchHolder() }
    DisposableEffect(holder) { onDispose { holder.dispose() } }
    CompositionLocalProvider(LocalPlatformPrefetchScheduler provides holder.scheduler, content = content)
}

private class DesktopPrefetchHolder {
    @Volatile
    private var lastInteractionNanos = System.nanoTime() - 1_000_000_000L
    private var frameIntervalNanos = DEFAULT_FRAME_INTERVAL_NANOS
    private var frameIntervalReadAt = Long.MIN_VALUE

    private val inputListener = AWTEventListener { event ->
        val interacting = when (event.id) {
            MouseEvent.MOUSE_WHEEL, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_PRESSED -> true
            else -> event.id in java.awt.event.KeyEvent.KEY_FIRST..java.awt.event.KeyEvent.KEY_LAST
        }
        if (interacting) lastInteractionNanos = System.nanoTime()
    }

    val scheduler = DesktopPrefetchScheduler(
        nanoTime = System::nanoTime,
        frameIntervalNanos = ::currentFrameIntervalNanos,
        lastInteractionNanos = { lastInteractionNanos },
        post = { EventQueue.invokeLater(it) },
        postAfter = { delayNanos, runnable ->
            Timer((delayNanos / 1_000_000L).toInt().coerceAtLeast(1)) { runnable.run() }.apply {
                isRepeats = false
                start()
            }
        },
    )

    init {
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                inputListener,
                AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or
                    AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK,
            )
        }
    }

    fun dispose() {
        scheduler.dispose()
        runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener) }
    }

    private fun currentFrameIntervalNanos(): Long {
        val now = System.nanoTime()
        if (now - frameIntervalReadAt > DISPLAY_MODE_REFRESH_NANOS) {
            frameIntervalReadAt = now
            frameIntervalNanos = runCatching {
                val rate = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.displayMode.refreshRate
                if (rate == DisplayMode.REFRESH_RATE_UNKNOWN || rate <= 0) {
                    DEFAULT_FRAME_INTERVAL_NANOS
                } else {
                    1_000_000_000L / rate
                }
            }.getOrDefault(DEFAULT_FRAME_INTERVAL_NANOS)
        }
        return frameIntervalNanos
    }
}
