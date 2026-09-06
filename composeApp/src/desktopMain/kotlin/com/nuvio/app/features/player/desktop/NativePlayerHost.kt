package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Point2D
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

internal class NativePlayerHost : Canvas() {
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    var onCursorActivity: (() -> Unit)? = null

    /**
     * The canvas has something to draw, so it is safe to promote over the loading screen.
     *
     * ⚠ **Promotion used to be driven by [onFirstPaint], which fires from the paint of the *1 dp*
     * panel.** `PlayerEngine.desktop.kt` parks the `SwingPanel` at `requiredSize(1.dp)` until its
     * first paint, so the canvas was promoted to full size - covering a correct Compose loading
     * screen - as soon as it had painted one pixel, with no artwork decoded and nothing to show.
     * Everything it then painted was the flat fill. That is the grey flash, and every attempt to
     * fix it by making this canvas paint *better* was treating a surface that should not have been
     * on screen yet.
     *
     * Fires once the backdrop can actually be drawn, or immediately when there is no backdrop to
     * wait for. The caller still applies its own deadline: a decode that never finishes must delay
     * the picture, never withhold it.
     */
    var onBackdropReady: (() -> Unit)? = null
    private var backdropReadyNotified = false

    /**
     * True while a decode is in flight for artwork this canvas is expected to show.
     *
     * ⚠ **Without this, `backdropImage = null` is ambiguous, and the ambiguity defeated the gate.**
     * `NativePlayerController.prepareOpeningBackdrop` nulls the image twice for opposite reasons:
     * once when there is genuinely no artwork, and once on a cache miss *before* starting the
     * decode. Treating both as "nothing to wait for" fired [onBackdropReady] immediately on every
     * first play of a title - and it is one-shot, so the decoded image could never re-open the gate.
     * The measured result: `first full-size paint=flat` on the first play, `backdrop` on the second.
     */
    @Volatile
    var backdropPending: Boolean = false
        set(value) {
            field = value
            // A decode that failed leaves no artwork and no reason to keep waiting.
            if (!value && backdropImage == null) notifyBackdropReady()
        }
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false
    private var controlsVisible = true
    private var cursorVisible = true

    private companion object {
        /** `NuvioColors.background`, `Color(0xFF0D0D0D)`. Kept in sync by hand; there is no
         *  Compose on this side of the boundary to read the token from. */
        val DEFAULT_SURFACE_BACKGROUND: Color = Color(0x0D, 0x0D, 0x0D)

        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }

        /**
         * ⚠ **Shared across hosts, because a new host is created for every play.**
         *
         * `PlayerEngine.desktop.kt` does `remember { NativePlayerHost() }`, so a per-instance
         * cache starts empty on every play and the flat fill is shown while the scale runs *every
         * time*. Shared, only the first play at a given window size pays - exactly like
         * `NativePlayerController.openingBackdropCache` one layer up, and for the same reason.
         *
         * One entry is enough: the window size rarely changes mid-session and an entry for a
         * previous size is worth nothing, so there is nothing to evict and no policy to get wrong.
         */
        @Volatile
        var preparedBackdrop: PreparedBackdrop? = null

        /**
         * Below this, the canvas is the parked `SwingPanel`, not a surface anyone is looking at.
         *
         * `PlayerEngine.desktop.kt` holds the panel at `requiredSize(1.dp)` until its first paint,
         * so the backdrop arrives - deliberately, before the attach - while this canvas is one
         * pixel. Preparing at that size wastes the scale *and* stores a 1x1 entry in
         * [preparedBackdrop], so the real preparation only started at promotion and the flat fill
         * covered the loading screen for its whole duration, on every play.
         */
        const val MIN_PREPARED_EDGE_PX = 16

