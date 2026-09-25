package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped changelog, as desktop reads it. The release guard itself runs in
 * `desktop-release.yml` (`scripts/check-changelog.py`): desktop's current serial is a published
 * release older than the changelog, so "this serial has notes" would be red until the next bump.
 */
class DesktopChangelogTest {
    private val releases = ChangelogCatalog.parse(File("src/commonMain/composeResources/files/changelog.json").readText())

    @Test
    fun desktopHasANextReleaseWithDesktopEntriesOnly() {
        val desktop = releases.filter { it.family == "desktop" }
        assertTrue(desktop.isNotEmpty())
        assertTrue(desktop.maxOf { it.serial } >= AppVersionConfig.RELEASE_SERIAL)
        desktop.flatMap { it.entries }.forEach { entry ->
            assertEquals(setOf(ChangelogPlatform.DESKTOP), entry.platforms, entry.title)
        }
    }

    @Test
    fun theDesktopIdentityIsDesktops() {
        val identity = WhatsNewStorage.releaseIdentity
        assertEquals("desktop", identity.family)
        assertEquals(ChangelogPlatform.DESKTOP, identity.platform)
        assertEquals(AppVersionConfig.RELEASE_SERIAL, identity.serial)
        assertTrue(AppVersionConfig.DESKTOP_VERSION_NAME.startsWith(identity.versionName))
    }
}
