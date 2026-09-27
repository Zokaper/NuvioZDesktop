package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Going on is always the footer. Working sources get Next; nothing working yet gets a quiet "Skip for
 * now" (people press a big Next without reading, which is why the question never had one); a setup
 * path greys Next until a source is installed.
 */
class SetupAdvanceTest {
    @Test fun nothingWorkingYetOffersSkipNotNext() {
        listOf(SetupSourcesStatus.None, SetupSourcesStatus.NeedsAttention, SetupSourcesStatus.Checking).forEach { status ->
            assertEquals(
                SetupAdvance.Skip,
                setupAdvanceFor(SetupStep.Sources, SetupSourcesState(mode = SetupSourcesMode.Choice), status),
                "$status",
            )
        }
    }

    @Test fun workingSourcesGoOnWithNext() {
        listOf(SetupSourcesStatus.Recommended, SetupSourcesStatus.Custom).forEach { status ->
            assertEquals(
                SetupAdvance.Shown,
                setupAdvanceFor(SetupStep.Sources, SetupSourcesState(mode = SetupSourcesMode.Choice), status),
                "$status",
            )
        }
    }

    @Test fun aSetupPathGreysNextUntilASourceIsInstalled() {
        assertEquals(
            SetupAdvance.Disabled,
            setupAdvanceFor(SetupStep.Sources, SetupSourcesState(mode = SetupSourcesMode.Recommended), SetupSourcesStatus.Custom),
        )
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