        val log = Logger.withTag("NativePlayerHost")
    }

    /**
     * What this canvas fills with before the native player has a frame.
     *
     * ⚠ **Not black, and that is the point.** A heavyweight `Canvas` paints over all Compose
     * content regardless of z-order, so the instant the `SwingPanel` is promoted to full size this
     * fill *replaces* the loading screen the app was drawing - and while it was `Color.BLACK` that
     * replacement was the black flash reported between choosing a source and the loading screen
     * reappearing. Filled with the app's own background instead, the takeover is the same colour
     * the screen it replaces is painted on, so there is nothing to see.
     *
     * Set from Compose so the AMOLED theme, whose background really is black, still matches.
     */
    @Volatile
    var surfaceBackground: Color = DEFAULT_SURFACE_BACKGROUND
        set(value) {
            field = value
            background = value
            repaint()
        }

    init {
        background = surfaceBackground
        ignoreRepaint = false
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                noteCursorActivity()
            }

            override fun mouseDragged(event: MouseEvent) {
                noteCursorActivity()
            }
        })
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = onLaidOut()

            override fun componentShown(event: ComponentEvent) = onLaidOut()

            private fun onLaidOut() {
                // Every platform: promotion to full size is the earliest moment the final canvas
                // size is known, and starting the scale here is what lets the first full-size
                // paint blit a ready backdrop instead of falling back to the flat fill. See
                // `requestPreparation`.
                backdropImage?.let { image ->
                    promotedSize()?.let { (w, h) -> requestPreparation(image, w, h) }
                }
                // On Linux/XWayland a heavyweight Canvas embedded in a Compose SwingPanel is not
                // guaranteed an expose-driven paint() when it is first laid out, so the
                // paint()-based first-full-size-paint signal (which unlocks the native attach)
                // can never fire and playback silently never starts. componentResized fires
                // reliably on layout, so use it to drive the same signal. Still Linux-only: on
                // macOS/Windows the paint-driven signal is what has been tested, and moving it
                // here would change when the native attach is allowed to begin.
                if (DesktopHostOs.current == DesktopHostOs.LINUX) {
                    repaint()
                    notifyFirstPaints()
                }
            }
        })
    }

    private fun notifyFirstPaints() {
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        // ⚠ **"Full size" has to mean full size, not "bigger than one pixel".**
        //
        // `PlayerEngine.desktop.kt` parks the `SwingPanel` at `requiredSize(1.dp)`, which at a 1.4x
        // UI scale is about two pixels - so `width > 1` was satisfied by the *parked* panel and the
        // native attach was unlocked while this canvas was a speck in the corner. The whole native
        // player was then created at that size and resized when the panel was finally promoted,
        // and resizing a live WebView2/mpv container erases it and re-lays it out: 314 ms of flat
        // fill, measured on screen at 14:29:00.479, immediately before this canvas's own first
        // full-size paint. Requiring a real size makes the order attach-after-promotion, so the
        // native window is created once, at the size it keeps.
        if (!firstFullSizePaintNotified &&
            width >= MIN_PREPARED_EDGE_PX &&
            height >= MIN_PREPARED_EDGE_PX
        ) {
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        setCursorVisible(visible)
    }

    fun noteCursorActivity() {
        onCursorActivity?.invoke()
    }

    fun resetCursorVisibility() {
        controlsVisible = true
        setCursorVisible(true)
    }

    private fun setCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    /**
     * The loading screen's backdrop, drawn here for the window where nothing else can.
     *
     * ⚠ **Between the `SwingPanel` being promoted to full size and the controls page painting, this
     * canvas is the only thing on screen** - a heavyweight `Canvas` covers every Compose layer
     * regardless of z-order, and WebView2 needs ~700ms to create and present (measured:
     * `afterAttachMs=696`). Filling flat meant the artwork and the band the user was reading
     * vanished for two-thirds of a second in the middle of an otherwise seamless hand-off.
     *
     * Painting the same image with the same centre-crop and the same scrim makes that window show
     * what was already there. The band is still missing for it - matching Compose text in Java2D is
     * not worth the fidelity risk - but the picture no longer drops out.
     *
     * Null is always valid: no artwork, or it has not finished decoding, and the flat fill is what
     * the user gets. Set from [NativePlayerController].
     */
    @Volatile
    var backdropImage: BufferedImage? = null
        set(value) {
            field = value
            if (value == null) {
                // Only "there is no artwork" ends the wait; "not decoded yet" must not. See
                // [backdropPending].
                if (!backdropPending) notifyBackdropReady()
            } else {
                promotedSize()?.let { (w, h) -> requestPreparation(value, w, h) }
            }
            repaint()
        }

    /**
     * One backdrop, already cropped, scaled and scrimmed for one exact canvas size.
     *
     * ⚠ **The whole point is that [paint] does no image maths at all.** See [requestPreparation].
     */
    private class PreparedBackdrop(
        val source: BufferedImage,
        val width: Int,
        val height: Int,
        val image: BufferedImage,
    ) {
    }

    /** The request in flight, so a burst of repaints does not start a scale per paint. Locked. */
    private var preparing: PreparationKey? = null

    private data class PreparationKey(val source: BufferedImage, val width: Int, val height: Int)

    /**
     * ⚠ **Nothing here may scale an image, and that is the entire reason this method is this
     * shape.** It used to call `drawImage` with a destination rectangle, which is a *software*
     * `sun.java2d.loops.TransformHelper.Transform` over the full-resolution artwork - `ImageIO`
     * hands back whatever the server sent, so a 4K backdrop is scaled 4K-to-window, bilinear, on
     * the AWT event thread, **on every single paint**. Measured on the desktop debug build: one
     * such paint held the UI thread for **2523 ms**, immediately after a source was chosen. That
     * was the second half of the "stutter, then the old loading screen, then ours" report - the
     * canvas was mid-paint and the whole application was frozen inside it.
     *
     * Now the scale, the crop and the scrim all happen once per size on a worker thread and land
     * in [preparedBackdrop]; this is a straight blit of a same-size image. The flat fill is what shows
     * until the first preparation completes, which is the documented fallback anyway.
     */
    override fun paint(graphics: Graphics) {
        graphics.color = surfaceBackground
        graphics.fillRect(0, 0, width, height)
        val source = backdropImage
        if (source != null && width > 0 && height > 0) {
            val ready = preparedBackdrop?.takeIf { it.source === source }
            when {
                ready == null -> {
                    logFirstFullSizePaint("flat", source)
                }
                ready.width == width && ready.height == height -> {
                    graphics.drawImage(ready.image, 0, 0, null)
                    logFirstFullSizePaint("backdrop", source)
                }
                // ⚠ **A near miss must still draw, and this is why the grey flash outlived the
                // container fix.** The backdrop is prepared before the `SwingPanel` is promoted,
                // so the only size available then is the window's content area - and the panel is
                // laid out slightly smaller than that (measured: prepared for the window, painted
                // at 1906x1164). Requiring an exact match meant the artwork was thrown away for a
                // handful of pixels and the flat fill covered the loading screen on every play.
                //
                // Scaling *this* image is cheap in a way scaling the source is not: it is already
                // approximately the right size, so this is a one-frame blit of ~2 megapixels
                // rather than the full-resolution `TransformHelper` that once held the UI thread
                // for 2523 ms. The exact-size version is requested below and takes over on the
                // next paint.
                else -> {
                    graphics.drawImage(ready.image, 0, 0, width, height, null)
                    logFirstFullSizePaint("backdrop~", source)
                }
            }
            if (ready == null || ready.width != width || ready.height != height) {
                requestPreparation(source, width, height)
            }
        }
        notifyFirstPaints()
    }

    /**
     * Builds the canvas-sized backdrop off the event thread, then asks for a repaint.
     *
     * Cheap to call from anywhere and on every paint: a request for a size already prepared, or
     * already being prepared, does nothing. The worker is a plain daemon thread because this runs
     * at most once per player per window size, and a size that changes while one is in flight is
     * simply prepared again when the next paint finds the mismatch.
     */
    /**
     * The size this canvas will occupy once promoted, so the scale can start *before* it is.
     *
     * ⚠ **Using `width`/`height` alone is why the grey flash survived the container fix.** The
     * backdrop is handed over while the panel is still 1 dp, so the only size available at the
     * moment there is time to do the work is the window's - and the panel is promoted to fill the
     * player root, which is the window's content area. Getting it slightly wrong is free: the
     * first full-size paint finds the mismatch and prepares again, which is exactly the behaviour
     * that existed before this.
     */
    /**
     * Says once per canvas whether its first real paint showed the artwork or the flat fill.
     *
     * One line, and it settles what three rounds of guessing could not: if this says `flat`, the
     * grey the user reports is this canvas; if it says `backdrop`, the grey belongs to something
     * else on the stack. The image identity is included because a canvas painting the *previous*
     * title's artwork looks identical in a log that only records "backdrop".
     */
    @Volatile
    private var firstFullSizePaintLogged = false

    private fun notifyBackdropReady() {
        if (backdropReadyNotified) return
        backdropReadyNotified = true
        onBackdropReady?.invoke()
    }

    private fun logFirstFullSizePaint(kind: String, source: BufferedImage?) {
        if (firstFullSizePaintLogged || width < MIN_PREPARED_EDGE_PX) return
        firstFullSizePaintLogged = true
        log.i {
            "first full-size paint=$kind canvas=${width}x$height " +
                "image=${source?.let { System.identityHashCode(it).toString(16) } ?: "none"}"
        }
    }

    private fun promotedSize(): Pair<Int, Int>? {
        if (width >= MIN_PREPARED_EDGE_PX && height >= MIN_PREPARED_EDGE_PX) return width to height
        val window = runCatching { SwingUtilities.getWindowAncestor(this) }.getOrNull() ?: return null
        val insets = window.insets
        val contentWidth = window.width - insets.left - insets.right
        val contentHeight = window.height - insets.top - insets.bottom
        return (contentWidth to contentHeight)
            .takeIf { contentWidth >= MIN_PREPARED_EDGE_PX && contentHeight >= MIN_PREPARED_EDGE_PX }
    }

    private fun requestPreparation(source: BufferedImage, width: Int, height: Int) {
        if (width < MIN_PREPARED_EDGE_PX || height < MIN_PREPARED_EDGE_PX) return
        if (source.width <= 0 || source.height <= 0) return
        val key = PreparationKey(source, width, height)
        synchronized(this) {
            if (preparing == key) return
            preparing = key
        }
        Thread(
            {
                val image = runCatching { renderBackdrop(source, width, height) }.getOrNull()
                synchronized(this) {
                    if (preparing != key) return@Thread
                    preparing = null
                }
                if (image != null) {
                    preparedBackdrop = PreparedBackdrop(source, width, height, image)
                    if (source === backdropImage) notifyBackdropReady()
                    // Thread-safe by contract, and the only way back onto the event thread from
                    // here that does not risk running after the peer is gone.
                    repaint()
                }
            },
            "nuvio-opening-backdrop-scale",
        ).apply { isDaemon = true }.start()
    }

    /**
     * Centre-crop plus the loading screen's scrim, kept numerically identical to
     * `PlaybackLoadingScreen.PlaybackLoadingBackdrop` and to `.opening-scrim`. A scrim that differs
     * by one stop is a visible flash at exactly the moment this exists to cover.
     *
     * Opaque on purpose: it covers the canvas edge to edge by construction (a cover-crop always
     * does), so the flat fill underneath it can never show through and the blit in [paint] needs
     * no blending.
     */
    private fun renderBackdrop(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val configuration = graphicsConfiguration
        val target = configuration?.createCompatibleImage(width, height)
            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics2d = target.createGraphics()
        try {
            graphics2d.color = surfaceBackground
            graphics2d.fillRect(0, 0, width, height)
            paintBackdrop(graphics2d, image, width, height)
        } finally {
            graphics2d.dispose()
        }
        return target
    }

    private fun paintBackdrop(
        graphics2d: Graphics2D,
        image: BufferedImage,
        width: Int,
        height: Int,
    ) {
        graphics2d.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        // ContentScale.Crop: cover the box, preserve the aspect ratio, centre the overflow.
        val scale = maxOf(width.toDouble() / image.width, height.toDouble() / image.height)
        val drawWidth = (image.width * scale).toInt()
        val drawHeight = (image.height * scale).toInt()
        graphics2d.drawImage(
            image,
            (width - drawWidth) / 2,
            (height - drawHeight) / 2,
            drawWidth,
            drawHeight,
            null,
        )
        graphics2d.paint = LinearGradientPaint(
            Point2D.Float(0f, 0f),
            Point2D.Float(0f, height.toFloat()),
            floatArrayOf(0f, 0.334f, 0.667f, 1f),
            arrayOf(
                Color(0, 0, 0, 77),
                Color(0, 0, 0, 153),
                Color(0, 0, 0, 204),
                Color(0, 0, 0, 230),
            ),
        )
        graphics2d.fillRect(0, 0, width, height)
    }

    override fun addNotify() {
        super.addNotify()
        onDisplayableChanged?.invoke(true)
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        // `preparedBackdrop` is deliberately NOT cleared: it is shared with the next player,
        // which is the whole point of caching it. See the note on it.
        synchronized(this) { preparing = null }
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        resetCursorVisibility()
        super.removeNotify()
    }
}
