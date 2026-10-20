package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCompletionBadgeTest {
    private fun item(status: DownloadStatus) = DownloadItem(
        id = "one", contentType = "movie", parentMetaId = "movie", parentMetaType = "movie",
        videoId = "movie", title = "Movie", streamTitle = "Source", providerName = "Server",
        fileName = "movie.mkv", status = status, createdAtEpochMs = 0L, updatedAtEpochMs = 0L,
    )

    @Test
    fun onlyACompletedTransitionRaisesTheBadgeAndSeenItemsStaySeen() {
        DownloadCompletionBadge.unseen.value = false
        try {
            val active = item(DownloadStatus.Downloading)
            val complete = item(DownloadStatus.Completed)
            DownloadCompletionBadge.onPublished(emptyList(), listOf(complete))
            assertFalse(DownloadCompletionBadge.unseen.value) // Restoring old completed files.
            DownloadCompletionBadge.onPublished(listOf(active), listOf(active.copy(status = DownloadStatus.Failed)))
            assertFalse(DownloadCompletionBadge.unseen.value)
            DownloadCompletionBadge.onPublished(listOf(active), listOf(complete))
            assertTrue(DownloadCompletionBadge.unseen.value)
            DownloadsRepository.markCompletedSeen()
            DownloadCompletionBadge.onPublished(listOf(complete), listOf(complete))
            assertFalse(DownloadCompletionBadge.unseen.value)
        } finally {
            DownloadCompletionBadge.unseen.value = false
        }
    }
}
