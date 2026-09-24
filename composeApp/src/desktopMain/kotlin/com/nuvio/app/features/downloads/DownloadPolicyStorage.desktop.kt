package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadPolicyStorage {
    private val store = DesktopStorage.store("nuvio_download_policy")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("download_policy"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("download_policy"), payload)
    }
}
