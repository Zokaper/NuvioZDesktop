package com.nuvio.app.features.tray

import com.nuvio.app.features.tray.DesktopSingleInstance.InstanceMessage
import com.nuvio.app.features.tray.DesktopSingleInstance.Outcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The launch hand-off: one copy claims the channel's folder, a later launch passes its links to it and
 * leaves. In one JVM a second lock attempt reports "held", which is exactly what another process sees.
 */
class DesktopSingleInstanceTest {
    private val dir: Path = Files.createTempDirectory("nuvio-single-instance")
    private val claims = mutableListOf<DesktopSingleInstance.Primary>()

    @AfterTest
    fun tearDown() {
        claims.forEach { it.close() }
        dir.toFile().deleteRecursively()
    }

    private fun claim(onMessage: (InstanceMessage) -> Unit = {}, urls: List<String> = emptyList(), deadlineMs: Long = 3_000): Outcome =
        DesktopSingleInstance.acquireOrHandOff(dir, urls, onMessage, deadlineMs).also { outcome ->
            if (outcome is Outcome.Claimed) claims += outcome.primary
        }

    @Test
    fun theFirstLaunchClaimsAndASecondHandsItsLinksOver() {
        val received = CopyOnWriteArrayList<InstanceMessage>()
        assertIs<Outcome.Claimed>(claim(onMessage = { received += it }))

        val second = claim(urls = listOf("nuvio://meta/movie/tt1", "stremio://addon.example/manifest.json"))

        assertEquals(Outcome.HandedOff, second)
        assertEquals(listOf(InstanceMessage(listOf("nuvio://meta/movie/tt1", "stremio://addon.example/manifest.json"))), received)
    }

    @Test
    fun aPlainLaunchStillAsksTheRunningCopyToShowItself() {
        val received = CopyOnWriteArrayList<InstanceMessage>()
        claim(onMessage = { received += it })

        assertEquals(Outcome.HandedOff, claim())
        assertEquals(listOf(InstanceMessage(emptyList())), received)
    }

    @Test
    fun aLinkCarryingALineBreakIsNotForwarded() {
        val received = CopyOnWriteArrayList<InstanceMessage>()
        claim(onMessage = { received += it })

        claim(urls = listOf("nuvio://ok", "nuvio://bad\nurl injected"))

        assertEquals(listOf(InstanceMessage(listOf("nuvio://ok"))), received)
    }

    @Test
    fun aHeldLockThatNeverAnswersEndsWithNoAnswer() {
        val received = CopyOnWriteArrayList<InstanceMessage>()
        claim(onMessage = { received += it })
        // The address file now points at a token the listener will not accept.
        val info = dir.resolve("instance.port")
        val lines = Files.readAllLines(info)
        Files.write(info, listOf(lines[0], "0".repeat(32), lines[2]))

        assertEquals(Outcome.NoAnswer, claim(deadlineMs = 700))
        assertTrue(received.isEmpty(), "a message with the wrong token was acted on")
    }

    @Test
    fun aLaunchWaitingOnAQuittingCopyTakesOverWhenItsLockFrees() {
        val first = assertIs<Outcome.Claimed>(claim())
        Files.write(dir.resolve("instance.port"), listOf("1", "x", "1"))
        val outcome = AtomicReference<Outcome>()
        val waiter = thread { outcome.set(claim(deadlineMs = 5_000)) }

        Thread.sleep(600)
        first.primary.close()
        waiter.join(TimeUnit.SECONDS.toMillis(6))

        assertIs<Outcome.Claimed>(outcome.get())
    }

    @Test
    fun aFreedClaimCanBeTakenAgain() {
        val first = assertIs<Outcome.Claimed>(claim())
        first.primary.close()

        assertIs<Outcome.Claimed>(claim())
    }
}
