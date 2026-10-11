package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

/**
 * End to end in a real window: a raw touchpad event is taken off the queue, and Compose scrolls by
 * the same total through several per-frame events instead. Opens a small window for a moment.
 */
class DesktopWheelSmoothingWindowTest {
    @Test
    fun aRawWheelEventScrollsComposeThroughPerFrameEvents() {
        assumeFalse("needs a display", GraphicsEnvironment.isHeadless())
        assumeFalse(
            "Windows only",
            !System.getProperty("os.name", "").lowercase().contains("windows"),
        )
        val state = LazyListState()
        // AWT re-creates a wheel event for the lightweight component under the pointer, so a
        // listener cannot tell the classes apart; the amounts tell them apart instead.
        val delivered = mutableListOf<Double>()
        val listener = AWTEventListener { event ->
            if (event is MouseWheelEvent) delivered += event.preciseWheelRotation
        }
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow()
            window.setSize(400, 400)
            window.setContent {
                DesktopWheelSmoothing.FrameDriver()
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(400) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
            window.isVisible = true
            DesktopWheelSmoothing.install()
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        }
        try {
            Thread.sleep(800) // first frames
            lateinit var target: java.awt.Component
            SwingUtilities.invokeAndWait {
                val pane = window.contentPane
                target = SwingUtilities.getDeepestComponentAt(pane, 200, 200)
                assertTrue(DesktopWheelSmoothing.shouldSmooth(wheel(target, 0.0)), "Compose's own component: ${target.javaClass.name}")
            }
            Toolkit.getDefaultToolkit().systemEventQueue.postEvent(wheel(target, 0.6))

            val deadline = System.currentTimeMillis() + 3_000
            var lastOffset = -1
            var stable = 0
            while (System.currentTimeMillis() < deadline && stable < 10) {
                Thread.sleep(30)
                var offset = 0
                SwingUtilities.invokeAndWait { offset = state.firstVisibleItemIndex * 100_000 + state.firstVisibleItemScrollOffset }
                if (offset == lastOffset && offset > 0) stable++ else stable = 0
                lastOffset = offset
            }

            SwingUtilities.invokeAndWait {
                assertTrue(lastOffset > 0, "the list scrolled")
                assertTrue(delivered.none { it == 0.6 }, "the raw event never reached a component: $delivered")
                assertTrue(delivered.size >= 4, "spread over frames: $delivered")
                assertEquals(0.6, delivered.sum(), 1e-9)
            }
        } finally {
            SwingUtilities.invokeAndWait {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                window.dispose()
            }
        }
    }

    @Test
    fun aSteadyTouchpadStreamProducesOneEventPerFrame() {
        assumeFalse("needs a display", GraphicsEnvironment.isHeadless())
        assumeFalse("Windows only", !System.getProperty("os.name", "").lowercase().contains("windows"))
        val state = LazyListState()
        val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val listener = AWTEventListener { event ->
            if (event is MouseWheelEvent && event.preciseWheelRotation != 0.05) times += System.nanoTime()
        }
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow()
            window.setSize(400, 400)
            window.setContent {
                DesktopWheelSmoothing.FrameDriver()
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(4000) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
            window.isVisible = true
            DesktopWheelSmoothing.install()
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        }
        try {
            Thread.sleep(800)
            lateinit var target: java.awt.Component
            SwingUtilities.invokeAndWait { target = SwingUtilities.getDeepestComponentAt(window.contentPane, 200, 200) }
            val queue = Toolkit.getDefaultToolkit().systemEventQueue
            val end = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < end) {
                queue.postEvent(wheel(target, 0.05))
                Thread.sleep(4)
            }
            Thread.sleep(500)
            val snapshot = synchronized(times) { times.toList() }
            val gaps = snapshot.zipWithNext { a, b -> (b - a) / 1_000_000.0 }.sorted()
            println("SMOOTHING-DIAG events=${snapshot.size} p50=${gaps[gaps.size / 2]} p90=${gaps[gaps.size * 9 / 10]} max=${gaps.last()}")
            assertTrue(gaps[gaps.size * 9 / 10] < 25.0, "one event per frame: p90 gap ${gaps[gaps.size * 9 / 10]} ms")
        } finally {
            SwingUtilities.invokeAndWait {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                window.dispose()
            }
        }
    }

    private fun wheel(target: java.awt.Component, rotation: Double) =
        MouseWheelEvent(
            target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 200, 200, 200, 200,
            0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 0, rotation,
        )
}
