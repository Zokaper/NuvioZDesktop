package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

/**
 * Reaction Mode's single owner of state. App-scoped on purpose: Nuvio Z tears the native player
 * down and builds a new one for every source and every episode, and the reaction session has to
 * outlive that. Every mutable field is confined to [scope]'s single thread.
 *
 * The episode is the playback master and is never paused, seeked or sped up from here; the only
 * things written to the main player are its volume (reaction audio is primary) and, in
 * single-screen mode, the margins that shrink the episode into the middle of the window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object ReactionSession {
    private val log = Logger.withTag("ReactionMode")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private data class Master(val playerHandle: Long, val hostViewPtr: Long)

    /** Written from the controller's threads, read from [scope]. 0 = no player. */
    private val attachedHandle = AtomicLong(0)
    private var master: Master? = null
    private var uiReadyFor = 0L
    private var injectJob: Job? = null

    private var preferences = ReactionPreferencesStore.load()
    private var panelOpen = false
    private var source: ReactionSource? = null
    private var stage: ReactionStage? = null
    private var offsetMs = 0L
    private val engine = ReactionSyncEngine()
    private val mixer = ReactionAudioMixer(preferences.audio)
    private var tickJob: Job? = null
    private var error: String? = null
    private var toolsBusy = false
    private var toolsMessage: String? = null
    private var reactionDurationMs: Long? = null
    private var reactionAspect: Double? = null
    private var appliedLayoutKey: String? = null
    private var dim = 0.0
    private var lastDecision: SyncDecision? = null
    private var restoreMainVolume: Double? = null
    private var lastUiJson: String? = null
    private var loading = false
    /** What yt-dlp resolved for the current web source; reused when the stage is reopened. */
    private var resolvedStream: String? = null
    /** Web loads fail transiently (YouTube refusing one stream URL); retried before giving up. */
    private var loadRetries = 0

    // Single-screen: the arrangement for the current surface, and where the episode is drawn right
    // now (it animates between that arrangement and the full surface around long gaps).
    private var singleLayout: SingleScreenLayout? = null
    private var singleSurface: LayoutRect? = null
    private var movieNow: LayoutRect? = null
    private var movieExpanded = false

    /** What the session is doing, published every tick for logs and the integration harness. */
    @Volatile
    var debugState: ReactionDebugState = ReactionDebugState()
        private set

    /** Harness only: put the "second monitor" stage here instead of on a real display. */
    @Volatile
    var monitorOverride: java.awt.Rectangle? = null

    /** Harness only: extra mpv options for the stage (the harness passes ao=null so it is silent). */
    @Volatile
    var extraStageOptionsForTest: List<String> = emptyList()

    /** Harness only: mpv's own screenshot of the stage window, so nothing else on screen is captured. */
    fun stagePropertyForTest(name: String): String? = stage?.get(name)

    fun stageScreenshotForTest(path: String) {
        scope.launch { stage?.command("screenshot-to-file", path, "window") }
    }

    /** Starts (or replaces) the reaction video. The panel's buttons and the browser link both end here. */
    fun startSource(newSource: ReactionSource) {
        scope.launch {
            start(newSource)
            pushUi(force = true)
        }
    }

    // --- Called by the player (NativePlayerController seams) ---------------------------------

    fun onPlayerAttached(playerHandle: Long, hostViewPtr: Long) {
        if (!ReactionModeFlag.enabled || !ReactionNative.available) return
        attachedHandle.set(playerHandle)
        scope.launch {
            master = Master(playerHandle, hostViewPtr)
            uiReadyFor = 0L
            appliedLayoutKey = null
            mixer.reset()
            engine.reset()
            startInjection(playerHandle)
            if (stage != null && preferences.mode == StageMode.SINGLE) {
                // The previous player's container (the stage's parent) is gone; rebuild inside the new one.
                reopenStage()
            }
            log.i { "player attached handle=$playerHandle session=${source != null}" }
        }
    }

    /** Called with the controller's lifecycle lock held: must not block or touch native code. */
    fun onPlayerDetached(playerHandle: Long) {
        if (!attachedHandle.compareAndSet(playerHandle, 0L)) return
        scope.launch {
            if (master?.playerHandle != playerHandle) return@launch
            master = null
            injectJob?.cancel()
            stage?.apply(FollowerCommand.SetPaused(true))
            if (preferences.mode == StageMode.SINGLE) closeStage(keepSource = true)
            log.i { "player detached handle=$playerHandle" }
        }
    }

    /** Page messages; true when the event belonged to Reaction Mode. */
    fun onControlsEvent(playerHandle: Long, type: String, value: Double): Boolean {
        if (!type.startsWith("reaction")) return false
        scope.launch { handleEvent(playerHandle, type, value) }
        return true
    }

    // --- Page --------------------------------------------------------------------------------

    private fun startInjection(playerHandle: Long) {
        injectJob?.cancel()
        injectJob = scope.launch {
            val script = ReactionControlsUi.installScript()
            // The page may not be ready yet; the script is idempotent and acknowledges with reactionReady.
            repeat(60) {
                if (uiReadyFor == playerHandle || master?.playerHandle != playerHandle) return@launch
                ReactionNative.mainRunControlsScript(playerHandle, script)
                delay(500)
            }
            log.w { "controls page never acknowledged the reaction UI handle=$playerHandle" }
        }
    }

    private suspend fun handleEvent(playerHandle: Long, type: String, value: Double) {
        if (master?.playerHandle != playerHandle) return
        when (type) {
            "reactionReady" -> {
                uiReadyFor = playerHandle
                debugState = debugState.copy(playerHandle = playerHandle, uiReady = true)
                lastUiJson = null
            }
            "reactionToggle" -> panelOpen = !panelOpen
            "reactionClose" -> panelOpen = false
            "reactionDismissError" -> error = null
            "reactionPickFile" -> pickFile()?.let { start(ReactionSource.LocalFile(it)) }
            "reactionPasteLink" -> pasteLink()
            "reactionInstallTools" -> installTools()
            "reactionMode" -> setMode(if (value >= 0.5) StageMode.SINGLE else StageMode.DUAL)
            "reactionMonitor" -> {
                val monitors = ReactionMonitors.list()
                monitors.getOrNull(value.toInt())?.let { chosen ->
                    preferences = preferences.copy(monitorId = chosen.id)
                    ReactionPreferencesStore.save(preferences)
                    if (stage != null && preferences.mode == StageMode.DUAL) reopenStage()
                }
            }
            "reactionMovieSize" -> {
                preferences = preferences.copy(movieFraction = (value / 100.0).coerceIn(0.4, 0.85))
                ReactionPreferencesStore.save(preferences)
                appliedLayoutKey = null
            }
            "reactionNudge" -> {
                offsetMs += value.toLong()
                engine.reset()
            }
            "reactionVolume" -> updateAudio { it.copy(reactionVolume = value.coerceIn(0.0, 100.0)) }
            "reactionMovieVolume" -> updateAudio { it.copy(movieVolumeWithReactions = value.coerceIn(0.0, 100.0)) }
            "reactionGapVolume" -> updateAudio { it.copy(movieVolumeInGaps = value.coerceIn(0.0, 100.0)) }
            "reactionStop" -> stop()
        }
        pushUi(force = true)
    }

    private fun updateAudio(change: (ReactionAudioSettings) -> ReactionAudioSettings) {
        val next = change(mixer.settings)
        mixer.update(next)
        preferences = preferences.copy(audio = next)
        ReactionPreferencesStore.save(preferences)
    }

    // --- Sources -------------------------------------------------------------------------------

    private suspend fun pickFile(): String? = withContext(Dispatchers.Default) {
        suspendCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
                    ?: Frame.getFrames().firstOrNull { it.isVisible }
                val dialog = FileDialog(owner, "Choose a reaction video", FileDialog.LOAD)
                dialog.setFilenameFilter { _, name ->
                    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
                }
                dialog.isVisible = true
                val chosen = dialog.file?.let { File(dialog.directory, it).absolutePath }
                continuation.resume(chosen)
            }
        }
    }

    private suspend fun pasteLink() {
        val text = suspendCoroutine<String?> { continuation ->
            SwingUtilities.invokeLater {
                continuation.resume(
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
                    }.getOrNull(),
                )
            }
        }?.trim()
        if (text.isNullOrBlank() || !(text.startsWith("http://") || text.startsWith("https://"))) {
            error = "Copy a YouTube link first, then press Paste."
            return
        }
        if (ReactionTools.locate() == null) {
            error = "YouTube links need a one-time setup first."
            return
        }
        start(ReactionSource.WebVideo(text))
    }

    private fun installTools() {
        if (toolsBusy) return
        toolsBusy = true
        toolsMessage = "Preparing…"
        scope.launch(Dispatchers.IO) {
            val result = runCatching { ReactionTools.install { message -> scope.launch { toolsMessage = message; pushUi() } } }
            scope.launch {
                toolsBusy = false
                toolsMessage = result.fold(
                    onSuccess = { null },
                    onFailure = { "Setup failed: ${it.message}" },
                )
                pushUi(force = true)
            }
        }
    }

    private suspend fun start(newSource: ReactionSource) {
        error = null
        if (newSource is ReactionSource.LocalFile && !File(newSource.path).isFile) {
            error = "That file is no longer there."
            return
        }
        closeStage(keepSource = false)
        source = newSource
        resolvedStream = null
        loadRetries = 0
        offsetMs = 0
        reactionDurationMs = null
        reactionAspect = null
        engine.reset()
        engine.seedSeekLatency(if (newSource is ReactionSource.WebVideo) 1_500.0 else 100.0)
        mixer.reset()
        openStage()
        val m = master
        if (m != null && restoreMainVolume == null) {
            restoreMainVolume = ReactionNative.mainGetProperty(m.playerHandle, "volume")?.toDoubleOrNull()
            if (mixer.settings.movieVolumeInGaps >= 100.0 && restoreMainVolume != null) {
                // "Movie when reactions skip" defaults to the volume the user was already watching at.
                updateAudio { it.copy(movieVolumeInGaps = restoreMainVolume!!.coerceIn(0.0, 100.0)) }
            }
        }
        ensureTicking()
    }

    private suspend fun stop() {
        closeStage(keepSource = false)
        debugState = debugState.copy(stageOpen = false)
        source = null
        panelOpen = false
        val m = master
        if (m != null) {
            resetMainMargins(m.playerHandle)
            restoreMainVolume?.let { setMainVolume(m.playerHandle, it) }
        }
        restoreMainVolume = null
        tickJob?.cancel()
        tickJob = null
        lastDecision = null
    }

    // --- Stage -------------------------------------------------------------------------------

    private fun setMode(mode: StageMode) {
        if (preferences.mode == mode) return
        preferences = preferences.copy(mode = mode)
        ReactionPreferencesStore.save(preferences)
        master?.let { resetMainMargins(it.playerHandle) }
        if (stage != null) reopenStage()
    }

    private fun reopenStage() {
        val resumeAt = stage?.sample()?.positionMs
        closeStage(keepSource = true)
        openStage(resumeAt)
    }

    private fun openStage(resumeAtMs: Long? = null) {
        val currentSource = source ?: return
        val placement = when (preferences.mode) {
            StageMode.DUAL -> {
                val bounds = monitorOverride
                    ?: ReactionMonitors.choose(ReactionMonitors.list(), preferences.monitorId)?.physicalBounds
                    ?: run { error = "No display found for the reactions."; return }
                StagePlacement.Monitor(bounds)
            }
            StageMode.SINGLE -> {
                val m = master ?: return
                StagePlacement.InPlayer(m.playerHandle)
            }
        }
        val extra = mutableListOf<String>()
        if (currentSource is ReactionSource.WebVideo) {
            val tools = ReactionTools.locate() ?: run { error = "YouTube links need a one-time setup first."; return }
            extra += ReactionTools.mpvOptions(tools)
        }
        extra += extraStageOptionsForTest
        if (resumeAtMs != null && resumeAtMs > 0) extra += "start=" + String.format(Locale.ROOT, "%.3f", resumeAtMs / 1000.0)
        val opened = runCatching { ReactionStage.open(placement, extra) }
            .onFailure { log.w(it) { "stage failed to open" }; error = "Couldn't open the reaction player: ${it.message}" }
            .getOrNull() ?: return
        stage = opened
        appliedLayoutKey = null
        dim = 0.0
        loading = true
        opened.setTiles(listOf(ShaderTile(LayoutRect.FULL, LayoutRect.FULL)))
        opened.load(currentSource, resolvedStream)
        log.i { "stage opened mode=${preferences.mode} source=${currentSource.label}" }
    }

    private fun closeStage(keepSource: Boolean) {
        stage?.close()
        stage = null
        appliedLayoutKey = null
        singleLayout = null
        singleSurface = null
        movieNow = null
        movieExpanded = false
        if (!keepSource) source = null
        master?.let { if (preferences.mode == StageMode.SINGLE) resetMainMargins(it.playerHandle) }
    }

    // --- The loop ------------------------------------------------------------------------------

    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var lastUiPush = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                runCatching { tick(now) }.onFailure { log.w(it) { "tick failed" } }
                if (now - lastUiPush >= 250) {
                    pushUi()
                    lastUiPush = now
                }
                delay(TICK_MS)
            }
        }
    }

    private fun tick(now: Long) {
        val currentStage = stage ?: return
        drainStageEvents(currentStage)
        val m = master
        if (m == null || attachedHandle.get() != m.playerHandle) {
            currentStage.apply(FollowerCommand.SetPaused(true))
            return
        }
        val masterSample = sampleMaster(m.playerHandle) ?: return
        val follower = currentStage.sample()
        reactionDurationMs = follower.durationMs ?: reactionDurationMs
        if (loading && follower.durationMs == null) {
            debugState = debugState.copy(loading = true, mode = preferences.mode, stageOpen = true)
            return
        }

        applyLayout(m.playerHandle, currentStage)

        val map = SyncMap.manualOffset(offsetMs, reactionDurationMs)
        val decision = engine.step(now, masterSample, follower, map)
        decision.commands.forEach(currentStage::apply)
        lastDecision = decision
        if (currentStage.placement is StagePlacement.InPlayer) animateMovie(m.playerHandle, currentStage, decision)

        val showing = decision.visibility == StageVisibility.SHOWING
        val targetDim = if (showing) 0.0 else 1.0
        dim = if (abs(targetDim - dim) <= DIM_STEP) targetDim else dim + if (targetDim > dim) DIM_STEP else -DIM_STEP
        currentStage.setLiveParameters(dim, blackoutMask = 0)
        currentStage.setOverlay(overlayText(decision))

        val observed = ReactionNative.mainGetProperty(m.playerHandle, "volume")?.toDoubleOrNull()
        val audio = mixer.step(now, showing, observed)
        if (observed == null || abs(observed - audio.movieVolume) > 0.5) setMainVolume(m.playerHandle, audio.movieVolume)
        currentStage.setVolume(audio.reactionVolume)
        debugState = ReactionDebugState(
            playerHandle = m.playerHandle,
            uiReady = uiReadyFor == m.playerHandle,
            stageOpen = true,
            loading = loading,
            mode = preferences.mode,
            offsetMs = offsetMs,
            masterMs = masterSample.positionMs,
            masterPaused = masterSample.paused,
            followerMs = follower.positionMs,
            followerPaused = follower.paused,
            errorMs = decision.errorMs,
            visibility = decision.visibility,
            resumesInMs = decision.resumesInMs,
            mainVolume = audio.movieVolume,
            error = error,
        )
        if (mixer.settings != preferences.audio) {
            preferences = preferences.copy(audio = mixer.settings)
            ReactionPreferencesStore.save(preferences)
        }
    }

    private fun overlayText(decision: SyncDecision): String? {
        if (decision.visibility != StageVisibility.GAP) return null
        val resumes = decision.resumesInMs ?: return "No more reactions in this episode"
        if (resumes < 1_000) return null
        val seconds = (resumes + 999) / 1000
        return "Reactions resume in ${seconds / 60}:${String.format(Locale.ROOT, "%02d", seconds % 60)}"
    }

    private fun drainStageEvents(currentStage: ReactionStage) {
        for (event in currentStage.takeEvents()) {
            when {
                event == "file-loaded" -> {
                    loading = false
                    if (source is ReactionSource.WebVideo && resolvedStream == null) resolvedStream = currentStage.resolvedStream()
                    reactionAspect = currentStage.double("video-params/aspect")
                    appliedLayoutKey = null
                    error = null
                }
                event.startsWith("end-file") -> {
                    val reason = event.split(' ').getOrNull(1)?.toIntOrNull()
                    if (reason == 4) {
                        val current = source
                        if (current is ReactionSource.WebVideo && loadRetries < MAX_WEB_RETRIES) {
                            // Resolve again from scratch: the URLs that failed may be the stale part.
                            loadRetries++
                            resolvedStream = null
                            log.w { "reaction load failed, retry $loadRetries of $MAX_WEB_RETRIES" }
                            currentStage.load(current)
                        } else {
                            loading = false
                            error = "Couldn't play the reaction video."
                        }
                    }
                }
                event.startsWith("log") -> log.w { "stage $event" }
            }
        }
    }

    private fun sampleMaster(playerHandle: Long): MasterSample? {
        val position = ReactionNative.mainGetProperty(playerHandle, "time-pos")?.toDoubleOrNull() ?: return null
        val paused = ReactionNative.mainGetProperty(playerHandle, "pause") == "yes"
        val speed = ReactionNative.mainGetProperty(playerHandle, "speed")?.toDoubleOrNull() ?: 1.0
        val stalled = ReactionNative.mainGetProperty(playerHandle, "paused-for-cache") == "yes" ||
            ReactionNative.mainGetProperty(playerHandle, "seeking") == "yes"
        return MasterSample((position * 1000).toLong(), paused, speed, stalled)
    }

    /**
     * The main player's surface in physical pixels, read from its container window - the window the
     * stage is a child of and the size the hole and margins are computed against.
     */
    private fun mainSurfaceSize(playerHandle: Long): Pair<Int, Int>? {
        val container = ReactionNative.mainContainerHwnd(playerHandle).takeIf { it != 0L } ?: return null
        val packed = ReactionNative.windowClientSize(container)
        val w = (packed ushr 32).toInt()
        val h = (packed and 0xFFFFFFFFL).toInt()
        return if (w > 1 && h > 1) w to h else null
    }

    /** Recomputes tiles (and, single-screen, the episode's margins) when anything they depend on changes. */
    private fun applyLayout(playerHandle: Long, currentStage: ReactionStage) {
        // Stage 1 has no box detection yet: the whole reaction frame is one tile.
        val tileSources = listOf(LayoutRect.FULL)
        val sourceAspect = reactionAspect ?: (16.0 / 9.0)
        val aspects = tileSources.map { it.w / it.h * sourceAspect }
        when (val placement = currentStage.placement) {
            is StagePlacement.Monitor -> {
                val w = placement.bounds.width.toDouble()
                val h = placement.bounds.height.toDouble()
                val key = "dual:$w:$h:$aspects"
                if (key == appliedLayoutKey) return
                appliedLayoutKey = key
                val targets = ReactionGridLayout.justify(aspects, w, h, TILE_GAP)
                currentStage.setTiles(tileSources.zip(targets) { s, t -> ShaderTile(s, t.normalizedIn(w, h)) })
            }
            is StagePlacement.InPlayer -> {
                val (sw, sh) = mainSurfaceSize(playerHandle) ?: return
                val movieAspect = ReactionNative.mainGetProperty(playerHandle, "video-params/aspect")?.toDoubleOrNull() ?: (16.0 / 9.0)
                val w = sw.toDouble()
                val h = sh.toDouble()
                val key = "single:$w:$h:$movieAspect:${preferences.movieFraction}:$aspects"
                if (key == appliedLayoutKey) return
                appliedLayoutKey = key
                val layout = ReactionSingleScreenLayout.arrange(w, h, movieAspect, aspects, preferences.movieFraction, TILE_GAP)
                currentStage.setTiles(tileSources.zip(layout.tiles) { s, t -> ShaderTile(s, t.normalizedIn(w, h)) })
                singleLayout = layout
                singleSurface = LayoutRect(0.0, 0.0, w, h)
                // A new surface size (window resized, fullscreen) moves the episode without animating.
                if (movieNow != null && movieNow?.let { it.right > w + 1 || it.bottom > h + 1 } == true) movieNow = null
            }
        }
    }

    /**
     * Single-screen: the episode sits in its arrangement while reactions play, and grows back to the
     * full surface during a long gap (over 30 s, or nothing left), shrinking again 3 s before
     * reactions resume. Eased over ~0.4 s; the episode's window region and margins move together.
     */
    private fun animateMovie(playerHandle: Long, currentStage: ReactionStage, decision: SyncDecision) {
        val layout = singleLayout ?: return
        val full = singleSurface ?: return
        val resumesIn = decision.resumesInMs
        movieExpanded = when {
            decision.visibility == StageVisibility.SHOWING -> false
            resumesIn != null && resumesIn <= GAP_SHRINK_BEFORE_MS -> false
            resumesIn == null || resumesIn > GAP_GROW_AFTER_MS -> true
            else -> movieExpanded
        }
        val target = if (movieExpanded) full else layout.movie
        val from = movieNow ?: full
        val next = from.approach(target)
        if (next == movieNow) return
        // The reaction's own window exists only once its first frame is up; until then, retry.
        if (!currentStage.layoutInPlayer(next)) return
        movieNow = next
        setMainMargins(playerHandle, MovieMargins.of(next, full.w, full.h))
    }

    /** One easing step towards [target]: a quarter of the way, at least 2 px, snapping when close. */
    private fun LayoutRect.approach(target: LayoutRect): LayoutRect {
        fun step(a: Double, b: Double): Double {
            val d = b - a
            if (abs(d) <= 2.0) return b
            return a + d * 0.25 + (if (d > 0) 1.0 else -1.0)
        }
        return LayoutRect(step(x, target.x), step(y, target.y), step(w, target.w), step(h, target.h))
    }

    private fun setMainMargins(playerHandle: Long, margins: MovieMargins) {
        // mpv draws subtitles in the margins by default (measured: below the picture), which the
        // single-screen clip would cut off. Keep them inside the episode while it is shrunk.
        ReactionNative.mainSetProperty(playerHandle, "sub-use-margins", if (margins == MovieMargins.NONE) "yes" else "no")
        val f = { v: Double -> String.format(Locale.ROOT, "%.5f", v) }
        ReactionNative.mainSetProperty(playerHandle, "video-margin-ratio-left", f(margins.left))
        ReactionNative.mainSetProperty(playerHandle, "video-margin-ratio-right", f(margins.right))
        ReactionNative.mainSetProperty(playerHandle, "video-margin-ratio-top", f(margins.top))
        ReactionNative.mainSetProperty(playerHandle, "video-margin-ratio-bottom", f(margins.bottom))
    }

    private fun resetMainMargins(playerHandle: Long) = setMainMargins(playerHandle, MovieMargins.NONE)

    private fun setMainVolume(playerHandle: Long, percent: Double) {
        ReactionNative.mainSetProperty(playerHandle, "volume", String.format(Locale.ROOT, "%.1f", percent))
    }

    // --- UI state ------------------------------------------------------------------------------

    private fun pushUi(force: Boolean = false) {
        val m = master ?: return
        if (uiReadyFor != m.playerHandle) return
        val json = uiState().toString()
        if (!force && json == lastUiJson) return
        lastUiJson = json
        ReactionNative.mainRunControlsScript(m.playerHandle, "window.reactionUpdate && window.reactionUpdate($json)")
    }

    private fun uiState(): JsonObject = buildJsonObject {
        put("enabled", true)
        put("open", panelOpen)
        put("active", source != null)
        val decision = lastDecision
        val status: Pair<String, String>? = when {
            source == null -> null
            error != null -> "error" to "Problem"
            loading -> "waiting" to "Loading…"
            decision == null -> "neutral" to "Starting"
            decision.waitingForSeek -> "waiting" to "Syncing"
            decision.visibility == StageVisibility.GAP -> "waiting" to (overlayText(decision)?.replace("Reactions resume", "Resumes") ?: "Paused")
            else -> "good" to "In sync"
        }
        if (status != null) putJsonObject("status") {
            put("tone", status.first)
            put("text", status.second)
        }
        error?.let { put("error", it) }
        if (source != null && decision?.visibility == StageVisibility.GAP) {
            overlayText(decision)?.let { put("gapText", it) }
        }
        source?.let { s ->
            putJsonObject("source") {
                put("label", s.label)
                put("detail", if (s is ReactionSource.WebVideo) "Streaming from the web" else "Local file")
            }
        }
        putJsonObject("tools") {
            put("ready", ReactionTools.locate() != null)
            put("busy", toolsBusy)
            toolsMessage?.let { put("message", it) }
        }
        put("mode", if (preferences.mode == StageMode.SINGLE) "single" else "dual")
        val monitors = ReactionMonitors.list()
        val chosen = ReactionMonitors.choose(monitors, preferences.monitorId)
        put(
            "monitors",
            buildJsonArray {
                monitors.forEachIndexed { index, monitor ->
                    add(
                        buildJsonObject {
                            put("index", index)
                            put("label", monitor.label)
                            put("detail", monitor.detail)
                            put("selected", monitor.id == chosen?.id)
                        },
                    )
                }
            },
        )
        put("movieSize", preferences.movieFraction)
        put("offsetMs", offsetMs)
        putJsonObject("volumes") {
            put("reaction", mixer.settings.reactionVolume)
            put("movie", mixer.settings.movieVolumeWithReactions)
            put("gap", mixer.settings.movieVolumeInGaps)
        }
    }

    private const val TICK_MS = 40L
    private const val MAX_WEB_RETRIES = 2
    private const val GAP_GROW_AFTER_MS = 30_000L
    private const val GAP_SHRINK_BEFORE_MS = 3_000L
    private const val DIM_STEP = 0.12
    private const val TILE_GAP = 8.0
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "flv", "wmv")
}

internal data class ReactionDebugState(
    val playerHandle: Long = 0,
    val uiReady: Boolean = false,
    val stageOpen: Boolean = false,
    val loading: Boolean = false,
    val mode: StageMode = StageMode.DUAL,
    val offsetMs: Long = 0,
    val masterMs: Long? = null,
    val masterPaused: Boolean? = null,
    val followerMs: Long? = null,
    val followerPaused: Boolean? = null,
    val errorMs: Long? = null,
    val visibility: StageVisibility? = null,
    val resumesInMs: Long? = null,
    val mainVolume: Double? = null,
    val error: String? = null,
)
