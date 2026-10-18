package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.configureSwingGlobalsForCompose
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.debug.DesktopDebugLog
import com.nuvio.app.core.debug.EdtStallWatchdog
import com.nuvio.app.core.debug.SelfTestHooks
import com.nuvio.app.core.debug.selftest.DesktopSelfTest
import com.nuvio.app.core.debug.selftest.SelfTestOverlay
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.diagnostics.SentryInitializer
import com.nuvio.app.core.ui.DesktopWheelSmoothing
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.ProvideDesktopPrefetchScheduler
import com.nuvio.app.features.discordrpc.DiscordPresenceManager
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.preloadP2pStreamingEngineAsync
import com.nuvio.app.features.plugins.configureDesktopQuickJsLibrary
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.desktop.DesktopAppFullscreenController
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopWindowGeometry
import com.nuvio.app.features.player.desktop.DesktopWindowModeStorage
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.configureMacosWindowBeforePeer
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.settings.installDesktopUiZoomShortcuts
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import com.nuvio.app.features.player.desktop.trackMaximizedBoundsForCurrentScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.AppIconRepository
import com.nuvio.app.features.settings.applyDesktopRendererPreference
import com.nuvio.app.features.settings.previewResource
import com.nuvio.app.features.watchparty.PartyDepartureMode
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.settings.transparentPreviewResource
import com.nuvio.app.features.settings.SocialSettingsExtensions
import com.nuvio.app.features.tray.DesktopBackgroundStart
import com.nuvio.app.features.tray.DesktopClosePromptHost
import com.nuvio.app.features.tray.DesktopCloseAction
import com.nuvio.app.features.tray.DesktopSocialPopupWindow
import com.nuvio.app.features.tray.DesktopSocialPopups
import com.nuvio.app.features.tray.DesktopSocialSettingsSections
import com.nuvio.app.features.tray.DesktopSingleInstance
import com.nuvio.app.features.tray.DesktopStartWithWindows
import com.nuvio.app.features.tray.DesktopTrayController
import com.nuvio.app.features.tray.DesktopTrayPreferences
import androidx.compose.ui.window.Tray
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.Desktop
import javax.imageio.ImageIO
import java.awt.Color as AwtColor
import javax.swing.JComponent
import javax.swing.JOptionPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val NuvioDesktopNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val MacosDarkAquaAppearance = "NSAppearanceNameDarkAqua"
private const val WindowStateSaveDelayMillis = 300L

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // First, so the rest of startup is inside the capture. No-op unless -Dnuvio.debugTools=true.
    DesktopDebugLog.install()?.let { logFile -> println("Nuvio debug log: $logFile") }
    // Start with Windows: straight to the tray, with no window until the user opens one.
    val startInTray = DesktopStartWithWindows.isBackgroundLaunch(args)
    // Before anything else starts: a second launch hands its links to the running copy, which shows its
    // window, and exits here. The player smoke run is its own throwaway process.
    if (System.getProperty("nuvio.desktop.smokePlayerUrl").isNullOrBlank() &&
        System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL").isNullOrBlank()
    ) {
        DesktopSingleInstance.enforce(args.map(String::trim).filter(::isDesktopAppUrl), background = startInTray)
    }
    // Second, so a stall in the rest of startup is attributed rather than guessed at. Also a no-op
    // outside a debug build.
    EdtStallWatchdog.install()
    // On Linux, initialize GTK BEFORE AWT/Compose/Skia to prevent GdkDisplayManager
    // type registration conflict (Skiko partially loads GDK without full GTK init).
    if (System.getProperty("os.name", "").lowercase().contains("linux")) {
        runCatching { NativePlayerBridge.initGtkEarly() }
    }
    applyDesktopRendererPreference()
    // Before anything composes a scrollable: Compose reads this once. The app smooths wheel and
    // touchpad input itself on Windows (see DesktopWheelSmoothing).
    DesktopWheelSmoothing.disableComposeWheelSmoothing()
    SentryInitializer.start()
    configureDesktopQuickJsLibrary()
    configureDesktopChrome()
    configureLinuxSwingGlobalsBeforeAwt()
    installDesktopOpenUriHandler()
    handleDesktopLaunchArgs(args)
    // In the tray these wait for the window: the player warm-up alone starts ~80 MB of WebView2 helpers.
    val warmUpPlayer = {
        preloadNativePlayerBridgeAsync()
        // Same reason, one subsystem over: the player screen touches `P2pStreamingEngine` while it
        // composes, so without this its class loading lands on the UI thread at the exact moment a
        // source is chosen. See the note on the function.
        preloadP2pStreamingEngineAsync()
    }
    if (startInTray) DesktopTrayController.startHidden(onFirstShow = warmUpPlayer) else warmUpPlayer()
    // Load cached profile data synchronously so the profile color is available
    // on the very first Compose frame (matching Android's SharedPreferences behavior).
    ProfileRepository.loadCachedProfiles()
    AppIconRepository.ensureLoaded()
    DiscordPresenceManager.start()
    // Social V2 tray mode: the window can close while the process (and Social) carries on.
    AppGateResume.enabled = true
    SocialSettingsExtensions.platformSections = { isTablet -> DesktopSocialSettingsSections(isTablet) }
    DesktopSocialPopups.start()
    if (startInTray) DesktopBackgroundStart.start()
    DesktopStartWithWindows.refresh()

    application {
        val appIconState by AppIconRepository.state.collectAsState()
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }
        val wasFullscreenOnLastExit = remember { DesktopWindowModeStorage.loadWasFullscreen() }
        val wasMaximizedOnLastExit = remember { DesktopWindowModeStorage.loadWasMaximized() }
        val savedGeometry = remember { DesktopWindowModeStorage.loadWindowedGeometry() }
        val restoresMaximizedWindowPlacement = DesktopHostOs.current != DesktopHostOs.MACOS
        val initialPlacement = when {
            wasFullscreenOnLastExit && DesktopHostOs.current != DesktopHostOs.WINDOWS -> {
                WindowPlacement.Fullscreen
            }
            wasMaximizedOnLastExit == false && savedGeometry != null -> {
                WindowPlacement.Floating
            }
            restoresMaximizedWindowPlacement -> {
                WindowPlacement.Maximized
            }
            else -> WindowPlacement.Floating
        }
        val isStartingMaximizedOrFullscreen =
            initialPlacement == WindowPlacement.Maximized || initialPlacement == WindowPlacement.Fullscreen
        val maxScreenBounds = remember {
            runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            }.getOrNull()
        }
        val initialWidth = when {
            isStartingMaximizedOrFullscreen && maxScreenBounds != null -> maxScreenBounds.width.dp
            savedGeometry != null -> savedGeometry.width.dp
            else -> 1280.dp
        }
        val initialHeight = when {
            isStartingMaximizedOrFullscreen && maxScreenBounds != null -> maxScreenBounds.height.dp
            savedGeometry != null -> savedGeometry.height.dp
            else -> 820.dp
        }
        val windowState = rememberWindowState(
            width = initialWidth,
            height = initialHeight,
            position = savedGeometry?.let { WindowPosition.Absolute(x = it.x.dp, y = it.y.dp) }
                ?: WindowPosition.PlatformDefault,
            // Windows fullscreen is emulated natively (see DesktopAppFullscreenController)
            // rather than driven by WindowPlacement, so it's restored separately below.
            placement = initialPlacement,
        )
        val fullscreenController = remember { DesktopAppFullscreenController() }
        val windowVisible by DesktopTrayController.windowVisible.collectAsState()
        val trayPreferences by DesktopTrayPreferences.state.collectAsState()

        fun quitApp() {
            P2pStreamingEngine.shutdown()
            DiscordPresenceManager.shutdown()
            SentryInitializer.close()
            exitApplication()
        }

        fun openFromTray() {
            DesktopTrayController.show()
            DesktopTrayController.bringToFront()
        }

        // The tray icon is where Nuvio lives once closing hides it: shown unless closing quits, and always
        // while the window is hidden (otherwise there would be no way back).
        if (smokePlayerUrl == null && (trayPreferences.closeAction != DesktopCloseAction.Quit || !windowVisible)) {
            Tray(
                icon = painterResource(appIconState.selected.transparentPreviewResource),
                tooltip = "Nuvio Z",
                onAction = ::openFromTray,
                menu = {
                    Item("Open Nuvio Z", onClick = ::openFromTray)
                    Separator()
                    Item("Quit Nuvio Z", onClick = ::quitApp)
                },
            )
        }
        if (smokePlayerUrl == null) DesktopSocialPopupWindow()

        if (windowVisible) SwingWindow(
            onCloseRequest = {
                val partyState = WatchPartyRepository.uiState.value
                val party = partyState.party
                // Hiding to the tray tears the player down too, so a party is left first either way.
                if (party != null) {
                    val isHost = party.hostProfileId == partyState.activeProfileId
                    val options = if (isHost) {
                        arrayOf("Leave and transfer", "End party", "Cancel")
                    } else {
                        arrayOf("Leave party", "Cancel")
                    }
                    val answer = JOptionPane.showOptionDialog(
                        null,
                        if (isHost) "Transfer hosting to the next person, or end the party for everyone."
                        else "You will leave this Watch Together party.",
                        "Leave Watch Together?",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        options,
                        options.last(),
                    )
                    val mode = when {
                        answer == 0 -> PartyDepartureMode.LEAVE_AND_TRANSFER
                        isHost && answer == 1 -> PartyDepartureMode.END_PARTY
                        else -> return@SwingWindow
                    }
                    runBlocking { WatchPartyRepository.depart(mode) }
                }
                if (smokePlayerUrl != null) quitApp() else DesktopTrayController.onCloseRequested(::quitApp)
            },
            title = if (smokePlayerUrl == null) "Nuvio Z" else "Nuvio Z Player Smoke",
            state = windowState,
            icon = painterResource(appIconState.selected.transparentPreviewResource),
            init = ::configureMacosWindowBeforePeer,
        ) {
            SideEffect {
                window.background = NuvioDesktopNativeBackground
                window.rootPane.background = NuvioDesktopNativeBackground
                window.contentPane.background = NuvioDesktopNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window, appIconState.selected) {
                val backgroundSuffix = "-transparent"
                val iconPath = "icons/app-icon-${appIconState.selected.key}$backgroundSuffix.png"
                Thread.currentThread().contextClassLoader.getResourceAsStream(iconPath)?.use { stream ->
                    ImageIO.read(stream)?.let { image ->
                        window.iconImages = listOf(image)
                    }
                }
            }

            LaunchedEffect(window) {
                applyNativeDesktopWindowChrome(window)
                installLinuxExtendedMouseButtons()
                DesktopWheelSmoothing.install()
                // Windows fullscreen is emulated natively and isn't reflected by
                // WindowPlacement, so it must be re-applied once the window peer exists.
                // Read again rather than the launch-time value: a window re-created from the tray restores
                // the mode it was last in, not the one Nuvio started in.
                fullscreenController.applyRestoredFullscreenState(window, windowState, DesktopWindowModeStorage.loadWasFullscreen())
            }
            LaunchedEffect(windowState) {
                // Covers OS-driven placement changes too (e.g. the native macOS
                // green-button fullscreen toggle), not just our own shortcuts.
                if (DesktopHostOs.current != DesktopHostOs.WINDOWS) {
                    snapshotFlow { windowState.placement }
                        .collect { placement ->
                            DesktopWindowModeStorage.saveWasFullscreen(placement == WindowPlacement.Fullscreen)
                        }
                }
            }
            LaunchedEffect(windowState) {
                // Only persist geometry while windowed: fullscreen/native-Windows-fullscreen
                // coordinates aren't a meaningful "windowed position" to restore later.
                // Z: a drag-resize emits dozens of these a second. The state is read here, on the
                // UI thread, but written once the window has settled, off it - the synchronous
                // per-event rewrites stalled every resize and were the likeliest cause of a crash
                // reported while resizing rapidly (see DesktopStorage.Store.persist).
                var pendingMaximized: Boolean? = null
                var pendingGeometry: DesktopWindowGeometry? = null
                var pendingSave: Job? = null
                snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }
                    .collect { (placement, position, size) ->
                        val isFullscreen = fullscreenController.isFullscreen(window, windowState)
                        if (!isFullscreen && restoresMaximizedWindowPlacement) {
                            pendingMaximized = placement == WindowPlacement.Maximized
                        }
                        val isWindowed = placement == WindowPlacement.Floating && !isFullscreen
                        if (isWindowed && position.isSpecified) {
                            pendingGeometry = DesktopWindowGeometry(
                                x = position.x.value,
                                y = position.y.value,
                                width = size.width.value,
                                height = size.height.value,
                            )
                        }
                        pendingSave?.cancel()
                        pendingSave = launch {
                            delay(WindowStateSaveDelayMillis)
                            val maximized = pendingMaximized
                            val geometry = pendingGeometry
                            pendingMaximized = null
                            pendingGeometry = null
                            withContext(Dispatchers.IO) {
                                maximized?.let(DesktopWindowModeStorage::saveWasMaximized)
                                geometry?.let(DesktopWindowModeStorage::saveWindowedGeometry)
                            }
                        }
                    }
            }
            DisposableEffect(window, windowState) {
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle(
                    handler = { targetWindow ->
                        if (targetWindow == null || targetWindow === window) {
                            fullscreenController.toggle(window, windowState)
                            DesktopWindowModeStorage.saveWasFullscreen(
                                fullscreenController.isFullscreen(window, windowState),
                            )
                        }
                    },
                    isFullscreen = { targetWindow ->
                        (targetWindow == null || targetWindow === window) &&
                            fullscreenController.isFullscreen(window, windowState)
                    },
                )
                val uninstallFullscreenShortcuts = installDesktopAppFullscreenShortcuts(window)
                val uninstallZoomShortcuts = installDesktopUiZoomShortcuts()
                val untrackMaximizedBounds = window.trackMaximizedBoundsForCurrentScreen()
                onDispose {
                    fullscreenController.dispose(window)
                    uninstallFullscreenShortcuts()
                    uninstallZoomShortcuts()
                    untrackMaximizedBounds()
                    unregisterFullscreenToggle()
                }
            }

            // The self-test needs the window itself: only a screen grab can photograph the native
            // mpv surface and the WebView2 controls, and only a real synthetic click travels the
            // path where a non-consuming overlay goes wrong. No-op outside a debug build.
            DisposableEffect(window) {
                DesktopSelfTest.install(window)
                onDispose { SelfTestHooks.launch = null }
            }

            // Pop-ups always use their own window; whether this one is in front only decides whether another
            // app's fullscreen may hold them back.
            DisposableEffect(window) {
                DesktopTrayController.mainWindow = window
                val focusListener = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) = DesktopSocialPopups.setWindowFocused(true)
                    override fun windowLostFocus(e: WindowEvent?) = DesktopSocialPopups.setWindowFocused(false)
                }
                window.addWindowFocusListener(focusListener)
                DesktopSocialPopups.setWindowFocused(window.isFocused)
                onDispose {
                    window.removeWindowFocusListener(focusListener)
                    DesktopSocialPopups.setWindowFocused(false)
                    if (DesktopTrayController.mainWindow === window) DesktopTrayController.mainWindow = null
                }
            }

            if (smokePlayerUrl == null) {
                DesktopWheelSmoothing.FrameDriver()
                ProvideDesktopPrefetchScheduler {
                    App()
                }
                SelfTestOverlay()
                DesktopClosePromptHost(onQuit = ::quitApp)
            } else {
                // The player surface reads LocalNuvioPlatformDensity, which only
                // NuvioTheme provides — the bare smoke harness must supply it too.
                NuvioTheme {
                    PlatformPlayerSurface(
                        sourceUrl = smokePlayerUrl,
                        modifier = Modifier.fillMaxSize(),
                        onControllerReady = {},
                        onSnapshot = {},
                        onError = {},
                    )
                }
            }
        }
    }
}

