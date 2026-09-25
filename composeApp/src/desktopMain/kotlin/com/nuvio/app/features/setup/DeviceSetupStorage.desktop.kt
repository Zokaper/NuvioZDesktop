package com.nuvio.app.features.setup

import com.nuvio.app.core.storage.DesktopStorage

/**
 * Desktop has no device steps (no mobile-data rule), so `setupWizardRun` never asks for a device
 * run here; the wizard still records the revision when it finishes, and this keeps it.
 */
internal actual object DeviceSetupStorage {
    private const val revisionKey = "device_setup_revision"
    private val store = DesktopStorage.store("nuvio_device_setup")

    actual fun loadRevision(): Int? = store.getInt(revisionKey)

    actual fun saveRevision(revision: Int) {
        store.putInt(revisionKey, revision)
    }
}
