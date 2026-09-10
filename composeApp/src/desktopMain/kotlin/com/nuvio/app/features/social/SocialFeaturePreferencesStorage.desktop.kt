package com.nuvio.app.features.social

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SocialFeaturePreferencesStorage {
    private val store = DesktopStorage.store("nuvio_social_features")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("social_features"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("social_features"), payload)
    }
}
