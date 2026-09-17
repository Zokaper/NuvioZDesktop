package com.nuvio.app.features.setup

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object AioStreamsCredentialStorage {
    private val store = DesktopStorage.store("nuvio_aiostreams_credentials")

    actual fun load(uuid: String): String? = store.getString(ProfileScopedKey.of(uuid))

    actual fun save(uuid: String, value: String) {
        store.putString(ProfileScopedKey.of(uuid), value)
    }

    actual fun remove(uuid: String) {
        store.remove(ProfileScopedKey.of(uuid))
    }
}
