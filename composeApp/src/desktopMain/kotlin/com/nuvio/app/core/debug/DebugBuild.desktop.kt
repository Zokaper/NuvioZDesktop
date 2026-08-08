package com.nuvio.app.core.debug

/**
 * Opt-in on desktop, via `-Dnuvio.debugTools=true`.
 *
 * A packaged desktop build carries no equivalent of Android's debuggable manifest flag and no
 * Kotlin/Native debug-binary flag, so there is nothing to read. Defaulting to false keeps the
 * diagnostic surfaces out of the shipped Windows app, and the property gives a developer
 * running from Gradle a way to switch them on deliberately.
 */
internal actual val isDebugBuild: Boolean
    get() = System.getProperty("nuvio.debugTools")?.equals("true", ignoreCase = true) == true

internal actual object PlatformPlaybackDebugTools {
    actual val throttleOptionsMbps: List<Int> = emptyList()
    actual var throttleMbps: Int
        get() = 0
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
}
