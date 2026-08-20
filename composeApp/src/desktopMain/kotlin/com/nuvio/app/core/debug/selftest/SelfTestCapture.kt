package com.nuvio.app.core.debug.selftest

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.NativePlayerDiagnosticsRegistry
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * Getting a picture of what is actually on screen.
 *
 * **Why `java.awt.Robot` and not Compose.** The desktop player is not Compose: mpv renders into a
 * native child HWND behind a `SwingPanel`, and the player controls are a WebView2 overlay in
 * another window entirely. `ImageComposeScene` - which is what `SetupWizardRenderHarness` uses
 * offscreen - draws the Compose tree and nothing else, so the video region comes back black and
 * the controls do not appear at all. A screen grab is the only thing that sees the real composite.
 *
 * The cost is real and worth stating: the window has to be **visible, foreground and unobscured**
 * for the whole run, the capture includes anything sitting on top of it, and none of this works on
 * a headless machine. That is the trade the plan took deliberately - a black rectangle proves
 * nothing, and proving things is the point.
 *
 * mpv's own `screenshot-to-file` is taken *as well as*, not instead of. Between the two, a failure
 * localises itself: if the mpv frame is fine and the grab is black, compositing or the window is at
 * fault; if both are black, decoding is.
 */
internal class SelfTestCapture(
    private val window: Window,
    private val runDir: Path,
) {
    private val log = Logger.withTag("SelfTestCapture")
    private val robot: Robot? = runCatching { Robot() }
        .onFailure { log.w(it) { "Robot unavailable - screenshots will be skipped" } }
        .getOrNull()

    private val shotsDir: Path = runDir.resolve("shots")
    private val framesDir: Path = runDir.resolve("frames")

    val isAvailable: Boolean get() = robot != null

    /**
     * Grabs the window, writes `shots/<name>.png`, and returns the run-relative path.
     *
     * Bounds are read on the EDT: the window can be moving, and AWT's own accessors are only
     * coherent there.
     */
    suspend fun screen(name: String): String? {
        val activeRobot = robot ?: return null

        // The status overlay is hidden for the grab and restored after. The wait is not optional:
        // hiding it only schedules a recomposition, and a capture taken in the same breath still
        // photographs the old frame with the card in it.
        DesktopSelfTest.state.suppressedForCapture = true
        try {
            delay(OVERLAY_HIDE_SETTLE_MS)
            val bounds = windowBounds() ?: return null
            if (bounds.width <= 0 || bounds.height <= 0) {
                log.w { "window has no area, skipping shot $name" }
                return null
            }
            return runCatching {
                val image = activeRobot.createScreenCapture(bounds)
                Files.createDirectories(shotsDir)
                val target = shotsDir.resolve("$name.png")
                ImageIO.write(image, "png", target.toFile())
                runDir.relativize(target).toString().replace('\\', '/')
            }.onFailure { log.w(it) { "screen capture failed for $name" } }.getOrNull()
        } finally {
            DesktopSelfTest.state.suppressedForCapture = false
        }
    }

    /**
     * Asks mpv for the decoded frame. Returns null when nothing is playing.
     *
     * `includeSubtitles` is what proves libass drew - mpv's `video` mode deliberately omits them.
     */
    fun mpvFrame(name: String, includeSubtitles: Boolean = false): String? {
        val target = framesDir.resolve("$name.mpv.png")
        if (!NativePlayerDiagnosticsRegistry.writeFrame(target, includeSubtitles)) return null
        return runDir.relativize(target).toString().replace('\\', '/')
    }

    /** Both, at the same instant, for a playback check. Nulls are dropped. */
    suspend fun both(name: String, includeSubtitles: Boolean = false): List<String> =
        listOfNotNull(mpvFrame(name, includeSubtitles), screen(name))

    /**
     * Waits until the screen stops changing, then reports what it settled on.
     *
     * Compose Desktop exposes no idling resource, and every alternative considered was worse: a
     * fixed sleep is either slow or a race, and waiting on a repository's `isLoading` misses
     * everything that animates after the data arrives - which on this app is most of it.
     *
     * The [Settled.changed] flag is load-bearing beyond timing. A screen that settles on the very
     * first comparison never drew anything new, which is what a blank screen looks like from here;
     * checks that care assert on it rather than trusting that navigation worked.
     */
    suspend fun awaitSettled(
        timeoutMs: Long = 15_000L,
        intervalMs: Long = 250L,
        /** Fraction of sampled pixels allowed to differ and still count as still. */
        tolerance: Double = 0.002,
    ): Settled {
        val activeRobot = robot ?: return Settled(settled = false, changed = false, waitedMs = 0)
        val started = System.currentTimeMillis()
        var previous: BufferedImage? = null
        var everChanged = false
        var consecutiveStill = 0

        while (System.currentTimeMillis() - started < timeoutMs) {
            delay(intervalMs)
            val elapsed = System.currentTimeMillis() - started
            val bounds = windowBounds() ?: continue
            val current = runCatching { activeRobot.createScreenCapture(bounds) }.getOrNull() ?: continue
            val last = previous
            previous = current
            if (last == null) continue
            if (differenceFraction(last, current) > tolerance) {
                everChanged = true
                consecutiveStill = 0
                continue
            }
            consecutiveStill++
            // ⚠ Two conditions, and the first run of this harness is why both exist. A single pair
            // of matching frames declared the details screen settled at 675 ms and photographed it
            // **mid-dissolve** - the disintegration transition has slow phases where consecutive
            // frames genuinely match, and the report came back with a screenshot of a half-drawn
            // checkerboard that looked like a rendering fault rather than a timing one.
            //
            // So: several consecutive still frames, and never before [MIN_SETTLE_MS] has passed.
            // The floor is what covers a transition that has not started yet when the first sample
            // is taken, which no amount of consecutive matching can see.
            if (consecutiveStill >= REQUIRED_STILL_SAMPLES && elapsed >= MIN_SETTLE_MS) {
                return Settled(settled = true, changed = everChanged, waitedMs = elapsed)
            }
        }
        return Settled(settled = false, changed = everChanged, waitedMs = timeoutMs)
    }

    data class Settled(val settled: Boolean, val changed: Boolean, val waitedMs: Long)

    /**
     * A real mouse click at the centre of the window.
     *
     * Synthesised at the OS level rather than dispatched into Compose, which is the whole point:
     * the fault being hunted is a full-screen surface that fails to *consume* pointer input, and an
     * event injected past the window manager would not travel the path where that goes wrong.
     *
     * ⚠ This moves the real cursor. The run needs the machine to itself.
     */
    fun clickCentre() {
        val activeRobot = robot ?: return
        val bounds = windowBounds() ?: return
        runCatching {
            activeRobot.mouseMove(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
            activeRobot.delay(120)
            activeRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            activeRobot.delay(60)
            activeRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }.onFailure { log.w(it) { "synthetic click failed" } }
    }

    /** A real key press, for the Back-out-of-the-player check. */
    fun pressKey(keyCode: Int) {
        val activeRobot = robot ?: return
        runCatching {
            activeRobot.keyPress(keyCode)
            activeRobot.delay(60)
            activeRobot.keyRelease(keyCode)
        }.onFailure { log.w(it) { "synthetic key press failed" } }
    }

    /**
     * Brings the window forward and waits for the OS to agree.
     *
     * Everything here photographs the screen, so a window that has lost focus - to the IDE, to a
     * notification - produces a folder full of pictures of something else.
     */
    fun focusWindow() {
        runCatching {
            SwingUtilities.invokeAndWait {
                window.toFront()
                window.requestFocus()
            }
        }.onFailure { log.w(it) { "could not focus the window" } }
    }

    /**
     * How much two frames differ, over a bounded sample rather than every pixel.
     *
     * A 4K window is eight megapixels and this runs four times a second; comparing all of them
     * would make the detector the slowest thing in the suite and change what it is measuring.
     * Sampling on a grid of roughly [SAMPLE_TARGET] points is stable enough to tell an animation
     * from a still frame, which is all that is being asked.
     */
    private fun differenceFraction(a: BufferedImage, b: BufferedImage): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        val step = maxOf(1, (a.width.toLong() * a.height / SAMPLE_TARGET).toDouble().let {
            kotlin.math.sqrt(it).toInt()
        })
        var sampled = 0
        var differing = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                sampled++
                if (channelDistance(a.getRGB(x, y), b.getRGB(x, y)) > CHANNEL_TOLERANCE) differing++
                x += step
            }
            y += step
        }
        return if (sampled == 0) 0.0 else differing.toDouble() / sampled
    }

    /**
     * Per-channel difference, not raw ARGB equality.
     *
     * Video is the thing most often on screen here, and a decoded frame is never bit-identical
     * twice - dithering and chroma noise alone would make an exact comparison report "changing"
     * forever on a paused player.
     */
    private fun channelDistance(first: Int, second: Int): Int {
        val dr = abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF))
        val dg = abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF))
        val db = abs((first and 0xFF) - (second and 0xFF))
        return maxOf(dr, dg, db)
    }

    private fun windowBounds(): Rectangle? {
        var bounds: Rectangle? = null
        runCatching {
            SwingUtilities.invokeAndWait {
                bounds = if (window.isShowing) {
                    Rectangle(window.locationOnScreen, window.size)
                } else {
                    null
                }
            }
        }.onFailure { log.w(it) { "could not read window bounds" } }
        return bounds
    }

    private companion object {
        const val SAMPLE_TARGET = 20_000
        const val CHANNEL_TOLERANCE = 12

        /** Consecutive matching frames before a screen counts as still. */
        const val REQUIRED_STILL_SAMPLES = 3

        /** Floor on the wait, for transitions that have not begun when sampling starts. */
        const val MIN_SETTLE_MS = 1_500L

        /** Long enough for the overlay's removal to reach the screen before the grab. */
        const val OVERLAY_HIDE_SETTLE_MS = 300L
    }
}
