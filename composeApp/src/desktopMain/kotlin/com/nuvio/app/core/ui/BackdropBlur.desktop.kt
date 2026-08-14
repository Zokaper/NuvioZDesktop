package com.nuvio.app.core.ui

/**
 * Skia renders the blur on every desktop target, so the frosted alphas are always the right ones.
 *
 * ⚠ This actual exists **only** in `NuvioZDesktop`; `nuvio-z` has no `desktopMain`. A new `expect`
 * in shared `commonMain` is the classic way to break this repository while the other one stays
 * green - the Windows MSI job is what proves it compiled.
 */
internal actual fun isBackdropBlurSupported(): Boolean = true
