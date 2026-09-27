package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories

private const val TRANSFER_BUFFER_BYTES = 64 * 1024

/** How often the watchdog checks whether anything has arrived. */
// The interval now comes from `DownloadTransfer.kt`, shared with the Android watchdog.

/**
 * `connectTimeout` and the header deadline in [sendDownloadRequest] both stop short of the body.
 * Every byte after the headers is read from a stream with no deadline of its own, which is why
 * the transfer loop runs its own stall watchdog.
 *
 * ⚠ **Never `HttpRequest.timeout` (Phase 9, Modern Family on desktop).** On the Java 17 runtime
 * the app ships, a request that follows a redirect keeps that timer armed for the whole body: at
 * the deadline the read dies with `IOException: closed` (cause `HttpTimeoutException`) while
 * bytes are still arriving. Debrid links always redirect (resolver -> CDN), so every transfer
 * longer than 60 s was cut at 60.0 s, retried and resumed - "Retrying shortly" at ~60 % on each
 * episode. JDK 25 does not do it and neither does 17 without a redirect, which is why the test
 * JVM never saw it. The header deadline is enforced around `sendAsync` instead.
 *
 * **A client per attempt, not one for the app (`.52`).** Debrid links reach the CDN through one
 * resolver host that speaks HTTP/2, so every episode of a season shared a single connection for
 * its first hop. On Android that connection died silently and every request after it - retries
 * included - waited out the full deadline for twenty minutes while a fresh process fetched the
 * same links in two seconds. `java.net.http` pools the same way and has no HTTP/2 ping to notice,
 * so a transfer gets connections of its own and they close with it.
 */
private fun newTransferClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

/**
 * `shutdownNow`, not `close`: `close` waits for exchanges still in flight, and after a timeout
 * this attempt no longer cares about any of them. Reflective because the build compiles against
 * JDK 17, where it does not exist (JDK 21+); the app runs on a newer bundled runtime. On 17 the
 * client is left to idle out with its connections.
 */
private fun HttpClient.closeQuietly() {
    runCatching { HttpClient::class.java.getMethod("shutdownNow").invoke(this) }
}

private const val NO_RESPONSE_MESSAGE = "This source isn't answering. Try again, or pick another source."

