package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.io.File
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStorageTest {
    @Test
    fun everyDesktopStoreNameIsClassified() {
        val base = listOf(File("src/desktopMain"), File("composeApp/src/desktopMain"))
            .first { it.isDirectory }
        val names = base.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                desktopStoreNamePattern.findAll(file.readText()).map { match ->
                    match.groupValues.drop(1).first(String::isNotEmpty)
                }
            }
            .toSet()

        assertTrue(names.size > 45, "the source scan found only $names - the pattern has stopped matching")
        val unclassified = names.filter { LocalStoreRegistry.find(it) == null }
        assertTrue(unclassified.isEmpty(), "classify these in LocalStoreRegistry: $unclassified")
    }

    @Test
    fun unchanged_operations_do_not_rewrite_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            val sentinel = FileTime.fromMillis(1_000L)
            Files.setLastModifiedTime(file, sentinel)

            store.putString("key", "value")
            store.remove("missing")
            store.removeAll(listOf("also-missing"))

            assertEquals(sentinel, Files.getLastModifiedTime(file))

            store.putString("key", "updated")

            assertNotEquals(sentinel, Files.getLastModifiedTime(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun signOutKeepsDeviceLocalStoresAndOnlyTheDeviceRevisionFromTheMixedStore() {
        val directory = Files.createTempDirectory("desktop-storage-wipe-test")
        try {
            val setupFile = directory.resolve("nuvio_device_setup.properties")
            DesktopStorage.Store(setupFile).apply {
                putInt("device_setup_revision", 10)
                putBoolean("setup_arrival_pending_2", true)
                putBoolean("advanced_setup_opened_2", true)
            }
            val deviceFile = directory.resolve("nuvio_discord_rich_presence.properties")
            DesktopStorage.Store(deviceFile).putBoolean("enabled", true)
            val accountFile = directory.resolve("nuvio_player_settings.properties")
            DesktopStorage.Store(accountFile).putString("playback_mode", "INSTANT")
            val unknownFile = directory.resolve("nuvio_future_account_store.properties")
            DesktopStorage.Store(unknownFile).putString("secret", "account-data")
            val downloads = Files.createDirectories(directory.resolve("downloads"))
            val media = downloads.resolve("episode.mkv")
            Files.writeString(media, "kept")

            DesktopStorage.wipeExcept(directory)

            val setup = DesktopStorage.Store(setupFile)
            assertEquals(10, setup.getInt("device_setup_revision"))
            assertNull(setup.getBoolean("setup_arrival_pending_2"))
            assertNull(setup.getBoolean("advanced_setup_opened_2"))
            assertTrue(DesktopStorage.Store(deviceFile).getBoolean("enabled") == true)
            assertFalse(Files.exists(accountFile))
            assertFalse(Files.exists(unknownFile))
            assertTrue(Files.exists(media))
        } finally {
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private companion object {
        val desktopStoreNamePattern = Regex(
            """DesktopStorage\.store\(\s*\"([^\"]+)\"|(?:STORE_NAME|desktopUpdaterPreferencesName)\s*=\s*\"([^\"]+)\"""",
        )
    }
}