private fun configureDesktopChrome() {
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.appearance", MacosDarkAquaAppearance)
    }
}

// application {} applies Compose's Swing globals, which on Linux include Skiko's
// display-scale detection (it sets sun.java2d.uiScale). AWT reads that property
// once, when its graphics environment starts, and installDesktopOpenUriHandler()
// starts it before application {} runs, which left the UI at 1x on HiDPI Linux
// desktops (#514). Apply the globals before anything touches AWT.
@OptIn(ExperimentalComposeUiApi::class)
private fun configureLinuxSwingGlobalsBeforeAwt() {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return
    if (System.getProperty("compose.application.configure.swing.globals") != "true") return
    // Skiko's detection overwrites sun.java2d.uiScale, so keep an explicitly
    // set scale (the documented #514 workaround) working by skipping it.
    configureSwingGlobalsForCompose(
        useAutoDpiOnLinux = System.getProperty("sun.java2d.uiScale") == null &&
            System.getProperty("skiko.linux.autodpi", "true") == "true",
    )
}

private fun installDesktopOpenUriHandler() {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return

    runCatching {
        desktop.setOpenURIHandler { event ->
            event.uri
                ?.toString()
                ?.trim()
                ?.takeIf(::isDesktopAppUrl)
                ?.let(::handleAppUrl)
        }
    }
}

private fun handleDesktopLaunchArgs(args: Array<String>) {
    args.asSequence()
        .map(String::trim)
        .filter(::isDesktopAppUrl)
        .forEach(::handleAppUrl)
}

private fun isDesktopAppUrl(value: String): Boolean =
    value.startsWith("nuvio://", ignoreCase = true) ||
        value.startsWith("stremio://", ignoreCase = true)
