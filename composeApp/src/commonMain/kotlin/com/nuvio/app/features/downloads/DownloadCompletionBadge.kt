package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow

/** Upstream's unseen-completion badge, fed by Z's device-wide queue rather than the retired queue. */
internal object DownloadCompletionBadge {
    val unseen = MutableStateFlow(false)

    fun onPublished(previous: List<DownloadItem>, items: List<DownloadItem>) {
        val statuses = previous.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && statuses[it.id]?.let { old -> old != DownloadStatus.Completed } == true }) {
            unseen.value = true
        }
    }
}
