package com.nuvio.app.features.downloads

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A media host that misbehaves the way real ones do.
 *
 * Every download fault this app has had to recover from is a property of the *server*
 * rather than of the app, which is why they could only ever be reproduced by starting
 * a real download and waiting for a bad one: a debrid host that drops the connection
 * partway through the file, one that accepts the request and then goes quiet without
 * closing anything, one whose signed link has expired by the time the queue reaches
 * it, and one that answers with the "your file is being prepared" placeholder video.
 *
 * Written on a raw socket rather than `com.sun.net.httpserver`, because the faults
 * that matter here are exactly the ones a well-behaved HTTP server will not produce:
 * a body cut off mid-flight with no trailer and no close, and a connection that stays
 * open forever with nothing on it.
 */
internal class FaultyMediaServer : AutoCloseable {

    /** What the server should do with the next request for a given path. */
    sealed interface Behavior {
        /** Serve the file, honouring `Range`. */
        data object Serve : Behavior

        /**
         * Send [bytesBeforeDrop] bytes of the body and then close the socket.
         *
         * This is the "closed" failure: a complete, plausible-looking response whose
         * body simply stops. The transfer sees an IO error with no status attached.
         */
        data class DropConnection(val bytesBeforeDrop: Long) : Behavior

        /**
         * Send [bytesBeforeSilence] bytes and then hold the connection open forever.
         *
         * Nothing about this looks like an error from the client's side; a blocking
         * read on it simply never returns. Only the transfer's own stall watchdog can
         * end it, which is what makes this the one fault that used to wedge a slot.
         */
        data class GoSilent(val bytesBeforeSilence: Long) : Behavior

        /** Answer as an expired signed link does. */
        data class Reject(val statusCode: Int) : Behavior

        /**
         * Answer with a complete, valid, far-too-small file.
         *
         * What a debrid provider serves while the real file is still queued.
         */
        data class Placeholder(val sizeBytes: Int) : Behavior
    }

    private val files = ConcurrentHashMap<String, ByteArray>()
    private val behaviors = ConcurrentHashMap<String, MutableList<Behavior>>()
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "faulty-media-server").apply { isDaemon = true }
    }
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var running = true

    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "faulty-media-accept") {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                openSockets += client
                workers.execute {
                    runCatching { serve(client) }
                    openSockets -= client
                    runCatching { client.close() }
                }
            }
        }
    }

    fun urlFor(path: String): String = "http://127.0.0.1:$port$path"

    /** Publishes [content] at [path], served correctly unless a fault is queued for it. */
    fun publish(path: String, content: ByteArray) {
        files[path] = content
    }

    /**
     * Queues faults for the next requests to [path], one per request, in order.
     *
     * Requests past the end of the list are served normally, so a test says "fail the
     * first two attempts" rather than having to describe every attempt that follows.
     */
    fun failNextRequests(path: String, vararg faults: Behavior) {
        behaviors[path] = faults.toMutableList()
    }

    fun requestCount(path: String): Int = requestCounts[path]?.get() ?: 0

    private fun nextBehavior(path: String): Behavior {
        val queued = behaviors[path] ?: return Behavior.Serve
        synchronized(queued) {
            if (queued.isEmpty()) return Behavior.Serve
            return queued.removeAt(0)
        }
    }

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val path = requestLine.split(' ').getOrNull(1) ?: return

        var rangeStart = 0L
        while (true) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator == -1) continue
            val name = header.substring(0, separator).trim().lowercase()
            val value = header.substring(separator + 1).trim()
            if (name == "range") {
                rangeStart = value.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
            }
        }

        requestCounts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        val content = files[path]
        val output = client.getOutputStream()
        if (content == null) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        when (val behavior = nextBehavior(path)) {
            is Behavior.Reject -> {
                output.write(
                    ("HTTP/1.1 ${behavior.statusCode} Gone\r\nContent-Length: 0\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                output.flush()
            }

            is Behavior.Placeholder -> {
                val body = ByteArray(behavior.sizeBytes) { 0 }
                writeHeaders(output, status = 200, bodyLength = body.size.toLong(), rangeStart = null, total = body.size.toLong())
                output.write(body)
                output.flush()
            }

            is Behavior.Serve -> writeBody(output, content, rangeStart, limit = Long.MAX_VALUE)

            is Behavior.DropConnection -> {
                writeBody(output, content, rangeStart, limit = behavior.bytesBeforeDrop)
                // Abrupt: no FIN through the normal path, so the client is reading a
                // body that is simply cut off.
                runCatching { client.setSoLinger(true, 0) }
                runCatching { client.close() }
            }

            is Behavior.GoSilent -> {
                writeBody(output, content, rangeStart, limit = behavior.bytesBeforeSilence)
                while (running && !client.isClosed) {
                    Thread.sleep(50L)
                }
            }
        }
    }

    private fun writeBody(output: OutputStream, content: ByteArray, rangeStart: Long, limit: Long) {
        val start = rangeStart.coerceIn(0L, content.size.toLong())
        val remaining = content.size - start
        writeHeaders(
            output = output,
            status = if (rangeStart > 0L) 206 else 200,
            bodyLength = remaining,
            rangeStart = start.takeIf { rangeStart > 0L },
            total = content.size.toLong(),
        )

        var written = 0L
        var offset = start.toInt()
        while (offset < content.size && written < limit) {
            val chunk = minOf(CHUNK_BYTES.toLong(), limit - written, (content.size - offset).toLong()).toInt()
            output.write(content, offset, chunk)
            output.flush()
            offset += chunk
            written += chunk
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        bodyLength: Long,
        rangeStart: Long?,
        total: Long,
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(if (status == 206) " Partial Content" else " OK").append("\r\n")
        builder.append("Content-Type: video/mp4\r\n")
        builder.append("Content-Length: ").append(bodyLength).append("\r\n")
        builder.append("Accept-Ranges: bytes\r\n")
        // A validator, so the resume path sends If-Range the way it does against a
        // real host rather than silently skipping it.
        builder.append("ETag: \"nuvio-test-").append(total).append("\"\r\n")
        if (rangeStart != null) {
            builder.append("Content-Range: bytes ").append(rangeStart).append('-')
                .append(total - 1).append('/').append(total).append("\r\n")
        }
        builder.append("Connection: close\r\n\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    override fun close() {
        running = false
        openSockets.forEach { runCatching { it.close() } }
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    private companion object {
        const val CHUNK_BYTES = 16 * 1024
    }
}
