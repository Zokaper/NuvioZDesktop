package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** A YouTube tab as the Firefox extension reports it. */
internal data class BrowserTab(
    val tabId: Int,
    val url: String,
    val title: String,
    val videoId: String?,
)

internal interface ReactionLinkListener {
    fun onTabsChanged(tabs: List<BrowserTab>)

    /** A tab now shows a different video (the user opened the next reaction in it). */
    fun onTabVideoChanged(tab: BrowserTab)

    fun onConnectionChanged(connected: Boolean)
}

/**
 * The Nuvio Z end of the Firefox "Reaction Link" extension: a WebSocket server on 127.0.0.1.
 *
 * Only connections whose Origin is a Firefox extension (`moz-extension://`) are accepted. A web page
 * cannot forge Origin, so no site can talk to it; anything else running as the user could, but that
 * already has the user's full access. Minimal RFC 6455: text frames, ping/pong, close; one client at
 * a time (a newer connection replaces the old one, e.g. after a Firefox restart).
 *
 * Measured in Stage 0 (S2): background-script WebSocket to localhost works from release Firefox;
 * pause + mute land in ~15 ms, even during a YouTube ad; an in-page "next video" click is reported
 * at once via tabs.onUpdated and again by YouTube's yt-navigate-finish ~0.7 s later.
 */
internal class ReactionLinkServer(
    private val listener: ReactionLinkListener,
    private val ports: List<Int> = DEFAULT_PORTS,
) {
    private val log = Logger.withTag("ReactionLink")
    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile
    var tabs: List<BrowserTab> = emptyList()
        private set

    val connected: Boolean get() = clients.isNotEmpty()

    /** The port actually bound, or null when every candidate was taken. */
    @Volatile
    var port: Int? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ports.firstNotNullOfOrNull { candidate ->
            runCatching { ServerSocket(candidate, 4, InetAddress.getLoopbackAddress()) }.getOrNull()
        }
        if (socket == null) {
            log.w { "no free port among $ports; the browser link is off" }
            running.set(false)
            return
        }
        server = socket
        port = socket.localPort
        log.i { "listening on 127.0.0.1:${socket.localPort}" }
        Thread({ acceptLoop(socket) }, "reaction-link-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        clients.forEach { it.close() }
        clients.clear()
        tabs = emptyList()
    }

    /** Pause and mute the tab's own player: Nuvio Z plays the reaction itself (Handoff). */
    fun quietTab(tabId: Int) = send("""{"type":"quiet","tabId":$tabId}""")

    fun requestTabs() = send("""{"type":"listTabs"}""")

    private fun send(text: String) {
        clients.lastOrNull()?.sendText(text)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val connection = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ serve(connection) }, "reaction-link-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = socket.getOutputStream()
        val request = readHttpHead(input) ?: return socket.close()
        val origin = request.headers["origin"].orEmpty()
        val key = request.headers["sec-websocket-key"]
        if (key == null || !request.headers["upgrade"].orEmpty().equals("websocket", ignoreCase = true)) {
            output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
            return socket.close()
        }
        if (!isExtensionOrigin(origin)) {
            log.w { "refused a connection from origin '$origin'" }
            output.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
            return socket.close()
        }
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n").toByteArray(),
        )
        output.flush()
        val client = Client(socket, output)
        // One extension at a time; a reconnect (Firefox restarted) replaces the previous socket.
        clients.forEach { it.close() }
        clients.clear()
        clients += client
        listener.onConnectionChanged(true)
        try {
            while (running.get()) {
                val frame = readFrame(input) ?: break
                when (frame.opcode) {
                    0x1 -> handle(frame.payload.toString(Charsets.UTF_8))
                    0x8 -> break
                    0x9 -> client.sendFrame(0xA, frame.payload)
                }
            }
        } catch (_: Exception) {
            // Connection dropped.
        } finally {
            client.close()
            if (clients.remove(client) && clients.isEmpty()) {
                tabs = emptyList()
                listener.onTabsChanged(tabs)
                listener.onConnectionChanged(false)
            }
        }
    }

    private fun handle(text: String) {
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "hello", "tabs" -> {
                tabs = message["tabs"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.toTab() }.orEmpty()
                listener.onTabsChanged(tabs)
            }
            "videoChanged" -> {
                val tab = message.toTab() ?: return
                tabs = tabs.map { if (it.tabId == tab.tabId) tab else it }.let { if (it.any { t -> t.tabId == tab.tabId }) it else it + tab }
                listener.onTabsChanged(tabs)
                listener.onTabVideoChanged(tab)
            }
            "tabClosed" -> {
                val id = message["tabId"]?.jsonPrimitive?.intOrNull ?: return
                tabs = tabs.filterNot { it.tabId == id }
                listener.onTabsChanged(tabs)
            }
        }
    }

    private fun JsonObject.toTab(): BrowserTab? {
        val id = this["tabId"]?.jsonPrimitive?.intOrNull ?: return null
        val url = this["url"]?.jsonPrimitive?.contentOrNull ?: return null
        if (this["incognito"]?.jsonPrimitive?.booleanOrNull == true) return null
        return BrowserTab(
            tabId = id,
            url = url,
            title = this["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            videoId = this["videoId"]?.jsonPrimitive?.contentOrNull ?: youTubeVideoId(url),
        )
    }

    private inner class Client(private val socket: Socket, private val output: OutputStream) {
        private val lock = Any()

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

        fun sendFrame(opcode: Int, payload: ByteArray) {
            runCatching {
                synchronized(lock) {
                    output.write(0x80 or opcode)
                    when {
                        payload.size < 126 -> output.write(payload.size)
                        payload.size < 65_536 -> {
                            output.write(126)
                            output.write(payload.size shr 8)
                            output.write(payload.size and 0xFF)
                        }
                        else -> {
                            output.write(127)
                            for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                        }
                    }
                    output.write(payload)
                    output.flush()
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private class HttpHead(val requestLine: String, val headers: Map<String, String>)

    private class Frame(val opcode: Int, val payload: ByteArray)

    private fun readHttpHead(input: DataInputStream): HttpHead? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0 || ++total > 16_384) return null
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
            } else {
                line.append(b.toChar())
            }
        }
        if (lines.isEmpty()) return null
        val headers = lines.drop(1).mapNotNull { header ->
            val colon = header.indexOf(':')
            if (colon <= 0) null else header.substring(0, colon).trim().lowercase() to header.substring(colon + 1).trim()
        }.toMap()
        return HttpHead(lines.first(), headers)
    }

    private fun readFrame(input: DataInputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.readUnsignedByte()
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = input.readUnsignedShort().toLong()
        if (length == 127L) length = input.readLong()
        if (length > MAX_FRAME_BYTES) return null
        val masked = second and 0x80 != 0
        val mask = if (masked) ByteArray(4).also(input::readFully) else null
        val payload = ByteArray(length.toInt()).also(input::readFully)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Frame(first and 0x0F, payload)
    }

    companion object {
        /** The extension tries these in order; the first one Nuvio Z could bind is the one it finds. */
        val DEFAULT_PORTS = listOf(47123, 47124, 47125, 47126)
        private const val MAX_FRAME_BYTES = 1L shl 20
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun isExtensionOrigin(origin: String): Boolean = origin.startsWith("moz-extension://")

        fun acceptKey(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()))

        private val VIDEO_ID = Regex("""[?&]v=([A-Za-z0-9_-]{6,})""")
        private val SHORT_ID = Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""")

        fun youTubeVideoId(url: String): String? =
            VIDEO_ID.find(url)?.groupValues?.get(1) ?: SHORT_ID.find(url)?.groupValues?.get(1)
    }
}
