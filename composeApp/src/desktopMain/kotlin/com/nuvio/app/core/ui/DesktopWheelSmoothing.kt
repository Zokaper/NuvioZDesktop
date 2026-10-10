package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlinx.coroutines.channels.Channel

/*
 * Smooth wheel and touchpad scrolling on Windows, where Compose's own smoothing makes a touchpad
 * stutter.
 *
 * Compose treats every Windows wheel event as a notched mouse wheel ("even free scrolling wheels
 * should trigger animation"). A precision touchpad sends bursts of small events, one to seven
 * between two frames, and each one cancels the running animation, jumps 6 dp at once and starts a
 * new one. The page then moves a different distance every frame although every frame is on time:
 * the jitter that was left once frames got fast.
 *
 * Here the real wheel events are held back. They only move a target, and once per frame the shown
 * position follows that target with a critically damped spring: velocity stays continuous, it
 * never overshoots, and a burst of events lands as the same smooth motion as an even stream. A
 * mouse notch is a step of the target, so it becomes a short eased glide. Each frame's share is
 * handed to Compose as one wheel event, which Compose applies at once because its own smoothing is
 * switched off ([disableComposeWheelSmoothing], before any scrollable is composed).
 *
 * Same mechanism as LinuxExtendedMouseButtons: an EventQueue pushed once the window peer exists,
 * because only a queue can drop an event; an AWTEventListener only observes.
 */

/** Follows the wheel input of both axes; everything here runs on the event thread. */
internal class WheelSmoother {
    private class Axis {
        var target = 0.0
        var position = 0.0
        var velocity = 0.0

        val isSettled get() = position == target && velocity == 0.0
    }

    private val vertical = Axis()
    private val horizontal = Axis()
    private var notched = false

    val isIdle: Boolean get() = vertical.isSettled && horizontal.isSettled

    fun add(rotation: Double, horizontal: Boolean) {
        notched = isNotched(rotation)
        (if (horizontal) this.horizontal else vertical).target += rotation
    }

    /**
     * Advances by [dtNanos] and returns how far to scroll this frame, in wheel rotations, as
     * (vertical, horizontal). Everything added is emitted in full by the time motion settles.
     */
    fun step(dtNanos: Long): Pair<Double, Double> {
        val omega = if (notched) NOTCH_OMEGA else TOUCHPAD_OMEGA
        val dt = dtNanos.coerceIn(MIN_STEP_NANOS, MAX_STEP_NANOS) / 1e9
        return advance(vertical, omega, dt) to advance(horizontal, omega, dt)
    }

    private fun advance(axis: Axis, omega: Double, dt: Double): Double {
        if (axis.isSettled) return 0.0
        // Exact critically damped spring over dt, towards a target that holds still for the step.
        val offset = axis.position - axis.target
        val decay = exp(-omega * dt)
        val slope = axis.velocity + omega * offset
        var nextOffset = (offset + slope * dt) * decay
        var nextVelocity = (axis.velocity - slope * omega * dt) * decay
        val crossed = nextOffset != 0.0 && offset != 0.0 && (nextOffset > 0) != (offset > 0)
        val settled = abs(nextOffset) < SETTLE_ROTATION && abs(nextVelocity) < SETTLE_VELOCITY
        if (crossed || settled) {
            // Never carry past the target: a scroll that bounces back reads as a glitch.
            nextOffset = 0.0
            nextVelocity = 0.0
        }
        val next = axis.target + nextOffset
        val delta = next - axis.position
        axis.velocity = nextVelocity
        if (nextOffset == 0.0 && nextVelocity == 0.0) {
            // Rebase at rest so the values never grow over a long session.
            axis.target = 0.0
            axis.position = 0.0
        } else {
            axis.position = next
        }
        return delta
    }

