package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage

internal actual object NetworkQualityStorage {
    private val store = DesktopStorage.store("nuvio_network_quality")

    // Not profile-scoped, unlike most storages here. The measured speed of a network belongs
    // to the device, and scoping it would make the first play on every profile a guess again.
    actual fun loadEstimatesJson(): String? = store.getString("estimates_json")

    actual fun saveEstimatesJson(json: String) {
        store.putString("estimates_json", json)
    }
}
