package com.nuvio.app.features.updater

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WindowsUpdateInstallTest {

    private val launcher = "C:\\Program Files\\Nuvio Z\\Nuvio Z.exe"

    @Test
    fun `jpackage launcher parent is waited on as well as the jvm`() {
        assertEquals(
            listOf(200L, 100L),
            windowsUpdateWaitPids(
                currentPid = 200L,
                parentPid = 100L,
                parentCommand = launcher,
                launcherPath = launcher,
            ),
        )
    }

    @Test
    fun `unrelated parent is not waited on`() {
        assertEquals(
            listOf(200L),
            windowsUpdateWaitPids(
                currentPid = 200L,
                parentPid = 100L,
                parentCommand = "C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe",
                launcherPath = launcher,
            ),
        )
    }

    @Test
    fun `run outside a jpackage install waits only on itself`() {
        assertEquals(
            listOf(200L),
            windowsUpdateWaitPids(
                currentPid = 200L,
                parentPid = 100L,
                parentCommand = "C:\\Windows\\explorer.exe",
                launcherPath = null,
            ),
        )
    }

    @Test
    fun `custom install folder is kept on upgrade`() {
        assertEquals("D:\\Games\\Nuvio Z", windowsInstallDirFor("D:\\Games\\Nuvio Z\\Nuvio Z.exe"))
    }

    @Test
    fun `no install folder is forced outside a jpackage install`() {
        assertNull(windowsInstallDirFor(null))
        assertNull(windowsInstallDirFor(""))
    }

    @Test
    fun `drive root install folder is left to the msi`() {
        assertNull(windowsInstallDirFor("D:\\Nuvio Z.exe"))
    }

    @Test
    fun `helper inputs travel in the environment with no quoting`() {
        val environment = windowsMsiUpdateEnvironment(
            msiPath = "C:\\Users\\A B\\AppData\\Roaming\\Nuvio Z\\updates\\Nuvio-Z.msi",
            logPath = "C:\\Users\\A B\\AppData\\Roaming\\Nuvio Z\\updates\\Nuvio-Z-install.log",
            waitPids = listOf(200L, 100L),
            relaunchPath = null,
            installDir = "D:\\Games\\Nuvio Z",
            title = "Nuvio Z",
            failedMessage = "Unable to start installation",
        )

        assertEquals("C:\\Users\\A B\\AppData\\Roaming\\Nuvio Z\\updates\\Nuvio-Z.msi", environment["NUVIO_UPDATE_MSI"])
        assertEquals("200,100", environment["NUVIO_UPDATE_WAIT_PIDS"])
        assertEquals("", environment["NUVIO_UPDATE_RELAUNCH"])
        assertEquals("D:\\Games\\Nuvio Z", environment["NUVIO_UPDATE_INSTALL_DIR"])
    }

    @Test
    fun `msi helper is started through the console-less script host`() {
        val command = windowsMsiUpdateCommand(File("run-hidden.js"), File("windows-msi-update.ps1"))

        assertEquals("wscript.exe", command.first())
        assertEquals(File("windows-msi-update.ps1").absolutePath, command.last())
    }

    @Test
    fun `helper scripts ship as resources`() {
        listOf(windowsMsiUpdateScriptResource, windowsHiddenLauncherResource).forEach { name ->
            assertNotNull(javaClass.getResource("/updater/$name"), name)
        }
    }
}
