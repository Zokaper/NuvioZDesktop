package com.nuvio.app.core.storage

import com.nuvio.app.core.build.AppVersionConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    val cacheDir: Path by lazy {
        resolveCacheDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    /**
     * Clears account, credential and cache stores while preserving installation preferences.
     * Only direct `*.properties` children are considered: downloads, logs, updater payloads and
     * the native-player directories also live under [rootDir] and are deliberately untouched.
     */
    fun wipeExceptDeviceLocal() {
        wipeExcept(rootDir)
    }

    internal fun wipeExcept(directory: Path) {
        if (!directory.exists()) return
        val files = Files.list(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(STORE_SUFFIX) }
                .toList()
        }
        files.forEach { file ->
            val name = file.fileName.toString().removeSuffix(STORE_SUFFIX)
            if (!LocalStoreRegistry.isWiped(name)) return@forEach

            val preservedKeys = LocalStoreRegistry.find(name)?.preservedKeys.orEmpty()
            if (preservedKeys.isEmpty()) {
                synchronized(stores) {
                    stores.remove(name)?.clearInMemory()
                }
                Files.deleteIfExists(file)
            } else {
                Store(file).retainOnly(preservedKeys)
                synchronized(stores) {
                    stores[name]?.clearInMemory()
                }
            }
        }
    }

    // Internal rather than private so the debug log writer lands beside the state it describes.
    // Two copies of this OS branching would drift the moment either one changed.
    internal fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        // A debug-channel build installs beside the release app, so it must not read or write
        // the release app's state. Sharing it would let a build published to test a fix corrupt
        // the settings of the app it is being compared against - and every stored-state fault in
        // STATUS.md was found by comparing exactly those two.
        val directoryName = if (AppVersionConfig.DESKTOP_DEBUG_CHANNEL) "Nuvio Z Debug" else "Nuvio Z"
        val linuxDirectoryName = if (AppVersionConfig.DESKTOP_DEBUG_CHANNEL) "nuvio-z-debug" else "nuvio-z"
        return when {
            // Kept distinct from official Nuvio so both can be installed at once
            // without sharing, and corrupting, one another's stored state.
            osName.contains("mac") -> userHome.resolve("Library/Application Support/$directoryName")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve(directoryName)
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve(linuxDirectoryName)
            }
        }
    }

    private fun resolveCacheDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Caches/Nuvio")
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                (localAppData?.let(Paths::get) ?: userHome.resolve("AppData/Local")).resolve("Nuvio/Cache")
            }
            else -> {
                val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                (xdgCache?.let(Paths::get) ?: userHome.resolve(".cache")).resolve("nuvio")
            }
        }
    }

    internal class Store(
        private val file: Path,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            val changed = if (value == null) {
                properties.remove(key) != null
            } else {
                properties.setProperty(key, value) != value
            }
            if (changed) persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            if (properties.remove(key) != null) persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            var changed = false
            keys.forEach { key ->
                if (properties.remove(key) != null) changed = true
            }
            if (changed) persist()
        }

        fun retainOnly(keys: Set<String>) = synchronized(lock) {
            ensureLoaded()
            val changed = properties.stringPropertyNames()
                .filterNot(keys::contains)
                .map(properties::remove)
                .isNotEmpty()
            if (changed) persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            if (!file.exists()) return
            runCatching {
                Files.newInputStream(file).use { input ->
                    properties.load(input)
                }
            }
        }

        private fun persist() {
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { output ->
                properties.store(output, "Nuvio desktop preferences")
            }
        }
    }

    private const val STORE_SUFFIX = ".properties"
}
