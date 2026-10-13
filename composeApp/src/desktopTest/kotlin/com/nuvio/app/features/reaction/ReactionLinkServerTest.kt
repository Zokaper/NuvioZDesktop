package com.nuvio.app.features.reaction

import java.io.DataInputStream
import java.net.Socket
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReactionLinkServerTest {
    private val events = LinkedBlockingQueue<Any>()
    private val listener = object : ReactionLinkListener {
        override fun onTabsChanged(tabs: List<BrowserTab>) { events.add(tabs) }
        override fun onTabVideoChanged(tab: BrowserTab) { events.add(tab) }
        override fun onConnectionChanged(connected: Boolean) { events.add(connected) }
    }
    // Port 0: an ephemeral port, so the test never collides with a running Nuvio Z.
    private val server = ReactionLinkServer(listener, ports = listOf(0)).apply { start() }

    @AfterTest
    fun stop() = server.stop()

    private class RawClient(port: Int, origin: String) {
        val socket = Socket("127.0.0.1", port)
        val input = DataInputStream(socket.getInputStream())
        val status: String

        init {
            val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
            socket.getOutputStream().write(
                ("GET /reaction-link HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nOrigin: $origin\r\n\r\n").toByteArray(),
            )
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                head.append(b.toChar())
            }
            status = head.lineSequence().first()
            if (status.contains("101")) {
                assertTrue(head.contains("Sec-WebSocket-Accept: ${ReactionLinkServer.acceptKey(key)}"), head.toString())
            }
        }

        fun send(text: String) {
            val payload = text.toByteArray()
            val mask = Random.nextBytes(4)
            val out = socket.getOutputStream()
            out.write(0x81)
            when {
                payload.size < 126 -> out.write(0x80 or payload.size)
                else -> {
                    out.write(0x80 or 126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xFF)
                }
            }
            out.write(mask)
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
            out.flush()
        }

        fun receive(): String {
            socket.soTimeout = 3_000
            val first = input.read()
            assertEquals(0x81, first)
            var length = input.readUnsignedByte() and 0x7F
            if (length == 126) length = input.readUnsignedShort()
            return String(ByteArray(length).also(input::readFully))
        }
    }

    private inline fun <reified T> next(): T {
        while (true) {
            val event = events.poll(3, TimeUnit.SECONDS) ?: error("no ${T::class.simpleName} event")
            if (event is T) return event
        }
    }

    @Test
    fun aWebPageCannotConnect() {
        val client = RawClient(server.port!!, "https://evil.example")
        assertTrue(client.status.contains("403"), client.status)
        assertFalse(server.connected)
    }

    @Test
    fun theExtensionReportsTabsAndPrivateWindowsAreDropped() {
        val client = RawClient(server.port!!, "moz-extension://5b0c1d2e-aaaa-bbbb-cccc-000000000000")
        assertTrue(client.status.contains("101"), client.status)
        assertEquals(true, next<Boolean>())
        client.send(
            """{"type":"hello","tabs":[
              {"tabId":4,"url":"https://www.youtube.com/watch?v=fyUFRCd4Afw","title":"Levi vs Female Titan - YouTube","incognito":false},
              {"tabId":9,"url":"https://www.youtube.com/watch?v=privateOne1","title":"secret","incognito":true}]}""",
        )
        val tabs = next<List<*>>().filterIsInstance<BrowserTab>()
        assertEquals(listOf(4), tabs.map { it.tabId })
        assertEquals("fyUFRCd4Afw", tabs.single().videoId)
    }

    @Test
    fun aTabMovingToAnotherVideoIsReportedAndQuietReachesTheExtension() {
        val client = RawClient(server.port!!, "moz-extension://5b0c1d2e-aaaa-bbbb-cccc-000000000000")
        next<Boolean>()
        client.send("""{"type":"videoChanged","tabId":4,"url":"https://www.youtube.com/watch?v=mU3yyNcKRK4","title":"E28 - YouTube"}""")
        val tab = next<BrowserTab>()
        assertEquals(4, tab.tabId)
        assertEquals("mU3yyNcKRK4", tab.videoId)

        server.quietTab(4)
        assertEquals("""{"type":"quiet","tabId":4}""", client.receive())
    }

    @Test
    fun aDroppedConnectionClearsTheTabs() {
        val client = RawClient(server.port!!, "moz-extension://x")
        next<Boolean>()
        client.send("""{"type":"tabs","tabs":[{"tabId":1,"url":"https://youtu.be/abcdefghijk","title":"t"}]}""")
        next<List<*>>()
        client.socket.close()
        assertEquals(false, next<Boolean>())
        assertTrue(server.tabs.isEmpty())
    }

    @Test
    fun videoIdsFromBothUrlShapes() {
        assertEquals("fyUFRCd4Afw", ReactionLinkServer.youTubeVideoId("https://www.youtube.com/watch?v=fyUFRCd4Afw&t=30s"))
        assertEquals("abcdefghijk", ReactionLinkServer.youTubeVideoId("https://youtu.be/abcdefghijk?si=x"))
        assertNull(ReactionLinkServer.youTubeVideoId("https://www.youtube.com/feed/subscriptions"))
        assertNotNull(server.port)
    }
}
