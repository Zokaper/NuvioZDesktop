package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.storage.DesktopStorage

internal actual object WhatsNewStorage {
    private const val lastSeenVersionKey = "nuvio_whats_new_last_seen_version"
    private val store = DesktopStorage.store("nuvio_whats_new")

    actual val isDesktop: Boolean = true

    actual fun loadLastSeenVersion(): String? = store.getString(lastSeenVersionKey)

    actual fun saveLastSeenVersion(versionName: String) {
        store.putString(lastSeenVersionKey, versionName)
    }
}
