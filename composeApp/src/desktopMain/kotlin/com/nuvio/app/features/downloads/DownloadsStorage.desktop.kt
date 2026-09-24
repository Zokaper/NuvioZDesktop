package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.MAX_PROFILES

/**
 * One device payload (Phase 9). Desktop used to keep one payload per profile under
 * `downloads_<profile>`; those are read once by the store's migration when no device payload
 * exists yet, and left in place.
 */
internal actual object DownloadsStorage {
    private val store = DesktopStorage.store("nuvio_downloads")
    private const val deviceKey = "downloads_device"

    actual fun loadPayload(): String? =
        store.getString(deviceKey)

    actual fun savePayload(payload: String) {
        store.putString(deviceKey, payload)
    }

    actual fun saveCorruptPayload(payload: String) {
        store.putString("${deviceKey}_corrupt", payload)
    }

    actual fun loadLegacyProfilePayloads(): Map<Int, String> =
        (1..MAX_PROFILES).mapNotNull { profile ->
            store.getString(ProfileScopedKey.of("downloads", profile))
                ?.takeIf { it.isNotBlank() }
                ?.let { profile to it }
        }.toMap()
}
