package com.nuvio.app.features.downloads

internal actual object DownloadsLiveStatusPlatform {
    actual fun onItemsChanged(items: List<DownloadItem>) = Unit

    /** Desktop has no notification surface of its own; the Downloads tab shows this. */
    actual fun onBatchesChanged(batches: List<DownloadBatch>) = Unit

    /** Nothing to ask for: desktop posts no download notifications. */
    actual fun onDownloadRequested() = Unit

    /**
     * The window is the app: there is no backgrounded state in which a notification would reach the
     * user and the in-app prompt would not, so "ready to choose" is always the in-app prompt.
     */
    actual fun isAppInForeground(): Boolean = true

    actual fun notifyChoice(notice: DownloadChoiceNotice) = Unit

    actual fun clearChoice(batchId: String) = Unit

    /** A desktop process is not frozen behind a window; discovery simply keeps running. */
    actual fun onDiscoveryRunning(running: Boolean) = Unit
}
