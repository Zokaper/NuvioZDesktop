package com.nuvio.app.core.debug.selftest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path

/**
 * What the overlay shows while a run is in progress.
 *
 * Deliberately small and deliberately *not* the report. The report is the artefact; this is only so
 * the person who pressed the button can see that something is happening and where it got to - a run
 * takes minutes, most of it staring at a screen that is being driven by something else, and an app
 * that appears to have frozen invites being killed halfway through.
 *
 * ⚠ The overlay must never intercept pointer input. The suite synthesises real clicks at the centre
 * of the window and photographs what is underneath; a scrim over the top would both eat those
 * clicks and appear in every screenshot. It draws in a corner, and nothing else.
 */
internal class SelfTestUiState {

    var isRunning by mutableStateOf(false)
        private set

    var currentStep by mutableStateOf("")
        private set

    var runDirectory: Path? by mutableStateOf(null)
        private set

    /** Finished summary, or null while a run is in progress. */
    var summary: String? by mutableStateOf(null)
        private set

    /** The last few steps, so a stuck run shows what it is stuck on rather than one frozen line. */
    val recentSteps = mutableStateListOf<String>()

    /**
     * Hides the overlay while a screenshot is being taken.
     *
     * The first run of this harness produced eight otherwise-perfect screenshots with the status
     * card sitting in the top-right corner of every one. That corner is not empty space in this
     * app - the floating nav bar and the hero's own chrome live near it, and the wizard device
     * script has a check specifically about what is pinned where - so evidence with a debug card
     * pasted over it is evidence that cannot answer the question it was taken for.
     */
    var suppressedForCapture by mutableStateOf(false)

    fun begin(directory: Path) {
        runDirectory = directory
        isRunning = true
        summary = null
        recentSteps.clear()
        currentStep = "Starting…"
    }

    fun onProgress(message: String) {
        currentStep = message
        recentSteps += message
        while (recentSteps.size > MAX_RECENT_STEPS) recentSteps.removeAt(0)
    }

    fun finish(run: SelfTestRun) {
        isRunning = false
        currentStep = "Finished"
        summary = "${run.passed} passed · ${run.failed} failed · ${run.skipped} skipped"
    }

    private companion object {
        const val MAX_RECENT_STEPS = 6
    }
}
