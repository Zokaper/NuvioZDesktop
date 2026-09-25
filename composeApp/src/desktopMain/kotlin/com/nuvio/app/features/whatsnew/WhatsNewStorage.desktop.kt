package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage

internal actual object WhatsNewStorage {
    private const val lastSeenVersionKey = "nuvio_whats_new_last_seen_version"
    private const val ackSerialKey = "ack_serial"
    private const val ackDebugBuildKey = "ack_debug_build"
    private val store = DesktopStorage.store("nuvio_whats_new")

    actual val isDesktop: Boolean = true

    actual fun loadLastSeenVersion(): String? = store.getString(lastSeenVersionKey)

    actual fun saveLastSeenVersion(versionName: String) {
        store.putString(lastSeenVersionKey, versionName)
    }

    /**
     * Desktop's own identity (Phase 9 fix): `VERSION_NAME` here is a stale mobile value, so the
     * version is `DESKTOP_VERSION_NAME` and the serial is desktop's `RELEASE_SERIAL`. On the debug
     * channel the version carries the debug build as a fourth component (`0.1.23-alpha-z6.63`).
     */
    actual val releaseIdentity: WhatsNewReleaseIdentity
        get() {
            val name = AppVersionConfig.DESKTOP_VERSION_NAME
            val debugBuild = if (AppVersionConfig.DESKTOP_DEBUG_CHANNEL) {
                name.substringAfterLast('.').toIntOrNull()
            } else {
                null
            }
            return WhatsNewReleaseIdentity(
                family = "desktop",
                serial = AppVersionConfig.RELEASE_SERIAL,
                versionName = if (debugBuild != null) name.substringBeforeLast('.') else name,
                debugBuild = debugBuild,
                platform = ChangelogPlatform.DESKTOP,
            )
        }

    actual fun loadAck(): WhatsNewAck? {
        val serial = store.getInt(ackSerialKey) ?: return null
        return WhatsNewAck(serial, store.getInt(ackDebugBuildKey) ?: 0)
    }

    actual fun saveAck(ack: WhatsNewAck) {
        store.putInt(ackSerialKey, ack.serial)
        store.putInt(ackDebugBuildKey, ack.debugBuild)
    }
}
