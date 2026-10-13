package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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

    // Stage 3: the reaction's own layout - which boxes are reactors, where the movie is.
    private var layoutTimeline: ReactionLayoutTimeline? = null
    /** The layout in force at the reaction's current position (it changes mid-video). */
    private var detectedLayout: DetectedLayout? = null
    private var currentEpoch = -1
    private var analysisJob: Job? = null
    private var analysisProgress: Double? = null
    private var analysisError: String? = null
    /** The user asked to see the reaction as it is, not rearranged. */
    private var showWholeFrame = false

    // Stage 4: automatic sync - where the episode appears in the reaction, found by fingerprints.
    // The +/- buttons stay: in automatic mode they fine-tune the matched map.
    private var autoSync = true
    private var reactionTrack = FingerprintTrack()
    private var reactionAnalysed = false
    private var downloadProgress: Double? = null
    private var episodeSource: EpisodeSource? = null
    private var episodeCheckedFor = 0L
    private var episodeTrack = FingerprintTrack()
    private var episodeJob: Job? = null
    private var episodeProgress: Double? = null
    /** Every frame of the episode read (the background fill finished). */
    private var episodeIndexed = false
    /** Enough of the episode read to trust the map here: see [EpisodeReadPlanner.checked]. */
    private var episodeChecked = false
    /** A match ran with the episode checked: until then the map is provisional. */
    private var matchedWhileChecked = false
    private var episodePlanner: EpisodeReadPlanner? = null
    private var playheadMs = 0L
    private var lastProgressCheck = 0L
    private var episodeError: String? = null
    private var matcher: ReactionMatcher? = null
    private var matchJob: Job? = null
    private var matchAgain = false
    private var matched: List<MatchedSegment> = emptyList()
    /** The main player's dropped frames: while they rise, the episode index holds. */
    private var lastDrops = -1L
    private var lastDropCheck = 0L
    @Volatile private var holdIndexUntil = 0L

    // The Firefox link (Stage 2). The tab the reaction came from stays linked: when it moves to
    // another video - the next episode's reaction - Nuvio Z follows it.
    private var browserConnected = false
    private var browserTabs: List<BrowserTab> = emptyList()
    private var linkedTabId: Int? = null
    private val link: ReactionLinkServer by lazy {
        ReactionLinkServer(
            object : ReactionLinkListener {
                override fun onTabsChanged(tabs: List<BrowserTab>) {
                    scope.launch {
                        browserTabs = tabs
                        debugState = debugState.copy(browserTabs = tabs)
                        pushUi()
                    }
                }

                override fun onTabVideoChanged(tab: BrowserTab) {
                    scope.launch { followLinkedTab(tab) }
                }

                override fun onConnectionChanged(connected: Boolean) {
                    scope.launch {
                        browserConnected = connected
                        debugState = debugState.copy(browserConnected = connected)
                        pushUi(force = true)
                    }
                }
            },
        )
    }

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
            link.start()
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
            "reactionPickTab" -> browserTabs.firstOrNull { it.tabId == value.toInt() }?.let { pickTab(it) }
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
            "reactionAutoSync" -> {
                autoSync = value >= 0.5
                offsetMs = 0
                engine.reset()
            }
            "reactionVolume" -> updateAudio { it.copy(reactionVolume = value.coerceIn(0.0, 100.0)) }
            "reactionMovieVolume" -> updateAudio { it.copy(movieVolumeWithReactions = value.coerceIn(0.0, 100.0)) }
            "reactionGapVolume" -> updateAudio { it.copy(movieVolumeInGaps = value.coerceIn(0.0, 100.0)) }
            "reactionStop" -> stop()
            "reactionShowWhole" -> {
                showWholeFrame = !showWholeFrame
                appliedLayoutKey = null
            }
            "reactionRedetect" -> stage?.let {
                // The fingerprints were taken inside the old boxes: find both again.
                resetReactionMatching(FingerprintTrack())
                beginLayout(it, useCache = false)
            }
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

    private suspend fun pickTab(tab: BrowserTab) {
        if (ReactionTools.locate() == null) {
            error = "YouTube needs a one-time setup first."
            return
        }
        start(ReactionSource.WebVideo(tab.url, tab.title.removeSuffix(" - YouTube").ifBlank { null }))
        linkedTabId = tab.tabId
        quietLinkedTab()
    }

    /** The linked tab now shows another video: that is the reaction to play (same-tab handoff). */
    private suspend fun followLinkedTab(tab: BrowserTab) {
        if (tab.tabId != linkedTabId) return
        val current = (source as? ReactionSource.WebVideo)?.url?.let(ReactionLinkServer::youTubeVideoId)
        if (tab.videoId == null || tab.videoId == current) return
        log.i { "linked tab moved to another video; following it" }
        start(ReactionSource.WebVideo(tab.url, tab.title.removeSuffix(" - YouTube").ifBlank { null }))
        linkedTabId = tab.tabId
        quietLinkedTab()
        pushUi(force = true)
    }

    /** Pause and mute the tab; again shortly after, since a page that is still loading may autoplay. */
    private fun quietLinkedTab() {
        val id = linkedTabId ?: return
        scope.launch {
            for (delayMs in listOf(0L, 1_500L, 4_000L)) {
                delay(delayMs)
                if (linkedTabId == id) link.quietTab(id)
            }
        }
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
        linkedTabId = null
        resolvedStream = null
        analysisJob?.cancel()
        analysisJob = null
        layoutTimeline = null
        detectedLayout = null
        currentEpoch = -1
        analysisProgress = null
        analysisError = null
        resetReactionMatching(ReactionTrackCache.load(trackKey(newSource)) ?: FingerprintTrack())
        autoSync = true
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
        linkedTabId = null
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
        analysisJob?.cancel()
        analysisJob = null
        resetReactionMatching(FingerprintTrack())
        episodeJob?.cancel()
        episodeJob = null
        episodeSource = null
        episodeCheckedFor = 0L
    }

    /** A new reaction (or new boxes): its fingerprints, and every match made with them, start over. */
    private fun resetReactionMatching(track: FingerprintTrack) {
        reactionTrack = track
        reactionAnalysed = false
        downloadProgress = null
        matchJob?.cancel()
        matchJob = null
        matcher = null
        matched = emptyList()
        matchedWhileChecked = false
    }

    // "-r2": reaction hashes are taken with the overlay normalised out (OverlayNormaliser); "-r" files
    // hold the old hashes. Episode tracks did not change and stay valid.
    /** The first run of at least 2 s of frames not in [track], below [until]. */
    private fun firstLongMissing(track: FingerprintTrack, until: Int): IntRange? {
        var from = 0
        while (true) {
            val run = track.missingRange(from, until) ?: return null
            if (run.last - run.first + 1 >= 2 * ReactionFingerprinter.FPS) return run
            from = run.last + 1
        }
    }

    private fun trackKey(source: ReactionSource) = ReactionLayoutCache.key(source) + "-r2"

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

        if (updateEpoch(follower.positionMs)) appliedLayoutKey = null
        applyLayout(m.playerHandle, currentStage)
        playheadMs = masterSample.positionMs
        ensureEpisodeIndex(m.playerHandle, masterSample.positionMs)
        if (now - lastProgressCheck >= 500) {
            lastProgressCheck = now
            updateEpisodeProgress()
        }
        noteMainDrops(m.playerHandle, now)
        giveUpOnMatchingIfDone()

        val decision = engine.step(now, masterSample, follower, currentMap())
        decision.commands.forEach(currentStage::apply)
        lastDecision = decision
        if (currentStage.placement is StagePlacement.InPlayer) animateMovie(m.playerHandle, currentStage, decision)

        val showing = decision.visibility == StageVisibility.SHOWING
        // An unsettled gap is not dimmed like a real cut: it may be a part not matched yet.
        val targetDim = if (showing || !gapSettled(decision)) 0.0 else 1.0
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
            browserConnected = browserConnected,
            browserTabs = browserTabs,
            linkedTabId = linkedTabId,
            sourceLabel = source?.label,
            reactorCount = detectedLayout?.reactors?.size,
            analyzing = analysisProgress != null,
            autoSync = autoSync,
            matchedSegments = matched.size,
            reactionAnalysed = reactionAnalysed,
            episodeIndexed = episodeIndexed,
        )
        if (mixer.settings != preferences.audio) {
            preferences = preferences.copy(audio = mixer.settings)
            ReactionPreferencesStore.save(preferences)
        }
    }

    /** Automatic: the matched map (empty while nothing is matched yet); manual: one offset everywhere. */
    private fun currentMap(): SyncMap =
        if (autoSync) ReactionMatcher.toSyncMap(matched, offsetMs) else SyncMap.manualOffset(offsetMs, reactionDurationMs)

    /** Either side still being read, or the latest fingerprints not matched yet. */
    private fun stillMatching(): Boolean =
        autoSync && (!reactionAnalysed || !episodeChecked || !matchedWhileChecked) && episodeError == null && analysisError == null

    /** Both sides read in full and nothing matched: say so, and hand over to the manual offset. */
    private fun giveUpOnMatchingIfDone() {
        if (!autoSync || matched.isNotEmpty() || stillMatching()) return
        if (!reactionAnalysed || !episodeChecked || !matchedWhileChecked) return
        autoSync = false
        offsetMs = 0
        engine.reset()
        error = "Couldn't find this episode in the reaction. Line it up with the buttons under Sync."
        log.i { "no match between episode and reaction; manual sync" }
    }

    /**
     * A gap is only shown as one - dimmed, with a countdown - once it is known: the map is past its
     * provisional stage and the episode from here to where reactions resume has been read. Before
     * that, a gap may be nothing but a part not matched yet, and a countdown would be a number that
     * changes under the user (first real test: "Reactions resume in 3:30" at the film's first frame).
     */
    private fun gapSettled(decision: SyncDecision): Boolean {
        if (decision.visibility != StageVisibility.GAP || !autoSync) return true
        if (stillMatching()) return false
        val planner = episodePlanner ?: return true
        val from = (playheadMs / ReactionFingerprinter.FRAME_MS).toInt()
        val ahead = decision.resumesInMs?.coerceAtMost(EpisodeReadPlanner.CHECKED_AHEAD_FRAMES * ReactionFingerprinter.FRAME_MS)
            ?: (EpisodeReadPlanner.CHECKED_AHEAD_FRAMES * ReactionFingerprinter.FRAME_MS)
        return planner.isRead(episodeTrack, from until from + (ahead / ReactionFingerprinter.FRAME_MS).toInt())
    }

    private fun overlayText(decision: SyncDecision): String? {
        if (decision.visibility != StageVisibility.GAP) return null
        if (!gapSettled(decision)) return "Matching the reaction to this episode…"
        val resumes = decision.resumesInMs ?: return "No more reactions in this episode"
        if (resumes < 1_000) return null
        val seconds = (resumes + 999) / 1000
        return "Reactions resume in ${seconds / 60}:${String.format(Locale.ROOT, "%02d", seconds % 60)}"
    }

    /**
     * Use this reaction's cached layout timeline and fingerprints, or read the whole video in the
     * background: a local file directly, a web video from a downloaded low-resolution copy. Reading
     * runs many times faster than playback, so it starts at the beginning (or where an earlier reading
     * stopped); the timeline and the fingerprints are published as they grow.
     */
    private fun beginLayout(currentStage: ReactionStage, useCache: Boolean) {
        val current = source ?: return
        val key = ReactionLayoutCache.key(current)
        val track = reactionTrack
        val duration = currentStage.double("duration")?.let { (it * 1000).toLong() }
        val totalIndex = duration?.let { (it / ReactionFingerprinter.FRAME_MS).toInt() }
        if (useCache && layoutTimeline == null) {
            ReactionLayoutCache.load(key)?.let {
                layoutTimeline = it
                currentEpoch = -1
                appliedLayoutKey = null
                log.i { "layout timeline from cache: ${it.epochs.size} epochs" }
            }
        }
        if (!useCache) layoutTimeline = null
        // A short tail the encoder never emits, or a second lost between two readers, does not count.
        val missing = totalIndex?.let { firstLongMissing(track, it - ReactionFingerprinter.FPS) }
        if (useCache && layoutTimeline != null && totalIndex != null && missing == null) {
            reactionAnalysed = true
            scheduleMatch()
            log.i { "reaction fingerprints from cache: ${track.count} frames" }
            return
        }
        val earlier = layoutTimeline
        // Resume where an earlier reading stopped only when its layout is here too; a layout from a new
        // version of the analysis needs the whole video.
        val start = if (earlier == null) 0L else (missing?.first ?: 0) * ReactionFingerprinter.FRAME_MS
        analysisJob?.cancel()
        analysisError = null
        analysisProgress = 0.0
        analysisJob = scope.launch {
            try {
                val target = when (current) {
                    is ReactionSource.LocalFile -> current.path
                    is ReactionSource.WebVideo -> {
                        val tools = ReactionTools.locate() ?: error("YouTube support is not set up")
                        downloadProgress = 0.0
                        ReactionDownloads.analysisCopy(current.url, key, tools) { p ->
                            scope.launch { if (source == current) downloadProgress = p }
                        }.absolutePath.also { downloadProgress = null }
                    }
                }
                val timeline = ReactionTimelineAnalyzer.analyze(
                    target, start, duration, track,
                    onProgress = { p -> scope.launch { analysisProgress = p } },
                    onTimeline = { t ->
                        scope.launch {
                            if (source == current) {
                                layoutTimeline = earlier?.overlaidWith(t) ?: t
                                currentEpoch = -1
                                appliedLayoutKey = null
                            }
                        }
                    },
                    onFingerprints = { scope.launch { if (reactionTrack === track) scheduleMatch() } },
                )
                if (source != current) return@launch
                layoutTimeline = earlier?.overlaidWith(timeline) ?: timeline
                currentEpoch = -1
                layoutTimeline?.let { ReactionLayoutCache.save(key, it) }
                appliedLayoutKey = null
                if (reactionTrack === track) {
                    reactionAnalysed = true
                    scheduleMatch()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.w(failure) { "reaction analysis failed" }
                analysisError = "Couldn't analyse the reaction: ${failure.message}"
            } finally {
                ReactionTrackCache.save(trackKey(current), track)
                if (source == current) {
                    analysisJob = null
                    analysisProgress = null
                    downloadProgress = null
                }
                pushUi(force = true)
            }
        }
    }

    // --- Stage 4: the episode index and the matcher ----------------------------------------------

    /**
     * Indexes the episode the main player is showing, or picks up its cached index. Called every
     * tick; does work only when the player (and so possibly the episode) changed.
     */
    private fun ensureEpisodeIndex(playerHandle: Long, positionMs: Long) {
        if (episodeCheckedFor == playerHandle) return
        // Until the player has opened its file there is nothing to read; try again next tick.
        val found = ReactionEpisodeIndexer.sourceOf(playerHandle) ?: return
        episodeCheckedFor = playerHandle
        if (found.key == episodeSource?.key) return
        episodeJob?.cancel()
        episodeSource = found
        episodeTrack = ReactionTrackCache.load(found.key) ?: FingerprintTrack()
        episodeIndexed = false
        episodeProgress = null
        episodeError = null
        matchJob?.cancel()
        matchJob = null
        matcher = null
        matched = emptyList()
        engine.reset()
        episodeChecked = false
        matchedWhileChecked = false
        val track = episodeTrack
        val fps = ReactionFingerprinter.FPS
        val total = found.durationMs?.let { (it / ReactionFingerprinter.FRAME_MS).toInt() }
            ?: ((positionMs / ReactionFingerprinter.FRAME_MS).toInt() + 3 * 3600 * fps)
        val planner = EpisodeReadPlanner(total).also { episodePlanner = it }
        log.i { "episode ${found.key}: ${track.count} frames cached" }
        updateEpisodeProgress()
        episodeJob = scope.launch {
            val busy = ArrayList<IntRange>()
            val failedReads = HashMap<Int, Int>()
            try {
                // Two readers: a probe is mostly opening the stream and seeking, which overlaps well.
                coroutineScope {
                    repeat(EPISODE_READERS) {
                        launch {
                            while (true) {
                                val position = (playheadMs / ReactionFingerprinter.FRAME_MS).toInt()
                                val plan = planner.next(track, position, matchedEdges(), busy)
                                if (plan == null) {
                                    if (busy.isEmpty()) break
                                    delay(1_000)
                                    continue
                                }
                                busy += plan.range
                                val started = System.currentTimeMillis()
                                try {
                                    ReactionEpisodeIndexer.index(
                                        found, track, plan.range,
                                        holdWhile = { System.currentTimeMillis() < holdIndexUntil },
                                        onProgress = {},
                                        onFingerprints = { scope.launch { if (episodeTrack === track) scheduleMatch() } },
                                    )
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    // One stalled or failed read (a debrid stream that hangs) must not end
                                    // the whole index: try it once more later, then leave it out.
                                    val tries = (failedReads[plan.range.first] ?: 0) + 1
                                    failedReads[plan.range.first] = tries
                                    log.w { "episode read ${plan.range.first / fps}s failed ($tries): ${failure.message}" }
                                    if (tries >= 2) planner.markUnreadable(plan.range)
                                    continue
                                } finally {
                                    busy -= plan.range
                                }
                                log.i {
                                    "episode read ${plan.range.first / fps}-${(plan.range.last + 1) / fps}s (${plan.phase}) " +
                                        "in ${System.currentTimeMillis() - started} ms"
                                }
                                // A run that produced nothing at its start (past the real end) must not repeat.
                                if (!track.has(plan.range.first)) planner.markUnreadable(plan.range)
                                if (episodeTrack !== track) return@launch
                                updateEpisodeProgress()
                                withContext(Dispatchers.IO) { ReactionTrackCache.save(found.key, track) }
                            }
                        }
                    }
                }
                if (episodeTrack === track) {
                    episodeIndexed = true
                    log.i { "episode ${found.key}: read in full, ${track.count} frames" }
                    scheduleMatch()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.w(failure) { "episode index failed" }
                if (episodeTrack === track) episodeError = "Couldn't read the episode to match it: ${failure.message}"
            } finally {
                ReactionTrackCache.save(found.key, track)
                if (episodeTrack === track) episodeProgress = null
            }
        }
    }

    /** Where matched stretches start and end, in episode frames: the planner reads around them. */
    private fun matchedEdges(): List<Int> = matched.flatMap {
        listOf((it.episodeStartMs / ReactionFingerprinter.FRAME_MS).toInt(), (it.episodeEndMs / ReactionFingerprinter.FRAME_MS).toInt())
    }

    /** The panel's "checking the episode" share (the probes across the film), and whether it is checked. */
    private fun updateEpisodeProgress() {
        val planner = episodePlanner ?: return
        val track = episodeTrack
        val position = (playheadMs / ReactionFingerprinter.FRAME_MS).toInt()
        val checkedNow = planner.checked(track, position, matchedEdges())
        if (checkedNow != episodeChecked) {
            episodeChecked = checkedNow
            if (checkedNow) scheduleMatch() else matchedWhileChecked = false
        }
        val done = planner.probesDone(track)
        episodeProgress = if (done < planner.probeCount) done.toDouble() / planner.probeCount.coerceAtLeast(1) else null
    }

    /** While the main player drops frames, the episode index pauses (5 s after the last drop). */
    private fun noteMainDrops(playerHandle: Long, now: Long) {
        if (now - lastDropCheck < 500) return
        lastDropCheck = now
        val drops = listOf("frame-drop-count", "decoder-frame-drop-count")
            .sumOf { ReactionNative.mainGetProperty(playerHandle, it)?.toLongOrNull() ?: 0L }
        if (lastDrops in 0 until drops) holdIndexUntil = now + 5_000
        lastDrops = drops
    }

    /**
     * Matches the fingerprints both sides have so far. One run at a time, each comparing only frames
     * that arrived since the last; fingerprints arriving meanwhile queue one more run.
     */
    private fun scheduleMatch() {
        if (matchJob?.isActive == true) {
            matchAgain = true
            return
        }
        val m = matcher ?: ReactionMatcher(episodeTrack, reactionTrack).also { matcher = it }
        matchJob = scope.launch {
            do {
                matchAgain = false
                val started = System.currentTimeMillis()
                val checked = episodeChecked && reactionAnalysed
                val segments = withContext(Dispatchers.Default) { m.update() }
                if (matcher !== m) return@launch
                if (checked && !matchedWhileChecked) {
                    matchedWhileChecked = true
                    log.i { "map settled: ${segments.size} stretches, the episode checked" }
                }
                if (segments != matched) {
                    matched = segments
                    log.i {
                        "matched ${segments.size} stretches in ${System.currentTimeMillis() - started} ms: " + segments.joinToString {
                            "ep ${it.episodeStartMs / 1000}-${it.episodeEndMs / 1000}s @${it.reactionAt(it.episodeStartMs) / 1000}s" +
                                (if (it.rate != 1.0) " x" + String.format(Locale.ROOT, "%.3f", it.rate) else "") +
                                (if (it.mirrored) " mirrored" else "")
                        }
                    }
                }
                if (matchAgain) delay(1_000)
            } while (matchAgain)
        }
    }

    /** Follows the reaction's position into the right layout epoch; true when the layout changed. */
    private fun updateEpoch(reactionMs: Long?): Boolean {
        val timeline = layoutTimeline ?: return false
        val ms = reactionMs ?: return false
        val index = timeline.epochIndexAt(ms)
        if (index == currentEpoch && detectedLayout != null) return false
        currentEpoch = index
        detectedLayout = timeline.at(ms)
        return true
    }

    /**
     * The parts of the reaction frame to show: detected reactors (slightly inset), or all of it.
     * Every reactor is cropped to one shape (the median camera's), so the grid gives everyone the
     * same size (plan Decision 7: "everyone, equal grid"); a wide two-person camera is not shown big
     * beside single ones. Height is cut more from the bottom than the top: faces sit high.
     */
    private fun currentTileSources(): List<LayoutRect> {
        val reactors = detectedLayout?.reactors.orEmpty()
        if (showWholeFrame || reactors.isEmpty()) return listOf(LayoutRect.FULL)
        // About one analysis pixel in from every side, so a neighbour's edge never shows.
        val ix = 1.5 / ReactionLayoutDetector.ANALYSIS_WIDTH
        val iy = 1.5 / ReactionLayoutDetector.ANALYSIS_HEIGHT
        val inset = reactors.map { LayoutRect(it.rect.x + ix, it.rect.y + iy, it.rect.w - 2 * ix, it.rect.h - 2 * iy) }
        val frameAspect = reactionAspect ?: (16.0 / 9.0)
        val shape = inset.map { it.w / it.h * frameAspect }.sorted()[inset.size / 2]
        return inset.map { r -> cropToShape(r, shape, frameAspect) }
    }

    private fun cropToShape(r: LayoutRect, shape: Double, frameAspect: Double): LayoutRect {
        val aspect = r.w / r.h * frameAspect
        return if (aspect > shape) {
            val w = r.w * shape / aspect
            LayoutRect(r.x + (r.w - w) / 2, r.y, w, r.h)
        } else {
            val h = r.h * aspect / shape
            LayoutRect(r.x, r.y + (r.h - h) * 0.3, r.w, h)
        }
    }

    private fun drainStageEvents(currentStage: ReactionStage) {
        for (event in currentStage.takeEvents()) {
            when {
                event == "file-loaded" -> {
                    loading = false
                    if (source is ReactionSource.WebVideo && resolvedStream == null) resolvedStream = currentStage.resolvedStream()
                    if (layoutTimeline == null && analysisJob == null) beginLayout(currentStage, useCache = true)
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
        val tileSources = currentTileSources()
        val sourceAspect = reactionAspect ?: (16.0 / 9.0)
        val aspects = tileSources.map { it.w / it.h * sourceAspect }
        when (val placement = currentStage.placement) {
            is StagePlacement.Monitor -> {
                val w = placement.bounds.width.toDouble()
                val h = placement.bounds.height.toDouble()
                val key = "dual:$w:$h:$tileSources:$sourceAspect"
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
                val key = "single:$w:$h:$movieAspect:${preferences.movieFraction}:$tileSources:$sourceAspect"
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
            decision.visibility == StageVisibility.GAP && !gapSettled(decision) -> "waiting" to "Matching…"
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
        putJsonObject("browser") {
            put("connected", browserConnected)
            put(
                "tabs",
                buildJsonArray {
                    browserTabs.forEach { tab ->
                        add(
                            buildJsonObject {
                                put("id", tab.tabId)
                                put("title", tab.title.removeSuffix(" - YouTube").ifBlank { tab.url })
                                put("linked", tab.tabId == linkedTabId)
                            },
                        )
                    }
                },
            )
        }
        putJsonObject("reactors") {
            put("analyzing", analysisProgress != null)
            analysisProgress?.let { put("progress", it) }
            put("count", detectedLayout?.reactors?.size ?: 0)
            put("found", detectedLayout != null)
            put("epochs", layoutTimeline?.epochs?.size ?: 0)
            put("showWhole", showWholeFrame)
            analysisError?.let { put("error", it) }
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
        putJsonObject("sync") {
            put("auto", autoSync)
            put("matching", stillMatching())
            put("segments", matched.size)
            downloadProgress?.let { put("download", it) }
            if (!reactionAnalysed) analysisProgress?.let { put("reaction", it) }
            if (!episodeIndexed) episodeProgress?.let { put("episode", it) }
            (episodeError ?: analysisError)?.let { put("error", it) }
            val matchedMs = matched.sumOf { it.episodeEndMs - it.episodeStartMs }
            episodeSource?.durationMs?.takeIf { it > 0 }?.let { put("coverage", matchedMs.toDouble() / it) }
        }
        putJsonObject("volumes") {
            put("reaction", mixer.settings.reactionVolume)
            put("movie", mixer.settings.movieVolumeWithReactions)
            put("gap", mixer.settings.movieVolumeInGaps)
        }
    }

    private const val TICK_MS = 40L
    private const val EPISODE_READERS = 2
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
    val browserConnected: Boolean = false,
    val browserTabs: List<BrowserTab> = emptyList(),
    val linkedTabId: Int? = null,
    val sourceLabel: String? = null,
    val reactorCount: Int? = null,
    val analyzing: Boolean = false,
    val autoSync: Boolean = true,
    val matchedSegments: Int = 0,
    val reactionAnalysed: Boolean = false,
    val episodeIndexed: Boolean = false,
)
