package com.nuvio.app.features.tray

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Start with Windows (Social V2 tray mode, 2026-10-18): Nuvio starts in the tray when the user signs in
 * to Windows, so friends' pop-ups reach them without opening it first.
 *
 * **The registry value is the setting.** One value per install channel under the user's own `Run` key,
 * named after the launcher (`Nuvio Z`, `Nuvio Z Debug`), so the release app and the Debug channel are
 * switched on independently, and Task Manager's Startup apps list shows and can disable it like any other.
 * Nothing is mirrored into Nuvio's own storage: a copy could only disagree with what Windows will run.
 *
 * Only installed builds (`jpackage.app-path`): a Gradle run has no launcher worth starting at sign-in.
 * Written with `reg.exe`, which every Windows has, rather than a native call.
 *
 * ⚠ Uninstalling leaves the value behind (the MSI does not know about it). Windows skips a missing
 * program at sign-in, so it costs nothing, and reinstalling to the same place makes it work again.
 */
internal object DesktopStartWithWindows {
    private val log = Logger.withTag("StartWithWindows")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The argument the `Run` value passes: start hidden, in the tray. */
    const val BackgroundArg = "--background"

    internal const val RunKey = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""

    /** Null until read; reading `reg.exe` is a process launch, so it never happens on the UI thread. */
    private val _enabled = MutableStateFlow<Boolean?>(null)
    val enabled: StateFlow<Boolean?> = _enabled.asStateFlow()

    /** The installed launcher, or null where Start with Windows cannot apply. */
    val launcher: File?
        get() {
            if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return null
            return System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
        }

    val isAvailable: Boolean get() = launcher != null

    fun isBackgroundLaunch(args: Array<String>): Boolean = args.any { it.trim().equals(BackgroundArg, ignoreCase = true) }

    /**
     * Reads the value, and points it back at this launcher if it names another one (an install moved).
     * Called at startup and when the settings page opens.
     */
    fun refresh() {
        scope.launch {
            val exe = launcher ?: run { _enabled.value = false; return@launch }
            val registry = Registry(RunKey)
            val current = registry.read(valueName(exe))
            if (current != null && current != command(exe)) {
                log.i { "the startup entry named another launcher; repointing it" }
                registry.write(valueName(exe), command(exe))
            }
            _enabled.value = current != null
        }
    }

    fun setEnabled(enabled: Boolean) {
        val exe = launcher ?: return
        _enabled.value = enabled
        scope.launch {
            val registry = Registry(RunKey)
            val ok = if (enabled) registry.write(valueName(exe), command(exe)) else registry.delete(valueName(exe))
            if (!ok) log.w { "could not ${if (enabled) "add" else "remove"} the startup entry" }
            _enabled.value = registry.read(valueName(exe)) != null
        }
    }

    internal fun valueName(exe: File): String = exe.nameWithoutExtension

    internal fun command(exe: File): String = "\"${exe.absolutePath}\" $BackgroundArg"

    /** A registry key through `reg.exe`. [key] is parameterised so tests can use a key of their own. */
    internal class Registry(private val key: String) {
        /** The value's data, or null when it does not exist. */
        fun read(name: String): String? {
            val result = run("query", key, "/v", name) ?: return null
            if (result.first != 0) return null
            // "    <name>    REG_SZ    <data>"
            return result.second.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith(name) && it.contains("REG_SZ") }
                ?.substringAfter("REG_SZ")
                ?.trim()
        }

        fun write(name: String, data: String): Boolean =
            run("add", key, "/v", name, "/t", "REG_SZ", "/d", escape(data), "/f")?.first == 0

        fun delete(name: String): Boolean {
            if (read(name) == null) return true
            return run("delete", key, "/v", name, "/f")?.first == 0
        }

        /** Deletes [key] itself; tests only. */
        fun deleteKey(): Boolean = run("delete", key, "/f")?.first == 0

        private fun run(vararg args: String): Pair<Int, String>? = runCatching {
            val process = ProcessBuilder(listOf("reg.exe") + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.exitValue() to output
        }.getOrNull()

        /**
         * `reg.exe` reads its command line with the C runtime's rules, where `\"` is a literal quote. The JDK
         * quotes an argument that contains spaces but leaves the quotes inside it alone, so they are escaped
         * here; a backslash before a quote doubles, as the same rules require.
         */
        private fun escape(data: String): String = buildString {
            var backslashes = 0
            for (c in data) {
                when (c) {
                    '\\' -> backslashes++
                    '"' -> {
                        append("\\".repeat(backslashes * 2 + 1)).append('"')
                        backslashes = 0
                        continue
                    }
                    else -> {
                        append("\\".repeat(backslashes)).append(c)
                        backslashes = 0
                        continue
                    }
                }
            }
            // Trailing backslashes sit before the closing quote the JDK adds to an argument with a space.
            append("\\".repeat(if (data.any { it == ' ' || it == '\t' }) backslashes * 2 else backslashes))
        }
    }
}
