package com.nuvio.app.core.debug

import com.nuvio.app.core.storage.DesktopStorage
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

/**
 * A file the tester can send back after a session.
 *
 * A desktop playback failure is usually reported as "it just stopped", and the evidence for why
 * is spread across places that do not share a destination: Kermit (`Logger.*`), plain
 * stdout/stderr from libraries that never adopted it, and whatever kills the AWT event thread.
 *
 * **Everything is captured by teeing stdout and stderr, deliberately - not with a Kermit
 * `LogWriter`.** Kermit's default JVM writer already prints to stdout, so adding a file writer
 * on top wrote every log line twice: once in Kermit's format via the tee, once in the writer's.
 * One capture point, one copy.
 *
 * Debug builds only - `isDebugBuild` is `-Dnuvio.debugTools=true` on desktop, so nothing here
 * runs in the shipped app.
 *
 * **What this cannot capture:** libmpv writes from native code through its own handles, below
 * the JVM. If the player dies inside the native layer, expect this log to end without
 * explaining itself.
 */
object DesktopDebugLog {

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileStampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    @Volatile
    private var sink: PrintStream? = null

    /** The log written by the most recent [install], or null if logging is off. */
    @Volatile
    var currentLogFile: Path? = null
        private set

    /**
     * Starts capturing. Call once, first thing in `main`, before anything else logs.
     *
     * Returns the log path, or null when this is not a debug build.
     */
    fun install(): Path? {
        if (!isDebugBuild) return null
        if (sink != null) return currentLogFile

        val logDir = DesktopStorage.resolveAppDataDir().resolve("logs")
        if (!logDir.exists()) runCatching { Files.createDirectories(logDir) }
        val logFile = logDir.resolve("nuvio-debug-${LocalDateTime.now().format(fileStampFormat)}.log")

        val stream = runCatching {
            // Autoflush, because a session that ends in a hard crash must still leave the lines
            // that led up to it on disk.
            PrintStream(FileOutputStream(logFile.toFile(), true), true, Charsets.UTF_8)
        }.getOrNull() ?: return null

        sink = stream
        currentLogFile = logFile

        stream.println("=== Nuvio Z desktop debug log ===")
        stream.println("started   ${LocalDateTime.now().format(timestampFormat)}")
        stream.println("os        ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        stream.println("java      ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        stream.println("log file  $logFile")
        stream.println("=================================")

        System.setOut(PrintStream(TeeLineStream(System.out, stream, "out"), true, Charsets.UTF_8))
        System.setErr(PrintStream(TeeLineStream(System.err, stream, "err"), true, Charsets.UTF_8))
        captureUncaughtExceptions()

        return logFile
    }

    /**
     * Anything that escapes a thread - including the AWT event thread, which is how a desktop
     * playback crash normally presents - lands in the file instead of vanishing with the window.
     */
    private fun captureUncaughtExceptions() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            sink?.let { file ->
                file.println("${LocalDateTime.now().format(timestampFormat)} [fatal] uncaught on thread '${thread.name}'")
                error.printStackTrace(file)
            }
            existing?.uncaughtException(thread, error)
        }
    }

    /**
     * Mirrors a stream byte-for-byte to the console and, one whole line at a time, to the file.
     *
     * Buffering to the newline is what makes the timestamps meaningful: `print` without a
     * terminator is common (Kermit's writer among others), and stamping every fragment would
     * shred single log lines across several timestamped entries.
     */
    private class TeeLineStream(
        private val console: PrintStream,
        private val file: PrintStream,
        private val tag: String,
    ) : OutputStream() {

        private val pending = ByteArrayOutputStream()

        @Synchronized
        override fun write(b: Int) {
            console.write(b)
            if (b == '\n'.code) {
                flushLine()
            } else if (b != '\r'.code) {
                pending.write(b)
            }
        }

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            for (index in offset until offset + length) write(bytes[index].toInt())
        }

        @Synchronized
        override fun flush() {
            console.flush()
            // Deliberately does not flush `pending`: a partial line stays buffered until its
            // newline arrives, so it is written once, whole, with one timestamp.
        }

        private fun flushLine() {
            val line = pending.toString(Charsets.UTF_8)
            pending.reset()
            file.println("${LocalDateTime.now().format(timestampFormat)} [$tag] $line")
        }
    }
}
