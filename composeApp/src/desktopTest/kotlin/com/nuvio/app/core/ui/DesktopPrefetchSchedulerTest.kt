package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalPlatformPrefetchScheduler
import androidx.compose.ui.platform.PlatformPrefetchRequest
import androidx.compose.ui.platform.PlatformPrefetchRequestScope
import androidx.compose.ui.platform.PlatformPrefetchScheduler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop Compose ships a no-op prefetch scheduler, so lazy rows were composed inside the frame that
 * first showed them. These pin the replacement's slicing and its hook into Compose.
 */
@OptIn(InternalComposeUiApi::class)
class DesktopPrefetchSchedulerTest {
    @get:Rule
    val compose = createComposeRule()

    private var now = 0L
    private var lastInteraction = -10 * FRAME
    private val posted = mutableListOf<Runnable>()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()

    private val scheduler = DesktopPrefetchScheduler(
        nanoTime = { now },
        frameIntervalNanos = { FRAME },
        lastInteractionNanos = { lastInteraction },
        post = { posted += it },
        postAfter = { delay, runnable -> delayed += delay to runnable },
    )

    /** Spends [cost] of fake time per run; reports more work until [runs] runs have happened. */
    private inner class FakeRequest(
        private val name: String,
        private val cost: Long,
        private val runs: Int = 1,
        private val log: MutableList<String>? = null,
    ) : PlatformPrefetchRequest {
        var executed = 0
        val offered = mutableListOf<Long>()

        override fun PlatformPrefetchRequestScope.execute(): Boolean {
            offered += availableTimeNanos()
            executed++
            log?.add(name)
            now += cost
            return executed < runs
        }
    }

    /** Declines to start until it is offered [needs] of time, like a row whose measure average is big. */
    private inner class BigRequest(private val needs: Long) : PlatformPrefetchRequest {
        val offered = mutableListOf<Long>()
        var done = false

        override fun PlatformPrefetchRequestScope.execute(): Boolean {
            val available = availableTimeNanos()
            offered += available
            if (available <= needs) return true
            now += needs
            done = true
            return false
        }
    }

    private fun drainPosted() {
        while (posted.isNotEmpty()) posted.removeAt(0).run()
    }

    @Test
    fun runsBetweenFramesAndStopsWhenTheQueueIsEmpty() {
        val request = FakeRequest("a", cost = 2 * MS)
        scheduler.scheduleLowPriorityPrefetch(request)
        scheduler.scheduleLowPriorityPrefetch(FakeRequest("b", cost = 2 * MS))

        assertEquals(1, posted.size, "one pass is posted, not one per request")
        assertEquals(0, request.executed, "nothing runs inside the frame that scheduled it")
        drainPosted()

        assertEquals(1, request.executed)
        assertEquals(FRAME / 2, request.offered.single(), "a pass gets half a frame")
        assertTrue(posted.isEmpty() && delayed.isEmpty())
    }

    @Test
    fun highPriorityRunsFirst() {
        val log = mutableListOf<String>()
        scheduler.scheduleLowPriorityPrefetch(FakeRequest("low", cost = MS, log = log))
        scheduler.scheduleHighPriorityPrefetch(FakeRequest("high", cost = MS, log = log))
        drainPosted()

        assertEquals(listOf("high", "low"), log)
    }

    @Test
    fun aRequestThatRunsOutOfTimeContinuesInTheNextPass() {
        val request = FakeRequest("long", cost = FRAME / 2, runs = 3)
        scheduler.scheduleLowPriorityPrefetch(request)

        posted.removeAt(0).run()
        assertEquals(1, request.executed, "the slice ends with the request")
        assertEquals(1, posted.size, "progress was made, so the next pass is posted, not delayed")
        assertTrue(delayed.isEmpty())

        drainPosted()
        assertEquals(3, request.executed)
    }

    @Test
    fun aDeclinedRequestWaitsAFrameAndGetsALongerSliceWhileIdle() {
        val request = BigRequest(needs = 3 * FRAME)
        scheduler.scheduleLowPriorityPrefetch(request)
        drainPosted()

        repeat(3) {
            val (delay, retry) = delayed.removeAt(0)
            assertEquals(FRAME, delay, "a request that will not start does not spin the event thread")
            now += delay
            retry.run()
            drainPosted()
        }

        assertTrue(request.done)
        assertEquals(
            listOf(FRAME / 2, FRAME, 2 * FRAME, DesktopPrefetchScheduler.MAX_SLICE_NANOS),
            request.offered,
        )
        assertTrue(posted.isEmpty() && delayed.isEmpty())
    }

    @Test
    fun slicesStayShortWhileTheUserIsScrolling() {
        val request = BigRequest(needs = 3 * FRAME)
        scheduler.scheduleLowPriorityPrefetch(request)
        drainPosted()

        repeat(4) {
            val (delay, retry) = delayed.removeAt(0)
            now += delay
            lastInteraction = now
            retry.run()
        }

        assertFalse(request.done)
        assertTrue(request.offered.all { it == FRAME / 2 }, "offered ${request.offered}")
    }

    @Test
    fun theSliceIsCapped() {
        val request = BigRequest(needs = 10 * FRAME)
        scheduler.scheduleLowPriorityPrefetch(request)
        drainPosted()
        repeat(6) {
            val (delay, retry) = delayed.removeAt(0)
            now += delay
            retry.run()
        }

        assertEquals(DesktopPrefetchScheduler.MAX_SLICE_NANOS, request.offered.max())
    }

    @Test
    fun disposeDropsQueuedWork() {
        val request = FakeRequest("a", cost = MS)
        scheduler.scheduleLowPriorityPrefetch(request)
        scheduler.dispose()
        drainPosted()

        assertEquals(0, request.executed)
    }

    /** The seam itself: a LazyColumn under the local hands its prefetch to the provided scheduler. */
    @Test
    fun lazyListsUnderTheLocalComposeAheadThroughIt() {
        val requests = mutableListOf<PlatformPrefetchRequest>()
        val recording = object : PlatformPrefetchScheduler {
            override fun scheduleHighPriorityPrefetch(request: PlatformPrefetchRequest) {
                requests += request
            }

            override fun scheduleLowPriorityPrefetch(request: PlatformPrefetchRequest) {
                requests += request
            }
        }
        val composed = mutableSetOf<Int>()
        lateinit var state: androidx.compose.foundation.lazy.LazyListState

        compose.setContent {
            CompositionLocalProvider(LocalPlatformPrefetchScheduler provides recording) {
                state = rememberLazyListState()
                LazyColumn(state = state, modifier = Modifier.size(100.dp, 250.dp)) {
                    items(50) { index ->
                        DisposableEffect(index) {
                            composed += index
                            onDispose { composed -= index }
                        }
                        Box(Modifier.height(100.dp))
                    }
                }
            }
        }
        compose.runOnIdle { state.dispatchRawDelta(10f) }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(requests.isNotEmpty(), "scrolling schedules a prefetch")
            assertFalse(3 in composed, "item 3 is below the viewport and not composed yet")
            val generous = object : PlatformPrefetchRequestScope {
                override fun availableTimeNanos(): Long = Long.MAX_VALUE / 2
            }
            requests.toList().forEach { request ->
                @Suppress("ControlFlowWithEmptyBody")
                while (with(request) { generous.execute() }) {}
            }
            assertTrue(3 in composed, "the prefetch composed the next row before it was shown")
        }
    }

    private companion object {
        const val MS = 1_000_000L
        const val FRAME = 16 * MS
    }
}
