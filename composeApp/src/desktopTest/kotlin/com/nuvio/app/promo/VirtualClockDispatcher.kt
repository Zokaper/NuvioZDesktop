@file:OptIn(InternalCoroutinesApi::class)

package com.nuvio.app.promo

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * A dispatcher whose `delay` runs on the frame clock instead of the wall clock.
 *
 * `ImageComposeScene` advances animations by the frame time it is given, but a `LaunchedEffect`'s
 * `delay` - Home's 8s hero rotation, for one - would otherwise run on real time, so a slow render
 * rotated the hero mid-shot. With this as the scene's context, both clocks are the same clock and a
 * render is the same every time. Work is queued and run by [pump] on the rendering thread.
 */
internal class VirtualClockDispatcher : CoroutineDispatcher(), Delay {
    @Volatile
    var nowMs: Long = 0L
        private set

    private val ready = ConcurrentLinkedQueue<Runnable>()
    private val timed = PriorityQueue<Timed>(compareBy<Timed>({ it.atMs }, { it.seq }))
    private var seq = 0L

    private inner class Timed(val atMs: Long, val seq: Long, val block: Runnable) : DisposableHandle {
        @Volatile var cancelled = false
        override fun dispose() { cancelled = true }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        ready.add(block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val handle = schedule(timeMillis, Runnable { with(continuation) { resumeUndispatched(Unit) } })
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
        schedule(timeMillis, block)

    private fun schedule(timeMillis: Long, block: Runnable): DisposableHandle = synchronized(timed) {
        Timed(nowMs + timeMillis.coerceAtLeast(0), seq++, block).also(timed::add)
    }

    /** Moves the clock to [ms], running everything that became due, in order. */
    fun advanceTo(ms: Long) {
        while (true) {
            pump()
            val next = synchronized(timed) {
                val head = timed.peek()
                if (head != null && head.atMs <= ms) timed.poll() else null
            } ?: break
            if (next.cancelled) continue
            nowMs = maxOf(nowMs, next.atMs)
            next.block.run()
        }
        nowMs = maxOf(nowMs, ms)
        pump()
    }

    fun pump() {
        var guard = 0
        while (guard++ < 100_000) {
            val task = ready.poll() ?: return
            task.run()
        }
    }
}
