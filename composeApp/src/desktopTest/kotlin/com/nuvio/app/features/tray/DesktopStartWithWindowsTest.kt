package com.nuvio.app.features.tray

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStartWithWindowsTest {
    private fun profile(index: Int, pin: Boolean = false) =
        NuvioProfile(id = "id-$index", profileIndex = index, name = "p$index", pinEnabled = pin)

    @Test fun theRememberedProfileIsEnteredWithoutAsking() {
        val state = ProfileState(listOf(profile(1), profile(2)), hasEverSelectedProfile = true, rememberLastProfileEnabled = true)
        assertEquals(2, backgroundStartupProfile(state, activeProfileIndex = 2)?.profileIndex)
    }

    @Test fun theLastProfileIsUsedEvenWhenThePickerIsOn() {
        val state = ProfileState(listOf(profile(1), profile(2)), hasEverSelectedProfile = true, rememberLastProfileEnabled = false)
        assertEquals(2, backgroundStartupProfile(state, activeProfileIndex = 2)?.profileIndex)
    }

    @Test fun severalProfilesNeverChosenWaitForTheWindow() {
        assertNull(backgroundStartupProfile(ProfileState(listOf(profile(1), profile(2))), activeProfileIndex = 1))
    }

    @Test fun anOnlyProfileIsEnteredLikeTheGateDoes() {
        val state = ProfileState(listOf(profile(1)))
        assertEquals(1, backgroundStartupProfile(state, activeProfileIndex = 1)?.profileIndex)
    }

    @Test fun aPinIsNeverSkipped() {
        val remembered = ProfileState(listOf(profile(1, pin = true), profile(2)), hasEverSelectedProfile = true, rememberLastProfileEnabled = true)
        assertNull(backgroundStartupProfile(remembered, activeProfileIndex = 1))
        assertNull(backgroundStartupProfile(ProfileState(listOf(profile(1, pin = true))), activeProfileIndex = 1))
    }

    @Test fun theBackgroundArgumentIsRecognised() {
        assertTrue(DesktopStartWithWindows.isBackgroundLaunch(arrayOf("--background")))
        assertTrue(DesktopStartWithWindows.isBackgroundLaunch(arrayOf("nuvio://x", " --Background ")))
        assertFalse(DesktopStartWithWindows.isBackgroundLaunch(arrayOf("nuvio://x")))
    }

    @Test fun theCommandQuotesTheLauncher() {
        val exe = File("""C:\Program Files\Nuvio Z Debug\Nuvio Z Debug.exe""")
        assertEquals("Nuvio Z Debug", DesktopStartWithWindows.valueName(exe))
        assertEquals("\"${exe.absolutePath}\" --background", DesktopStartWithWindows.command(exe))
    }

    /** The real `reg.exe`, on a key of the test's own - never the `Run` key. */
    @Test fun aQuotedCommandSurvivesTheRegistryRoundTrip() {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        val registry = DesktopStartWithWindows.Registry("""HKCU\Software\NuvioZTest-StartWithWindows-${ProcessHandle.current().pid()}""")
        try {
            val command = DesktopStartWithWindows.command(File("""C:\Program Files\Nuvio Z Debug\Nuvio Z Debug.exe"""))
            assertNull(registry.read("Nuvio Z Debug"))
            assertTrue(registry.write("Nuvio Z Debug", command))
            assertEquals(command, registry.read("Nuvio Z Debug"))
            assertTrue(registry.delete("Nuvio Z Debug"))
            assertNull(registry.read("Nuvio Z Debug"))
            assertTrue(registry.delete("Nuvio Z Debug"), "deleting what is not there is not a failure")
        } finally {
            registry.deleteKey()
        }
    }
}
