package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage

internal class NativePlayerHost : Canvas() {
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    var onCursorActivity: (() -> Unit)? = null
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
        // On Linux/XWayland a heavyweight Canvas embedded in a Compose SwingPanel is not
        // guaranteed an expose-driven paint() when it is first laid out, so the paint()-based
        // first-full-size-paint signal (which unlocks the native attach) can never fire and
        // playback silently never starts. componentResized fires reliably on layout, so use it
        // to drive the same signal. Linux-only to keep macOS/Windows behaviour byte-identical.
        if (DesktopHostOs.current == DesktopHostOs.LINUX) {
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    repaint()
                    notifyFirstPaints()
                }

                override fun componentShown(event: ComponentEvent) {
                    repaint()
                    notifyFirstPaints()
                }
            })
        }
    }

    private fun notifyFirstPaints() {
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
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

    override fun paint(graphics: Graphics) {
        graphics.color = surfaceBackground
        graphics.fillRect(0, 0, width, height)
        notifyFirstPaints()
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
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        resetCursorVisibility()
        super.removeNotify()
    }
}
