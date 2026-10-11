package com.nuvio.app.core.ui

import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Windows wheel smoother. The input pattern below is the shape a precision touchpad produced in
 * the 2026-10-10 signed-in run: one to seven events between two frames, all small.
 */
class WheelSmootherTest {
    private val frame = 16_666_667L

    /** Runs frames until the smoother settles; returns each frame's vertical output. */
    private fun WheelSmoother.drain(limit: Int = 200): List<Double> {
        val out = mutableListOf<Double>()
        var n = 0
        while (!isIdle && n++ < limit) out += step(frame).first
        assertTrue(isIdle, "settles within $limit frames")
        return out
    }

    @Test
    fun emitsExactlyWhatWasAdded() {
        val smoother = WheelSmoother()
        val added = listOf(0.12, 0.31, 0.07, 0.4, -0.05, 0.22)
        added.forEach { smoother.add(it, horizontal = false) }

        val out = smoother.drain()

        assertEquals(added.sum(), out.sum(), 1e-9)
    }

    @Test
    fun neverOvershootsTheTarget() {
        val smoother = WheelSmoother()
        smoother.add(1.0, horizontal = false)
        var applied = 0.0
        val out = smoother.drain()
        for (delta in out) {
            assertTrue(delta >= 0.0, "never moves back: $out")
            applied += delta
            assertTrue(applied <= 1.0 + 1e-9, "never past the notch: $applied")
        }
    }

    @Test
    fun aBurstyTouchpadComesOutEven() {
        val smoother = WheelSmoother()
        // Same mean speed every frame, delivered unevenly: 1, 4, 2, 7, 1, 3, ... events per frame.
        val perFrame = listOf(1, 4, 2, 7, 1, 3, 5, 1, 6, 2, 4, 1, 3, 7, 2, 4)
        val eventSize = 0.05
        val output = mutableListOf<Double>()
        val input = mutableListOf<Double>()
        repeat(3) {
            for (count in perFrame) {
                repeat(count) { smoother.add(eventSize, horizontal = false) }
                input += count * eventSize
                output += smoother.step(frame).first
            }
        }
        // Skip the ramp-up of the first pass; compare the steady part.
        val steadyIn = input.drop(perFrame.size)
        val steadyOut = output.drop(perFrame.size)

        val inputVariation = variation(steadyIn)
        val outputVariation = variation(steadyOut)
        assertTrue(inputVariation > 0.5, "the input really is uneven: $inputVariation")
        assertTrue(outputVariation < 0.15, "output varies $outputVariation (input $inputVariation)")
    }

    @Test
    fun aMouseNotchGlidesInsteadOfJumping() {
        val smoother = WheelSmoother()
        smoother.add(1.0, horizontal = false)
        val out = smoother.drain()

        assertTrue(out.first() < 0.35, "the first frame takes only part of the notch: $out")
        val withinSevenFrames = out.take(7).sum()
        assertTrue(withinSevenFrames > 0.9, "most of the notch lands within ~115 ms: $withinSevenFrames")
    }

    @Test
    fun reversingDirectionFollowsTheFinger() {
        val smoother = WheelSmoother()
        smoother.add(0.5, horizontal = false)
        smoother.step(frame)
        smoother.add(-1.0, horizontal = false)
        val out = smoother.drain()
        assertTrue(out.any { it < 0 }, "moves back once the input does")
    }

    @Test
    fun axesAreIndependent() {
        val smoother = WheelSmoother()
        smoother.add(0.3, horizontal = true)
        var vertical = 0.0
        var horizontal = 0.0
        while (!smoother.isIdle) {
            val (dy, dx) = smoother.step(frame)
            vertical += dy
            horizontal += dx
        }
        assertEquals(0.0, vertical)
        assertEquals(0.3, horizontal, 1e-9)
    }

    @Test
    fun recognisesNotchedWheels() {
        assertTrue(WheelSmoother.isNotched(1.0))
        assertTrue(WheelSmoother.isNotched(-2.0))
        assertFalse(WheelSmoother.isNotched(0.13))
        assertFalse(WheelSmoother.isNotched(1.25))
    }

    @Test
    fun leavesModifierGesturesAndForeignComponentsAlone() {
        val canvas = Canvas()
        fun wheel(modifiers: Int, type: Int = MouseWheelEvent.WHEEL_UNIT_SCROLL) =
            MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, 0L, modifiers, 1, 1, 1, 1, 0, false, type, 3, 0, 0.2)

        assertFalse(DesktopWheelSmoothing.shouldSmooth(wheel(InputEvent.CTRL_DOWN_MASK)), "Ctrl+wheel zooms")
        assertFalse(DesktopWheelSmoothing.shouldSmooth(wheel(0, MouseWheelEvent.WHEEL_BLOCK_SCROLL)), "page scroll")
        assertFalse(DesktopWheelSmoothing.shouldSmooth(wheel(0)), "a non-Compose component keeps its own wheel")
    }

    private fun variation(values: List<Double>): Double {
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return abs(sd / mean)
    }
}
