package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.createDirectories

private const val TRANSFER_BUFFER_BYTES = 64 * 1024

private val desktopDownloadHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal actual object DownloadsPlatformDownloader {
    private val downloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    actual fun start(
        request: DownloadPlatformRequest,
        listener: DownloadTransferListener,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            val destination = File(downloadsDir, request.destinationFileName)
            val tempFile = File(downloadsDir, "${request.destinationFileName}.part")
            var downloadedBytes = 0L

            try {
                var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
                downloadedBytes = resumeFromBytes
                var attemptedRangeRequest = resumeFromBytes > 0L
                var response = sendDownloadRequest(request, if (attemptedRangeRequest) resumeFromBytes else null)

                if (attemptedRangeRequest && response.statusCode() == 416) {
                    // The range starts past the end of the object. When that is because the
                    // partial file already holds every byte, the download is finished and
                    // re-fetching it from zero would throw away a completed transfer.
                    val reportedTotal =
                        parseContentRangeTotal(response.headers().firstValue("Content-Range").orElse(null))
                            ?: request.knownTotalBytes

                    if (reportedTotal != null && tempFile.length() == reportedTotal) {
                        val finalized = finalizePartialFile(tempFile, destination)
                        if (finalized == null) {
                            listener.onFailed(
                                DownloadFailureReason.Transient,
                                "Failed to finalize download file",
                                downloadedBytes,
                            )
                        } else {
                            listener.onCompleted(finalized.first, finalized.second)
                        }
                        return@launch
                    }

                    tempFile.delete()
                    resumeFromBytes = 0L
                    downloadedBytes = 0L
                    attemptedRangeRequest = false
                    response = sendDownloadRequest(request, null)
                }

                if (response.statusCode() !in 200..299) {
                    listener.onFailed(
                        failureReasonForHttpStatus(response.statusCode()),
                        "Download failed with HTTP ${response.statusCode()}",
                        downloadedBytes,
                    )
                    return@launch
                }

                val isPartialResume = attemptedRangeRequest && response.statusCode() == 206 && resumeFromBytes > 0L
                val appendToTemp = isPartialResume
                val startingBytes = if (appendToTemp) resumeFromBytes else 0L
                // A 200 answer to a range request means the server either ignores ranges or
                // has told us, via If-Range, that the object changed. Either way the bytes
                // on disk no longer belong to this response.
                if (!appendToTemp && tempFile.exists()) {
                    tempFile.delete()
                }
                downloadedBytes = startingBytes

                val totalBytes = resolveTotalBytes(
                    startingBytes = startingBytes,
                    isPartialResume = isPartialResume,
                    contentRangeHeader = response.headers().firstValue("Content-Range").orElse(null),
                    contentLength = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull(),
                )
                listener.onOpened(
                    resumedFromBytes = startingBytes,
                    totalBytes = totalBytes,
                    etag = response.headers().firstValue("ETag").orElse(null),
                    lastModified = response.headers().firstValue("Last-Modified").orElse(null),
                )
                listener.onProgress(downloadedBytes, totalBytes)

                var lastReportedBytes = downloadedBytes
                var lastReportedAtEpochMs = DownloadsClock.nowEpochMs()

                response.body().use { input ->
                    FileOutputStream(tempFile, appendToTemp).use { output ->
                        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read.toLong()

                            // Reporting every chunk used to re-serialise and rewrite the whole
                            // downloads payload thousands of times per file.
                            val nowEpochMs = DownloadsClock.nowEpochMs()
                            if (
                                shouldReportProgress(
                                    downloadedBytes = downloadedBytes,
                                    lastReportedBytes = lastReportedBytes,
                                    nowEpochMs = nowEpochMs,
                                    lastReportedAtEpochMs = lastReportedAtEpochMs,
                                )
                            ) {
                                lastReportedBytes = downloadedBytes
                                lastReportedAtEpochMs = nowEpochMs
                                listener.onProgress(downloadedBytes, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                listener.onProgress(downloadedBytes, totalBytes)

                // Reaching the end of the body is not the same as having the whole file. A
                // dropped connection or a short error body ends the stream just as cleanly
                // as a finished download does.
                when (val completion = evaluateCompletion(downloadedBytes, totalBytes)) {
                    is DownloadCompletion.Short -> {
                        listener.onFailed(
                            DownloadFailureReason.Incomplete,
                            "Transfer was cut short. Resume to continue.",
                            completion.downloadedBytes,
                        )
                        return@launch
                    }

                    is DownloadCompletion.Overrun -> {
                        tempFile.delete()
                        listener.onFailed(
                            DownloadFailureReason.SourceChanged,
                            "The source changed, so the download restarted",
                            0L,
                        )
                        return@launch
                    }

                    DownloadCompletion.Complete -> Unit
                }

                val finalized = finalizePartialFile(tempFile, destination)
                if (finalized == null) {
                    listener.onFailed(
                        DownloadFailureReason.Transient,
                        "Failed to finalize download file",
                        downloadedBytes,
                    )
                    return@launch
                }
                listener.onCompleted(finalized.first, finalized.second)
            } catch (error: CancellationException) {
                // A pause is a deliberate stop, not a failure.
                listener.onPaused(currentPartialBytes(tempFile, downloadedBytes))
                throw error
            } catch (error: Throwable) {
                listener.onFailed(
                    DownloadFailureReason.Transient,
                    error.message ?: "Download failed",
                    currentPartialBytes(tempFile, downloadedBytes),
                )
            }
        }

        return DesktopDownloadsTaskHandle(job)
    }

    actual fun partialFileBytes(destinationFileName: String): Long {
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        return runCatching { tempFile.takeIf { it.exists() }?.length() ?: 0L }.getOrDefault(0L)
    }

    actual fun freeStorageBytes(): Long =
        runCatching { downloadsDir.usableSpace }.getOrDefault(-1L).takeIf { it > 0L } ?: -1L

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempFile = File(downloadsDir, "$destinationFileName.part")
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return File(downloadsDir, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val directory = downloadsDir
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            val opened = runCatching { desktop.open(directory) }.isSuccess
            if (opened) return true
        }

        return openDirectoryWithPlatformCommand(directory)
    }

    private fun sendDownloadRequest(
        request: DownloadPlatformRequest,
        rangeStart: Long?,
    ): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder()
            .uri(URI(request.sourceUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
        request.sourceHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
            // Without a validator the server cannot tell us the bytes it is about to send
            // belong to a different file than the partial one on disk, and we would
            // append them blindly.
            resumeValidator(request.resumeEtag, request.resumeLastModified)?.let {
                builder.header("If-Range", it)
            }
        }
        return desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }
}

private fun openDirectoryWithPlatformCommand(directory: File): Boolean {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        osName.contains("mac") -> listOf("open", directory.absolutePath)
        osName.contains("win") -> listOf("explorer", directory.absolutePath)
        else -> listOf("xdg-open", directory.absolutePath)
    }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}

private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()

/** Moves a verified partial file into place, returning its URI and confirmed size. */
private fun finalizePartialFile(tempFile: File, destination: File): Pair<String, Long>? {
    return runCatching {
        if (destination.exists()) {
            destination.delete()
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }
        val finalSize = destination.length()
        if (!destination.exists() || finalSize <= 0L) return null
        destination.toURI().toString() to finalSize
    }.getOrNull()
}

/** The byte count actually on disk, which is what a resume will continue from. */
private fun currentPartialBytes(tempFile: File, fallback: Long): Long =
    runCatching { tempFile.takeIf { it.exists() }?.length() }.getOrNull() ?: fallback
