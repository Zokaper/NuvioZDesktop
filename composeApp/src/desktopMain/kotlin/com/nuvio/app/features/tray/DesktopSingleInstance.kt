package com.nuvio.app.features.tray

import co.touchlab.kermit.Logger
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * One Nuvio per install channel (Social V2 tray mode, 2026-10-16).
 *
 * Once closing hides Nuvio to the tray, launching it again from the Start menu or a shortcut used to
 * start a second, full copy beside the first - two Social layers, two players, one set of stored state.
 * Now a launch first claims a lock in the channel's own data folder (so the release app, the Debug
 * channel and vanilla Nuvio stay independent). The first launch keeps it for the life of the process
 * and listens on a loopback port; a later launch hands its `nuvio://` / `stremio://` links to that
 * one, asks it to show its window, and exits.
 *
 * The lock is the OS's: it dies with the process, so a crash never leaves a stale claim. A launch that
 * finds the lock held but no answer (the first copy still starting, or quitting) keeps retrying, and
 * takes the lock itself if it comes free.
 *
 * Only installed builds take part (`jpackage.app-path` is set by the packaged launcher): a Gradle or
 * Hot Reload run must never hand itself off to an installed Debug build and vanish.
 */
internal object DesktopSingleInstance {
    private val log = Logger.withTag("SingleInstance")

    /** The claim, held for the life of the process; the reference keeps the lock's channel open. */
    @Volatile
    private var held: Primary? = null

    /** Becomes the one Nuvio, or hands [urls] to the running one and exits the process. */
    fun enforce(urls: List<String>) {
        if (System.getProperty("jpackage.app-path").isNullOrBlank()) return
        val dir = runCatching { DesktopStorage.rootDir }.getOrNull() ?: return
        when (val outcome = acquireOrHandOff(dir, urls, ::onMessage)) {
            is Outcome.Claimed -> held = outcome.primary
            Outcome.HandedOff -> {
                log.i { "handed off to the running copy (${urls.size} link(s))" }
                exitProcess(0)
            }
            Outcome.NoAnswer -> {
                // A copy holds the lock and will not answer. Starting beside it is what this exists to stop.
                log.w { "the running copy did not answer; exiting" }
                exitProcess(0)
            }
            Outcome.Unavailable -> log.w { "single-instance lock unavailable; starting without it" }
        }
    }

    private fun onMessage(message: InstanceMessage) {
        log.i { "another launch handed off (${message.urls.size} link(s)); showing the window" }
        if (message.urls.isNotEmpty()) SwingUtilities.invokeLater { message.urls.forEach(::handleAppUrl) }
        DesktopTrayController.show()
        DesktopTrayController.bringToFront()
    }

    internal sealed interface Outcome {
        data class Claimed(val primary: Primary) : Outcome
        data object HandedOff : Outcome
        data object NoAnswer : Outcome
        data object Unavailable : Outcome
    }

    internal data class InstanceMessage(val urls: List<String>)

    internal fun acquireOrHandOff(
        dir: Path,
        urls: List<String>,
        onMessage: (InstanceMessage) -> Unit,
        deadlineMs: Long = HandOffDeadlineMs,
    ): Outcome {
        val deadline = System.currentTimeMillis() + deadlineMs
        val links = urls.filter { url -> url.none { it == '\n' || it == '\r' } }.take(MaxUrls)
        while (true) {
            val primary = try {
                Primary.tryStart(dir, onMessage)
            } catch (e: IOException) {
                return Outcome.Unavailable
            }
            if (primary != null) return Outcome.Claimed(primary)
            readInfo(dir)?.let { info ->
                // Windows gives the foreground only to the process the user just launched - this one.
                // Passing that on is what lets the running copy's window come to the front instead of
                // flashing in the taskbar.
                if (DesktopHostOs.current == DesktopHostOs.WINDOWS) WindowsForeign.allowSetForegroundWindow(info.pid)
                if (send(info, links)) return Outcome.HandedOff
            }
            if (System.currentTimeMillis() >= deadline) return Outcome.NoAnswer
            Thread.sleep(RetryMs)
        }
    }

    /** The running copy's side: the lock, and the loopback listener whose address is in [InfoFile]. */
    internal class Primary private constructor(
        private val channel: FileChannel,
        private val lock: FileLock,
        private val server: ServerSocket,
    ) : AutoCloseable {
        override fun close() {
            runCatching { server.close() }
            runCatching { lock.release() }
            runCatching { channel.close() }
        }

        companion object {
            /** Null when another process holds the lock. */
            fun tryStart(dir: Path, onMessage: (InstanceMessage) -> Unit): Primary? {
                Files.createDirectories(dir)
                val channel = FileChannel.open(dir.resolve(LockFile), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    return null
                }
                try {
                    val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
                    val token = newToken()
                    writeInfo(dir, Info(server.localPort, token, ProcessHandle.current().pid()))
                    thread(isDaemon = true, name = "nuvio-single-instance") { serve(server, token, onMessage) }
                    return Primary(channel, lock, server)
                } catch (e: IOException) {
                    runCatching { lock.release() }
                    runCatching { channel.close() }
                    throw e
                }
            }

            private fun serve(server: ServerSocket, token: String, onMessage: (InstanceMessage) -> Unit) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (server.isClosed) return else continue
                    }
                    socket.use { client ->
                        runCatching {
                            client.soTimeout = SocketTimeoutMs
                            // Bounded: the sender closes its side when done, and nothing legitimate is this long.
                            val lines = client.getInputStream().readNBytes(MaxMessageBytes).decodeToString().lines()
                            if (lines.firstOrNull() != token) return@runCatching
                            val urls = lines.drop(1).filter { it.startsWith(UrlPrefix) }.map { it.removePrefix(UrlPrefix) }.take(MaxUrls)
                            onMessage(InstanceMessage(urls))
                            client.getOutputStream().apply { write(Ack.encodeToByteArray()); flush() }
                        }
                    }
                }
            }
        }
    }

    private fun send(info: Info, urls: List<String>): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), info.port), SocketTimeoutMs)
            socket.soTimeout = SocketTimeoutMs
            val message = (listOf(info.token) + urls.map { UrlPrefix + it }).joinToString("\n", postfix = "\n")
            socket.getOutputStream().apply { write(message.encodeToByteArray()); flush() }
            socket.shutdownOutput()
            socket.getInputStream().readNBytes(Ack.length).decodeToString() == Ack
        }
    }.getOrDefault(false)

    internal data class Info(val port: Int, val token: String, val pid: Long)

    private fun writeInfo(dir: Path, info: Info) {
        val target = dir.resolve(InfoFile)
        val temp = dir.resolve("$InfoFile.tmp")
        Files.writeString(temp, "${info.port}\n${info.token}\n${info.pid}\n")
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readInfo(dir: Path): Info? = runCatching {
        val lines = Files.readAllLines(dir.resolve(InfoFile))
        Info(port = lines[0].trim().toInt(), token = lines[1].trim(), pid = lines[2].trim().toLong())
    }.getOrNull()

    private fun newToken(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val LockFile = "instance.lock"
    private const val InfoFile = "instance.port"
    private const val UrlPrefix = "url "
    private const val Ack = "ok\n"
    private const val HandOffDeadlineMs = 10_000L
    private const val RetryMs = 250L
    private const val SocketTimeoutMs = 2_000
    private const val MaxMessageBytes = 64 * 1024
    private const val MaxUrls = 16
}
