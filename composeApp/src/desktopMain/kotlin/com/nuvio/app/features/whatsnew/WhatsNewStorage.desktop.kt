package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage

internal actual object WhatsNewStorage {
    // Written by earlier builds; read for migration only, never rewritten. 0.1.23-alpha-z6 and
    // every stable desktop build before it wrote only the last-seen version.
    private const val lastSeenVersionKey = "nuvio_whats_new_last_seen_version"
    private const val ackSerialKey = "ack_serial"

    private const val ackSeqKey = "ack_seq"
    private const val ackSeenKey = "ack_seen"
    private const val viewedSeqKey = "viewed_seq"
    private const val viewedSeenKey = "viewed_seen"
    private const val ackDebugBuildKey = "ack_debug_build"
    private val store = DesktopStorage.store("nuvio_whats_new")

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

    actual fun load(): StoredWhatsNew {
        fun seen(seqKey: String, seenKey: String): SeenEvents? =
            store.getInt(seqKey)?.let { SeenEvents(it.coerceAtLeast(0), SeenEvents.decodeAbove(store.getString(seenKey))) }
        return StoredWhatsNew(
            acknowledged = seen(ackSeqKey, ackSeenKey),
            viewed = seen(viewedSeqKey, viewedSeenKey),
            debugBuild = store.getInt(ackDebugBuildKey),
            legacySerial = store.getInt(ackSerialKey),
            legacyLastSeenVersion = store.getString(lastSeenVersionKey),
        )
    }

    actual fun save(state: WhatsNewState) {
        store.putInt(ackSeqKey, state.acknowledged.floor)
        store.putString(ackSeenKey, SeenEvents.encodeAbove(state.acknowledged.above))
        store.putInt(viewedSeqKey, state.viewed.floor)
        store.putString(viewedSeenKey, SeenEvents.encodeAbove(state.viewed.above))
        store.putInt(ackDebugBuildKey, state.debugBuild)
    }
}
