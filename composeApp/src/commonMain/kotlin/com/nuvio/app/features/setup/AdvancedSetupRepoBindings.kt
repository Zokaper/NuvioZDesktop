package com.nuvio.app.features.setup

import androidx.compose.runtime.Composable

// ⚠ **The one per-repository file of Advanced Setup** - the `ZProfileSyncContributors.kt` pattern.
// Everything else under `features/setup/` is byte-identical with the mobile repository; this file is
// not, because what the mobile copy binds (the Random Episode store) does not exist here. Never carry
// the mobile copy across as-is.

/** Whether Random Episode is on, for the Detail page preview's Shuffle action. Always false on desktop. */
@Composable
internal fun rememberAdvancedRandomEpisodeAvailable(): Boolean = false

/**
 * Detail page → Random Episode is mobile-only: `advancedSetupControls` never lists it on desktop, so
 * this is never reached. Empty rather than absent so the shared screen compiles unchanged.
 */
@Composable
internal fun AdvancedRandomEpisodeRow() = Unit
