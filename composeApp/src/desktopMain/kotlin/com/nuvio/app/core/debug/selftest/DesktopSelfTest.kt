package com.nuvio.app.core.debug.selftest

import co.touchlab.kermit.Logger
import com.nuvio.app.core.debug.DesktopDebugLog
import com.nuvio.app.core.debug.SelfTestHooks
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.core.debug.selftest.suites.S0Environment
import com.nuvio.app.core.debug.selftest.suites.S1Sources
import com.nuvio.app.core.debug.selftest.suites.S2Debrid
import com.nuvio.app.core.debug.selftest.suites.S3Playback
import com.nuvio.app.core.debug.selftest.suites.S6SettingsAndSync
import com.nuvio.app.core.debug.selftest.suites.S8UiWalk
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The debug-only self-test: real addons, real debrid, real streams, real hardware.
 *
 * ## Why this exists
 *
 * `commonTest` holds 130 pure test files and not one of them crosses a network boundary. The only
 * real-I/O suite is the desktop download harness, and only for downloads. Everything else - whether
 * an addon answers, whether a debrid link serves bytes, whether a 4K remux decodes on this GPU,
 * whether the settings survive a server pull, whether a screen actually draws - has been verified
 * by hand, from a numbered device script in `STATUS.md`, or not at all. Almost always the last one:
 * nearly every section of that file ends with *"nothing is smoke-tested on a device or an installed
 * desktop app"*.
 *
 * This is that script, automated, writing evidence to a folder.
 *
 * ## What it is not
 *
 * It does not replace the device scripts. Judgements like *"is the heading legible over the frosted
 * panel"* stay human - what changes is that the screenshots make them cheap to answer instead of
 * requiring someone to reproduce the state first.
 *
 * ## Constraints worth knowing before reading a report
 *
 * - It **needs the window visible and foreground**, because the only thing that can photograph a
 *   native mpv surface and a WebView2 overlay is a screen grab. It moves the mouse and presses
 *   keys. Run it on a machine you are not using.
 * - It runs against **your real state** - real addons, real account, real debrid keys. It writes
 *   real settings and restores them, and it may enqueue and delete a real download.
 * - A debug-channel build keeps its own `%APPDATA%\Nuvio Z Debug` folder, so it sees the debug
 *   install's configuration and not the release app's. S0.1 says so plainly when that folder is
 *   empty, which is otherwise indistinguishable from every addon being broken.
 */
internal object DesktopSelfTest {

    private val log = Logger.withTag("SelfTest")
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Live progress for the overlay. Set on the runner's thread, read by Compose. */
    val state = SelfTestUiState()

    /**
     * Wired from `Main.kt` once the window exists.
     *
     * Also honours `-Dnuvio.selfTest=true`, which starts a run automatically a few seconds after
     * launch. That exists so the suite can be driven from a command line - by CI, by a script, or
     * by an agent that has no way to press a button - and it follows the precedent already set by
     * `nuvio.desktop.smokePlayerUrl` in the same file.
     *
     * The delay is not cosmetic: `App.kt` warms every profile-bound repository asynchronously on
     * first composition, and a suite that started before that finished would report an empty addon
     * list as a configuration fault.
     */
    fun install(window: Window) {
        if (!isDebugBuild) return
        SelfTestHooks.launch = { start(window) }
        if (System.getProperty("nuvio.selfTest")?.equals("true", ignoreCase = true) == true) {
            scope.launch {
                kotlinx.coroutines.delay(AUTOSTART_DELAY_MS)
                start(window)
            }
        }
    }