    internal companion object {
        /** Touchpad: about 2/omega = 36 ms behind the finger at a steady speed. */
        const val TOUCHPAD_OMEGA = 55.0

        /** One mouse notch reaches 90 % in about 3.9/omega = 110 ms, like a browser's smooth wheel. */
        const val NOTCH_OMEGA = 35.0

        const val MIN_STEP_NANOS = 2_000_000L
        const val MAX_STEP_NANOS = 50_000_000L
        const val SETTLE_ROTATION = 0.002
        const val SETTLE_VELOCITY = 0.05

        fun isNotched(rotation: Double): Boolean =
            abs(rotation) >= 1.0 && abs(rotation - rotation.roundToLong()) < 1e-3
    }
}

/** A wheel event this file made; it passes the queue untouched. */
private class SmoothedWheelEvent(template: MouseWheelEvent, rotation: Double, horizontal: Boolean) :
    MouseWheelEvent(
        template.component,
        MOUSE_WHEEL,
        System.currentTimeMillis(),
        if (horizontal) {
            template.modifiersEx or InputEvent.SHIFT_DOWN_MASK
        } else {
            template.modifiersEx and InputEvent.SHIFT_DOWN_MASK.inv()
        },
        template.x,
        template.y,
        template.xOnScreen,
        template.yOnScreen,
        0,
        false,
        WHEEL_UNIT_SCROLL,
        template.scrollAmount,
        0,
        rotation,
    )

internal object DesktopWheelSmoothing {
    private val smoother = WheelSmoother()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var template: MouseWheelEvent? = null
    private var installed = false

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("windows")

    /** Call from main(), before any window or scrollable exists. */
    fun disableComposeWheelSmoothing() {
        if (!isWindows) return
        System.setProperty("compose.scrolling.smooth.enabled", "false")
    }

    /** Call once the window peer exists, on the event thread. */
    fun install() {
        if (!isWindows || installed) return
        installed = true
        runCatching { Toolkit.getDefaultToolkit().systemEventQueue.push(SmoothingQueue()) }
            .onFailure { installed = false }
    }

    /** Drives the smoother from the window's frame clock while there is motion. */
    @Composable
    fun FrameDriver() {
        if (!isWindows) return
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                wake.receive()
                last = 0L
                while (!smoother.isIdle) {
                    withFrameNanos { now ->
                        val dt = if (last == 0L) DEFAULT_FRAME_NANOS else now - last
                        last = now
                        val (dy, dx) = smoother.step(dt)
                        emit(dy, horizontal = false)
                        emit(dx, horizontal = true)
                    }
                }
            }
        }
    }

    private fun emit(rotation: Double, horizontal: Boolean) {
        if (rotation == 0.0) return
        val source = template ?: return
        // Posted, not dispatched: this frame is already being drawn, so it lands in the next one.
        Toolkit.getDefaultToolkit().systemEventQueue.postEvent(SmoothedWheelEvent(source, rotation, horizontal))
    }

    /** True for wheel events this should take over; anything else passes straight through. */
    internal fun shouldSmooth(event: MouseWheelEvent): Boolean {
        if (event.scrollType != MouseWheelEvent.WHEEL_UNIT_SCROLL) return false
        // Ctrl+wheel zooms the UI; keep modifier gestures exactly as the user made them.
        val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK
        if (event.modifiersEx and modifiers != 0) return false
        return isComposeComponent(event.component)
    }

    private fun isComposeComponent(component: Component?): Boolean {
        val name = component?.javaClass?.name ?: return false
        return name.startsWith("org.jetbrains.skiko.") || name.startsWith("androidx.compose.")
    }

    private fun accept(event: MouseWheelEvent) {
        template = event
        smoother.add(event.preciseWheelRotation, horizontal = event.isShiftDown)
        wake.trySend(Unit)
    }

    private class SmoothingQueue : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            if (event is MouseWheelEvent && event !is SmoothedWheelEvent) {
                // An input convenience must never cost the app an event.
                val taken = runCatching { shouldSmooth(event).also { if (it) accept(event) } }.getOrDefault(false)
                if (taken) return
            }
            super.dispatchEvent(event)
        }
    }

    private const val DEFAULT_FRAME_NANOS = 16_666_667L
}
