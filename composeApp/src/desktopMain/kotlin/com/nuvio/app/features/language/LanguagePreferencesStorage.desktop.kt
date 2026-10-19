package com.nuvio.app.features.language

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object LanguagePreferencesStorage {
    private val store = DesktopStorage.store("nuvio_language_preferences")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("language_preferences"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("language_preferences"), payload)
    }
}
