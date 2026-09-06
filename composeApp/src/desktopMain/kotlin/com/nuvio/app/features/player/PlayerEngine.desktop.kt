package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.LocalNuvioPlatformDensity
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.desktopFullscreenChanges
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    sourceAvailable: Boolean,
) {
    if (DesktopHostOs.current == DesktopHostOs.MACOS ||
        DesktopHostOs.current == DesktopHostOs.WINDOWS ||
        DesktopHostOs.current == DesktopHostOs.LINUX
    ) {
        NativePlayerSurface(
            sourceUrl = sourceUrl,
            sourceAvailable = sourceAvailable,
            sourceHeaders = sourceHeaders,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            initialPositionMs = initialPositionMs ?: 0L,
            initialPositionRequestKey = initialPositionRequestKey,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onInitialPositionHandled = onInitialPositionHandled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }

    DesktopStubPlayerSurface(
        modifier = modifier,
        initialPositionRequestKey = initialPositionRequestKey,
        onInitialPositionHandled = onInitialPositionHandled,
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
    )
}

@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    sourceAvailable: Boolean,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    initialPositionRequestKey: String?,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val platformDensity = LocalNuvioPlatformDensity.current
    val host = remember { NativePlayerHost() }
    val controller = remember(host) { NativePlayerController(host) }
    val hostFirstPaintComplete = remember { mutableStateOf(false) }
    /**
     * Whether the canvas may cover the loading screen yet. See `NativePlayerHost.onBackdropReady`.
     *
     * ⚠ **Promotion is a hand-over, not a layout detail.** The instant this panel fills its parent,
     * the heavyweight canvas behind it replaces every Compose layer on screen - including the
     * loading screen the user is reading. Gating that on `onFirstPaint` alone promoted it while the
     * canvas was still one pixel with nothing decoded, so the hand-over was to a flat fill: the
     * grey flash between the loading screen and the player.
     */
    val hostBackdropReady = remember { mutableStateOf(false) }
    val hostFirstFullSizePaintComplete = remember { mutableStateOf(false) }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestOnError = rememberUpdatedState(onError)
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val decoderPriority = playerSettings.decoderPriority
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled

    SideEffect {
        onControllerReady(controller)
    }

    DisposableEffect(host) {
        host.onDisplayableChanged = { displayable ->
            if (!displayable) {
                hostFirstPaintComplete.value = false
                hostFirstFullSizePaintComplete.value = false
            }
        }
        host.onFirstPaint = {
            hostFirstPaintComplete.value = true
        }
        host.onBackdropReady = {
            hostBackdropReady.value = true
        }
        host.onFirstFullSizePaint = {
            hostFirstFullSizePaintComplete.value = true
        }
        onDispose {
            host.onDisplayableChanged = null
            host.onFirstPaint = null
            host.onFirstFullSizePaint = null
            host.onBackdropReady = null
        }
    }

    // ⚠ **A backdrop that never decodes must delay the picture, never withhold it.** The gate above
    // waits for artwork so the hand-over is invisible; this bounds that wait, so a dead image URL
    // or an offline cache costs a moment of loading screen and nothing else. Every failure path in
    // `prepareOpeningBackdrop` is silent by design, so there is no error to wait on - only a clock.
    LaunchedEffect(host, hostFirstPaintComplete.value) {
        if (!hostFirstPaintComplete.value || hostBackdropReady.value) return@LaunchedEffect
        delay(BACKDROP_READY_DEADLINE_MS)
        hostBackdropReady.value = true
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> latestOnPlayerControlsAction.value(action) },
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    DisposableEffect(controller, sourceAvailable, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    // The controls overlay owns the player shortcuts. After alt-tab, desktop
    // window focus can return to the AWT/Compose host instead of the embedded
    // WebView, so explicitly hand keyboard focus back to the native controls
    // whenever the player window becomes active again.
    DisposableEffect(controller, hostFirstFullSizePaintComplete.value) {
        val uninstall = if (hostFirstFullSizePaintComplete.value) {
            controller.installWindowFocusForwarding()
        } else {
            null
        }
        onDispose { uninstall?.invoke() }
    }

    LaunchedEffect(
        controller,
        sourceAvailable,
        sourceUrl,
        playbackHeaders,
        decoderPriority,
        nvidiaRtxSuperResolutionEnabled,
        hostFirstFullSizePaintComplete.value,
        initialPositionMs,
        initialPositionRequestKey,
    ) {
        if (!sourceAvailable || !hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        delay(16L)
        controller.attach(
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            onError = { message -> latestOnError.value(message) },
        )
        initialPositionRequestKey?.let { key ->
            latestOnInitialPositionHandled.value(key, initialPositionMs > 0L)
        }
        onControllerReady(controller)
    }

    LaunchedEffect(controller, sourceAvailable, playWhenReady) {
        if (!sourceAvailable) return@LaunchedEffect
        if (playWhenReady) {
            controller.play()
        } else {
            controller.pause()
        }
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, playerControlsState) {
        controller.updateControls(playerControlsState)
    }

    LaunchedEffect(controller) {
        desktopFullscreenChanges.drop(1).collect {
            controller.onDesktopFullscreenChanged()
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            onSnapshot(controller.snapshot())
            delay(500L)
        }
    }

    // ⚠ **The player's ground is the app's background, not black.**
    //
    // The loading surface `PlaybackLoadingHost` draws is painted on `nuvio.colors.background`, and
    // this root used to be `Color.Black` - so pushing `PlayerRoute` faded a *black* screen in
    // under a screen that was `#0D0D0D`, and the dip between them is the black frame reported
    // between choosing a source and the loading screen appearing. The `SwingPanel` and the AWT
    // canvas behind it are given the same colour for the same reason: a heavyweight component
    // paints over all Compose content the instant it is promoted to full size, so whatever it
    // fills with *is* the hand-over.
    val surfaceGround = MaterialTheme.nuvio.colors.background
    LaunchedEffect(host, surfaceGround) {
        host.surfaceBackground = java.awt.Color(surfaceGround.toArgb(), true)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceGround),
    ) {
        CompositionLocalProvider(LocalDensity provides platformDensity) {
            SwingPanel(
                factory = {
                    host
                },
                // ⚠ **Parked by size, and it must stay that way.**
                //
                // Parking by *offset* instead - full size, translated off screen - was tried to
                // avoid the erase Windows performs when a heavyweight component is resized (two
                // frames of grey at the promotion, measured). It removed the grey and **broke
                // playback**: every attempt failed through to "attempt 3 of 3" and dropped the user
                // on the source list. A native player whose host canvas is outside the window does
                // not start. The two frames are the price of a player that works.
                modifier = if (hostFirstPaintComplete.value && hostBackdropReady.value) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .requiredSize(1.dp)
                },
                background = surfaceGround,
            )
        }
    }
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    initialPositionRequestKey: String?,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
    }

    LaunchedEffect(initialPositionRequestKey) {
        initialPositionRequestKey?.let { key -> onInitialPositionHandled(key, false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Desktop in-app playback is not available yet.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private class DesktopStubPlayerController : PlayerEngineController {
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
    override fun retry() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun selectAudioTrack(index: Int) = Unit
    override fun selectSubtitleTrack(index: Int) = Unit
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}

/**
 * How long promotion waits for the opening backdrop before covering the loading screen anyway.
 *
 * Generous, because the cost of waiting is a loading screen the user is already reading, and the
 * cost of not waiting is the grey flash this gate exists to remove. The decode is cached by URL in
 * `NativePlayerController`, so only the first play of a title can approach this at all.
 */
private const val BACKDROP_READY_DEADLINE_MS: Long = 1_200L