package com.nuvio.app.features.downloads

internal actual object DownloadsLiveStatusPlatform {
    actual fun onItemsChanged(items: List<DownloadItem>) = Unit

    /** Desktop has no notification surface of its own; the Downloads tab shows this. */
    actual fun onBatchesChanged(batches: List<DownloadBatch>) = Unit

    /** Nothing to ask for: desktop posts no download notifications. */
    actual fun onDownloadRequested() = Unit
}
