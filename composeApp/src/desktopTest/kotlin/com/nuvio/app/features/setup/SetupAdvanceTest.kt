package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals

/** People press Next without reading, so Sources only offers it once a source exists. */
class SetupAdvanceTest {
    @Test fun theSourceQuestionHasNoNext() {
        assertEquals(SetupAdvance.Hidden, setupAdvanceFor(SetupStep.Sources, SetupSourcesState(mode = SetupSourcesMode.Choice)))
    }

    @Test fun aSetupPathGreysNextUntilASourceIsInstalled() {
        assertEquals(SetupAdvance.Disabled, setupAdvanceFor(SetupStep.Sources, SetupSourcesState(mode = SetupSourcesMode.Recommended)))
        assertEquals(SetupAdvance.Disabled, setupAdvanceFor(SetupStep.Sources, SetupSourcesState(mode = SetupSourcesMode.Manual)))
        assertEquals(
            SetupAdvance.Shown,
            setupAdvanceFor(SetupStep.Sources, SetupSourcesState(mode = SetupSourcesMode.Recommended, configuredName = "AIOStreams Z")),
        )
    }

    @Test fun everyOtherStepKeepsNext() {
        SetupStep.entries.filter { it != SetupStep.Sources }.forEach { step ->
            assertEquals(SetupAdvance.Shown, setupAdvanceFor(step, SetupSourcesState()))
        }
    }
}