    fun start(window: Window) {
        if (!isDebugBuild) return
        if (!running.compareAndSet(false, true)) {
            log.i { "self-test already running" }
            return
        }
        scope.launch {
            try {
                run(window)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun run(window: Window) {
        val startedAt = LocalDateTime.now()
        val runDir = DesktopStorage.rootDir
            .resolve("self-test")
            .resolve(SelfTestReportWriter.runDirectoryName(startedAt))
        Files.createDirectories(runDir)
        log.i { "self-test run directory: $runDir" }

        val capture = SelfTestCapture(window, runDir)
        val context = SelfTestContext(
            runDir = runDir,
            capture = capture,
            fixtures = SelfTestFixtures.load(),
            onProgress = { message -> state.onProgress(message) },
        )
        state.begin(runDir)

        // Everything is photographed, so the window has to be in front before anything starts.
        capture.focusWindow()
        if (!capture.isAvailable) {
            context.environment["screenshots"] = "unavailable - java.awt.Robot could not be created"
        }

        val startedAtMs = System.currentTimeMillis()
        // Each suite is awaited in turn and each is allowed to fail without taking the run with it.
        // A harness that dies on suite three and writes nothing is worse than no harness: the run
        // looks like it was never started, which is the failure mode this whole file is a reaction
        // to.
        suites().forEach { (name, suite) ->
            state.onProgress(name)
            runCatching { suite(context) }.onFailure { error ->
                log.w(error) { "$name threw outside a check" }
                context.results += CheckResult(
                    id = name,
                    name = "suite aborted",
                    status = CheckResult.Status.FAIL,
                    durationMs = 0,
                    detail = "The suite threw outside any check: " +
                        "${error::class.simpleName}: ${SelfTestRedaction.text(error.message.orEmpty())}",
                )
            }
        }

        val finishedAt = LocalDateTime.now()
        val run = SelfTestRun(
            startedAt = SelfTestReportWriter.formatTimestamp(startedAt),
            finishedAt = SelfTestReportWriter.formatTimestamp(finishedAt),
            durationMs = System.currentTimeMillis() - startedAtMs,
            environment = context.environment.toMap(),
            checks = context.results.toList(),
        )
        SelfTestReportWriter.write(runDir, run)
        copyDebugLog(runDir)

        state.finish(run)
        log.i { "self-test finished: ${run.passed} passed, ${run.failed} failed, ${run.skipped} skipped" }
        // Puts the evidence in front of whoever pressed the button. The `setup-wizard-renders`
        // artifact was green for four passes while nobody opened it, and every Welcome defect since
        // would have been plain in it - a report that takes a deliberate act to find gets the same
        // treatment.
        openRunDirectory()
    }

    /**
     * The suites, in dependency order.
     *
     * S1 finds sources, S2 mints one into a playable link, S3 plays it. Ordering is load-bearing,
     * and every suite skips with a reason rather than throwing when its input is absent.
     *
     * S4, S5 and S7 are named here as the outstanding work rather than silently missing: seek and
     * track selection, the playback-mode routes and the failure chain, and a real download. See
     * `STATUS.md`.
     */
    private fun suites(): List<Pair<String, suspend (SelfTestContext) -> Unit>> = listOf(
        "S0 Environment" to S0Environment::run,
        "S1 Sources" to S1Sources::run,
        "S2 Debrid" to S2Debrid::run,
        "S3 Playback" to S3Playback::run,
        "S6 Settings and sync" to S6SettingsAndSync::run,
        "S8 UI walk" to S8UiWalk::run,
    )

    /**
     * Copies the tee'd stdout/stderr log beside the report.
     *
     * Copied, not referenced: the log keeps growing after the run and rotates on the next launch, so
     * a path in the report would point at a different file by the time anyone opened it.
     *
     * ⚠ It will not explain a native crash. `DesktopDebugLog` tees the JVM's streams, and libmpv
     * writes from below them through its own handles - if the player dies inside the native layer
     * this log simply ends.
     */
    private fun copyDebugLog(runDir: Path) {
        val source = DesktopDebugLog.currentLogFile ?: return
        runCatching {
            Files.copy(source, runDir.resolve("run.log"), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { log.w(it) { "could not copy the debug log" } }
    }

    /** Opens the run folder in the OS file manager. Same approach as the downloads folder action. */
    private fun openRunDirectory(): Boolean {
        val dir = state.runDirectory ?: return false
        if (!Desktop.isDesktopSupported()) return false
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return false
        if (!desktop.isSupported(Desktop.Action.OPEN)) return false
        return runCatching { desktop.open(dir.toFile()); true }.getOrDefault(false)
    }

    /** Long enough for `warmProfileBoundRepositories` to have finished on a cold start. */
    private const val AUTOSTART_DELAY_MS = 12_000L
}
