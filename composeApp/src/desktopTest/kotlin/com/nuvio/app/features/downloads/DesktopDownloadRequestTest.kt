package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop download request carries no `HttpRequest.timeout`.
 *
 * On the Java 17 runtime the app ships, a request that follows a redirect keeps that timer armed
 * for the whole body, so every debrid transfer (resolver -> CDN) longer than the deadline was cut
 * at exactly 60 s with `IOException: closed` and resumed (Modern Family on desktop, Phase 9). The
 * test JVM is newer and does not have the defect, so an end-to-end case cannot catch it coming
 * back: the invariant itself is what is pinned here. The header deadline that replaced it is
 * covered end to end by `DesktopDownloadQueueE2ETest`'s never-answering server.
 */
class DesktopDownloadRequestTest {
    private val request = DownloadPlatformRequest(
        downloadId = "d1",
        sourceUrl = "https://resolver.example/stream/abc",
        sourceHeaders = mapOf("User-Agent" to "Nuvio"),
        destinationFileName = "e1.mkv",
        resumeEtag = "\"v1\"",
    )

    @Test
    fun aFreshRequestHasNoTimeout() {
        val built = DownloadsPlatformDownloader.buildDownloadRequest(request, rangeStart = null)
        assertTrue(built.timeout().isEmpty, "HttpRequest.timeout outlives a redirect on Java 17 and cuts the body")
        assertTrue(built.headers().firstValue("Range").isEmpty)
    }

    @Test
    fun aResumeHasNoTimeoutAndAsksForTheRestWithItsValidator() {
        val built = DownloadsPlatformDownloader.buildDownloadRequest(request, rangeStart = 81_059_840L)
        assertTrue(built.timeout().isEmpty)
        assertEquals("bytes=81059840-", built.headers().firstValue("Range").get())
        assertEquals("\"v1\"", built.headers().firstValue("If-Range").get())
    }
}
