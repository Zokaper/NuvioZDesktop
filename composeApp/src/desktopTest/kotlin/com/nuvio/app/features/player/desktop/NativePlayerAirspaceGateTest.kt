package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.playback.PlaybackHandover
import com.nuvio.app.features.player.PlayerExitDiagnostics
import java.awt.Color
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePlayerAirspaceGateTest {

    @Test
    fun nativePlayerHostDefaultsToConcealed() {
        val host = NativePlayerHost()
        assertFalse(host.isVisible, "NativePlayerHost must initialize with isVisible = false")
    }

    @Test
    fun promoteNativeSurfaceExposesHostOnEdt() {
        val host = NativePlayerHost()
        var promotedHandle: Long? = null
        val controller = NativePlayerController(
            host = host,
            nativeCreate = { _, _, _, _, _, _, _, _, _ -> 42L },
            nativeDispose = {},
            nativePromoteOpeningContainer = { handle -> promotedHandle = handle },
        )
        setNativeHandleForTest(controller, 42L)

        var notifiedPromoted: Boolean? = null
        controller.onSurfacePromotedChanged = { promoted -> notifiedPromoted = promoted }

        assertFalse(controller.isNativeSurfacePromoted())
        assertFalse(host.isVisible)

        val latch = CountDownLatch(1)
        SwingUtilities.invokeLater {
            controller.promoteNativeSurface()
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertTrue(controller.isNativeSurfacePromoted())
        assertTrue(host.isVisible, "Host canvas must become visible upon promotion")
        assertEquals(true, notifiedPromoted, "onSurfacePromotedChanged must fire with true upon promotion")
        assertEquals(42L, promotedHandle, "nativePromoteOpeningContainer must be called with the active handle")
    }

    @Test
    fun releaseBeforeNavigationConcealsHostImmediately() {
        val host = NativePlayerHost()
        val wrapper = JPanel(null).apply {
            setBounds(0, 0, 1920, 1080)
            add(host)
        }
        host.surfaceBackground = Color(13, 13, 13)
        val controller = NativePlayerController(
            host = host,
            nativeCreate = { _, _, _, _, _, _, _, _, _ -> 42L },
            nativeDispose = {},
            nativePromoteOpeningContainer = {},
        )
        setNativeHandleForTest(controller, 42L)

        var notifiedPromoted: Boolean? = null
        controller.onSurfacePromotedChanged = { promoted -> notifiedPromoted = promoted }

        // First promote
        val latch = CountDownLatch(1)
        SwingUtilities.invokeLater {
            controller.promoteNativeSurface()
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(host.isVisible)
        assertEquals(true, notifiedPromoted)

        // Then releaseBeforeNavigation (simulating Escape/Back at T0)
        PlayerExitDiagnostics.recordT0("unit_test")
        val releaseLatch = CountDownLatch(1)
        SwingUtilities.invokeLater {
            controller.releaseBeforeNavigation {
                releaseLatch.countDown()
            }
        }
        assertTrue(releaseLatch.await(2, TimeUnit.SECONDS))
        assertFalse(host.isVisible, "Host canvas must be concealed immediately at T0 upon releaseBeforeNavigation")
        assertFalse(wrapper.isVisible, "The fullscreen Swing interop wrapper must be concealed too")
        assertEquals(1, wrapper.width)
        assertEquals(1, wrapper.height)
        assertEquals(host.surfaceBackground, wrapper.background)
        assertFalse(controller.isNativeSurfacePromoted(), "Controller must mark surface unpromoted on releaseBeforeNavigation")
        assertEquals(false, notifiedPromoted, "onSurfacePromotedChanged must fire with false upon releaseBeforeNavigation")
        PlayerExitDiagnostics.recordT2("unit_test")
    }

    @Test
    fun playbackHandoverGatingRequiresRealFrameAndNonLoading() {
        // Buffering / loading without dimensions -> not ready
        assertFalse(
            PlaybackHandover.hasFirstFrame(
                isLoading = true,
                isPlaying = false,
                positionMs = 0L,
                videoWidth = 0,
                videoHeight = 0,
            ),
        )

        // Loading flag still true even if dimensions known -> not ready yet
        assertFalse(
            PlaybackHandover.hasFirstFrame(
                isLoading = true,
                isPlaying = false,
                positionMs = 0L,
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )

        // Decoded dimensions ready and loading finished -> READY!
        assertTrue(
            PlaybackHandover.hasFirstFrame(
                isLoading = false,
                isPlaying = true,
                positionMs = 0L,
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )

        // Audio or advancing playback without explicit dimensions -> READY!
        assertTrue(
            PlaybackHandover.hasFirstFrame(
                isLoading = false,
                isPlaying = true,
                positionMs = 120L,
                videoWidth = 0,
                videoHeight = 0,
            ),
        )
    }

    private fun setNativeHandleForTest(controller: NativePlayerController, value: Long) {
        NativePlayerController::class.java.getDeclaredField("handle").also { field ->
            field.isAccessible = true
            field.setLong(controller, value)
        }
    }
}
