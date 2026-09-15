package com.nuvio.app.features.social

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SocialStorage {
    private val store = DesktopStorage.store("nuvio_social")

    actual fun loadPayload(profileId: String): String? = store.getString("state_$profileId")

    actual fun savePayload(profileId: String, payload: String) {
        store.putString("state_$profileId", payload)
    }

    actual fun loadOutbox(profileId: String): String? = store.getString("outbox_$profileId")

    actual fun saveOutbox(profileId: String, payload: String) {
        store.putString("outbox_$profileId", payload)
    }

    actual fun loadAbandonedJoinRequests(profileId: String): String? = store.getString("abandoned_join_$profileId")

    actual fun saveAbandonedJoinRequests(profileId: String, payload: String?) {
        if (payload == null) store.remove("abandoned_join_$profileId") else store.putString("abandoned_join_$profileId", payload)
    }
}
