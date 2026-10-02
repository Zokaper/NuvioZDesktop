package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shipped global changelog, as desktop reads it. The release guard itself runs in
 * `desktop-release.yml` (`scripts/check-changelog.py`): desktop's current serial is a published
 * release older than the changelog, so "this serial ships an event" would be red until the next bump.
 */
class DesktopChangelogTest {
    private val text = File("src/commonMain/composeResources/files/changelog.json").readText()
    private val events = ChangelogCatalog.parse(text)

    @Test
    fun theFileIsSoundAndParsesWhole() {
        assertEquals(emptyList(), validateChangelog(events))
        assertEquals(Regex("\"category\"\\s*:").findAll(text).count(), events.sumOf { it.entries.size })
    }

    @Test
    fun desktopHasAnEventAtOrAfterThisSerial() {
        assertTrue(events.any { (it.ships[ChangelogPlatform.DESKTOP]?.serial ?: 0) >= AppVersionConfig.RELEASE_SERIAL })
    }

    @Test
    fun theUpcomingDesktopEventStaysHiddenFromThisStableSerial() {
        val upcoming = events.filter { (it.ships[ChangelogPlatform.DESKTOP]?.serial ?: 0) > AppVersionConfig.RELEASE_SERIAL }
        val stable = ChangelogViewer(ChangelogPlatform.DESKTOP, AppVersionConfig.RELEASE_SERIAL)
        upcoming.forEach { event -> assertTrue(!event.isEligibleFor(stable), "seq ${event.seq} leaked to a stable build") }
    }

    @Test
    fun theDebugNotesAreDesktops() {
        val debug = File("src/commonMain/composeResources/files/changelog-debug.json").readText()
        val notes = ChangelogCatalog.parseDebug(debug, "desktop")
        assertTrue(notes.isNotEmpty())
        assertEquals(notes.size, notes.map { it.build }.toSet().size)
    }

    @Test
    fun theDesktopIdentityIsDesktops() {
        val identity = WhatsNewStorage.releaseIdentity
        assertEquals("desktop", identity.family)
        assertEquals(ChangelogPlatform.DESKTOP, identity.platform)
        assertEquals(AppVersionConfig.RELEASE_SERIAL, identity.serial)
        assertTrue(AppVersionConfig.DESKTOP_VERSION_NAME.startsWith(identity.versionName))
        assertNotNull(identity.viewer)
    }
}