internal actual object DownloadsPlatformDownloader {
    // Desktop runs its own transfers in-process: the slot count is the device's downloads-at-once
    // setting and the queue keeps its silence watchdog. Nothing on desktop pauses the queue on the
    // system's behalf, so nothing resumes it either - the queue takes such items back itself.
    actual val transferHost: TransferHost = TransferHost.InProcess(recoversSystemPauses = false)

    private val downloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    actual fun start(
        request: DownloadPlatformRequest,
        listener: DownloadTransferListener,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val handle = DesktopDownloadsTaskHandle(job)
        val lastByteAtEpochMs = AtomicLong(DownloadsClock.nowEpochMs())
        val stalled = AtomicBoolean(false)

        // A connection that goes quiet without closing has no natural end: the read
        // below blocks forever, cancelling the coroutine cannot interrupt it, and the
        // download sits at its last percentage holding a transfer slot for good.
        // Closing the stream out from under the read is the only thing that unblocks
        // it, and this is what decides when to.
        val watchdog = scope.launch {
            while (true) {
                delay(stallCheckIntervalMs(DownloadsTiming.stallTimeoutMs))
                if (DownloadsClock.nowEpochMs() - lastByteAtEpochMs.get() < DownloadsTiming.stallTimeoutMs) {
                    continue
                }
                // Flagged before the close, never after: closing unblocks the reader
                // immediately, and it reaches the error handler on its own thread. Set
                // this afterwards and the transfer reports a bare "closed" rather than
                // what actually happened to it.
                handle.takeActiveStream()?.let { stream ->
                    stalled.set(true)
                    runCatching { stream.close() }
                }
                lastByteAtEpochMs.set(DownloadsClock.nowEpochMs())
            }
        }

        scope.launch {
            val destination = File(downloadsDir, request.destinationFileName)
            val tempFile = File(downloadsDir, "${request.destinationFileName}.part")
            var downloadedBytes = 0L
            val client = newTransferClient()
            val startedAtNs = System.nanoTime()
            // Whether the server answered at all. A timeout before it did is `NoResponse`.
            var opened = false

            try {
                var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
                downloadedBytes = resumeFromBytes
                var attemptedRangeRequest = resumeFromBytes > 0L
                var response = sendDownloadRequest(client, request, if (attemptedRangeRequest) resumeFromBytes else null)
                opened = true
                DownloadDiagnostics.http(
                    request.downloadId,
                    "http_response",
                    "code=${response.statusCode()} proto=${response.version()} ms=${(System.nanoTime() - startedAtNs) / 1_000_000L}",
                )
                lastByteAtEpochMs.set(DownloadsClock.nowEpochMs())

                if (attemptedRangeRequest && response.statusCode() == 416) {
                    // The range starts past the end of the object. When that is because the
                    // partial file already holds every byte, the download is finished and
                    // re-fetching it from zero would throw away a completed transfer.
                    val reportedTotal =
                        parseContentRangeTotal(response.headers().firstValue("Content-Range").orElse(null))
                            ?: request.knownTotalBytes
                    // Nothing reads this body, and an unread one holds its connection open.
                    runCatching { response.body().close() }

                    if (
                        rangeNotSatisfiableOutcome(reportedTotal, tempFile.length()) ==
                        RangeNotSatisfiableOutcome.PartialIsComplete
                    ) {
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
                    response = sendDownloadRequest(client, request, null)
                    lastByteAtEpochMs.set(DownloadsClock.nowEpochMs())
                }

                if (response.statusCode() !in 200..299) {
                    val statusCode = response.statusCode()
                    runCatching { response.body().close() }
                    listener.onFailed(
                        failureReasonForHttpStatus(statusCode),
                        "Download failed with HTTP $statusCode",
                        downloadedBytes,
                    )
                    return@launch
                }

                val isPartialResume =
                    responseAppendsToPartial(attemptedRangeRequest, response.statusCode(), resumeFromBytes)
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
                    handle.attachStream(input)
                    lastByteAtEpochMs.set(DownloadsClock.nowEpochMs())
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
                            // Proof of life for the watchdog, which is otherwise the only
                            // thing that can end a read on a connection gone quiet.
                            lastByteAtEpochMs.set(nowEpochMs)
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
                handle.detachStream()

                // The stream can also end because the watchdog closed it. That is a
                // stalled transfer, not a finished one, and it must not be allowed to
                // fall through to the completion check as a short read.
                if (stalled.get()) {
                    listener.onFailed(
                        DownloadFailureReason.Transient,
                        "The source stopped sending data. Retrying.",
                        currentPartialBytes(tempFile, downloadedBytes),
                    )
                    return@launch
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
                when {
                    // Pausing now closes the stream so the read actually stops, which
                    // surfaces here as an IO error rather than a cancellation. It is
                    // still a pause.
                    handle.isCancelled -> listener.onPaused(
                        currentPartialBytes(tempFile, downloadedBytes),
                    )

                    stalled.get() -> listener.onFailed(
                        DownloadFailureReason.Transient,
                        "The source stopped sending data. Retrying.",
                        currentPartialBytes(tempFile, downloadedBytes),
                    )

                    // Nothing came back at all - see `DownloadFailureReason.NoResponse`.
                    error is HttpTimeoutException && !opened -> {
                        DownloadDiagnostics.http(
                            request.downloadId,
                            "http_failed",
                            "error=${error::class.simpleName} ms=${(System.nanoTime() - startedAtNs) / 1_000_000L}",
                        )
                        listener.onFailed(
                            DownloadFailureReason.NoResponse,
                            NO_RESPONSE_MESSAGE,
                            currentPartialBytes(tempFile, downloadedBytes),
                        )
                    }

                    else -> listener.onFailed(
                        DownloadFailureReason.Transient,
                        error.message ?: "Download failed",
                        currentPartialBytes(tempFile, downloadedBytes),
                    )
                }
            } finally {
                watchdog.cancel()
                handle.detachStream()
                client.closeQuietly()
            }
        }

        return handle
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
        val removed = runCatching { file.delete() }.getOrDefault(false)
        removeEmptyLayoutFolders(file, downloadsDir)
        return removed
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
        // An organized file whose absolute path moved with the app's data folder.
        localFileUri?.toLocalFileOrNull()
            ?.let { relativeByFolderName(it, downloadsDir) }
            ?.let { resolveInsideRoot(downloadsDir, it) }
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }
        return File(downloadsDir, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun relativePathOf(localFileUri: String?): String? {
        val file = localFileUri?.takeIf { it.isNotBlank() }?.toLocalFileOrNull() ?: return null
        return relativeInsideRoot(file, downloadsDir)
    }

    actual fun existsInDownloads(relativePath: String): Boolean =
        resolveInsideRoot(downloadsDir, relativePath)?.exists() == true

    actual fun fileUriFor(relativePath: String): String? =
        resolveInsideRoot(downloadsDir, relativePath)?.toURI()?.toString()

    actual fun moveCompletedFile(localFileUri: String, relativePath: String): Boolean {
        val root = downloadsDir
        val source = localFileUri.toLocalFileOrNull()
            ?.takeIf { it.isFile && relativeInsideRoot(it, root) != null }
            ?: return false
        val target = resolveInsideRoot(root, relativePath) ?: return false
        return runCatching {
            if (target.exists()) return false
            target.parentFile?.mkdirs()
            // A rename inside one folder tree; Files.move without REPLACE_EXISTING never overwrites.
            java.nio.file.Files.move(source.toPath(), target.toPath())
            target.isFile
        }.getOrDefault(false)
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

    /**
     * Sends [request] and waits for its headers for at most the stall deadline (so the harness can
     * shorten it like every other one). Past it, throws `HttpTimeoutException` - the `NoResponse`
     * path. The body is not bound by it; see the note on [newTransferClient].
     */
    private fun sendDownloadRequest(
        client: HttpClient,
        request: DownloadPlatformRequest,
        rangeStart: Long?,
    ): HttpResponse<java.io.InputStream> {
        val pending = client.sendAsync(buildDownloadRequest(request, rangeStart), HttpResponse.BodyHandlers.ofInputStream())
        return try {
            pending.get(DownloadsTiming.stallTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            pending.cancel(true)
            throw HttpTimeoutException("request timed out")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    /** The download request. Deliberately no `timeout(...)` - see [newTransferClient]. */
    internal fun buildDownloadRequest(
        request: DownloadPlatformRequest,
        rangeStart: Long?,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI(request.sourceUrl))
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
        return builder.build()
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

/**
 * Handle over a transfer whose read loop is blocking, not suspending.
 *
 * Cancelling the job is not enough to stop it. A thread parked in a socket read
 * observes nothing until that read returns, so a download on a connection that
 * has gone quiet ignored both pause and cancel and kept its transfer slot. The
 * stream reference exists so the read can be ended from the outside.
 */
private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    private val activeStream = AtomicReference<InputStream?>(null)
    private val cancelled = AtomicBoolean(false)

    /** True once [cancel] has been called, so a resulting IO error reads as a pause. */
    val isCancelled: Boolean
        get() = cancelled.get()

    fun attachStream(stream: InputStream) {
        activeStream.set(stream)
    }

    fun detachStream() {
        activeStream.set(null)
    }

    /**
     * Claims the stream in progress, if there still is one.
     *
     * Handed over rather than closed here so the caller can record why it is ending
     * the read before the read observes it: closing unblocks the reading thread at
     * once, and whatever it is meant to report has to be true by then.
     */
    fun takeActiveStream(): InputStream? = activeStream.getAndSet(null)

    override fun cancel() {
        cancelled.set(true)
        takeActiveStream()?.let { runCatching { it.close() } }
        job.cancel()
    }
}

/** [file] as a '/'-separated path inside [root], or null when it is not inside it. */
private fun relativeInsideRoot(file: File, root: File): String? = runCatching {
    val rootPath = root.canonicalPath + File.separator
    file.canonicalPath.takeIf { it.startsWith(rootPath) }
        ?.removePrefix(rootPath)
        ?.replace(File.separatorChar, '/')
        ?.takeIf { it.isNotEmpty() }
}.getOrNull()

/** [relativePath] under [root], refusing any path that could leave it. */
private fun resolveInsideRoot(root: File, relativePath: String): File? {
    val segments = relativePath.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || ':' in it }) return null
    val file = File(root, segments.joinToString(File.separator))
    return file.takeIf { relativeInsideRoot(it, root) != null }
}

/** The part of [file]'s path after ".../<parent>/<root name>/", for a data folder that has moved. */
private fun relativeByFolderName(file: File, root: File): String? {
    val marker = "/${root.parentFile?.name}/${root.name}/"
    val path = file.path.replace(File.separatorChar, '/')
    val index = path.indexOf(marker).takeIf { it >= 0 } ?: return null
    return path.substring(index + marker.length).takeIf { it.isNotEmpty() }
}

/** Deletes the folders between [file] and [root] that are now empty; stops at the first that is not. */
private fun removeEmptyLayoutFolders(file: File, root: File) {
    runCatching {
        val rootPath = root.canonicalPath
        var folder = file.parentFile
        while (folder != null && folder.canonicalPath != rootPath && relativeInsideRoot(folder, root) != null) {
            // File.delete removes a folder only when it is empty, so nothing else can go with it.
            if (!folder.delete()) break
            folder = folder.parentFile
        }
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
