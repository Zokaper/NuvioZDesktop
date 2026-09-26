package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Assisted "choose when ready" end to end, on the real flow controller and the real download store
 * (Phase 9, decided 2026-09-25): a season's sources are found in the background, the finding sheet
 * can be closed without cancelling anything, the user is told once - in the app when it is on
 * screen, by the system when it is not - and the quality sheet then has the real totals.
 */
class AssistedChoiceFlowTest {
    private val gb = 1_000_000_000L
    private val addon = AddonSourceKey("a", "https://a/manifest.json")

    private val show = MetaDetails(
        id = META_ID,
        type = "series",
        name = "Lanterns",
        videos = (1..EPISODES).map { MetaVideo(id = "$META_ID:1:$it", title = "E$it", season = 1, episode = it, released = "2001-01-01") },
    )

    private val notices = object : DownloadFlowNotices {
        val log = mutableListOf<String>()
        override fun findingSource() { log += "finding" }
        override fun started(item: DownloadItem?, height: Int?, bytes: Long?, onChange: (() -> Unit)?) { log += "started" }
        override fun startedMany(count: Int, needAttention: Int) { log += "many:$count" }
        override fun needsAttention() { log += "attention" }
        override fun nothingNew() { log += "nothing" }
        override fun qualityReady(title: String, season: Int?, onChoose: () -> Unit) { log += "ready:$title:$season" }
    }
    private val systemNotices = mutableListOf<DownloadChoiceNotice>()
    private var foreground = true
    private val discoveries = AtomicInteger()
    private var gate = CompletableDeferred<Unit>()
    private var found: (DownloadTarget) -> List<DownloadSourceCandidate> = { target ->
        listOf(
            candidate("${target.videoId}-1080", VideoResolution.FULL_HD_1080, 2 * gb),
            candidate("${target.videoId}-720", VideoResolution.HD_720, 1 * gb),
        )
    }

    private val defaults = object {
        val mode = DownloadFlowController.modeProvider
        val policy = DownloadFlowController.policyProvider
        val metered = DownloadFlowController.isMeteredNow
        val notices = DownloadFlowController.notices
        val foreground = DownloadFlowController.isAppInForeground
        val post = DownloadFlowController.postChoiceNotification
        val running = AssistedDiscovery.onRunningChanged
        val repoMetered = DownloadsRepository.isMeteredNetwork
    }

    private fun candidate(name: String, resolution: VideoResolution, size: Long): DownloadSourceCandidate {
        val url = "https://a/$name.mkv"
        return DownloadSourceCandidate(
            stream = StreamItem(name = name, url = url, addonName = "a", addonId = "a"),
            addonKey = addon,
            facts = SourceFacts(resolution = resolution, sizeBytes = size, reportedSizes = listOf(size)),
            resolvedUrl = url,
        )
    }

    private fun context(policy: DownloadPolicy) = DownloadSourceSelector.Context(
        policy = policy,
        runtimeMinutes = 60,
        isEpisode = true,
        rankingPreferences = SourceRankingPreferences(),
        playbackDynamicRange = DynamicRangePolicy.ANY,
        addonFilter = DownloadSourcePolicy(),
    )

    @BeforeTest
    fun setUp() {
        val storageRoot = DesktopStorage.rootDir.toAbsolutePath().toString()
        assertTrue(storageRoot.contains("build") || storageRoot.contains("test"), "not the real app data: $storageRoot")
        cleanUp()
        DownloadFlowController.resetForTests(Dispatchers.Default)
        DownloadFlowController.modeProvider = { DownloadMode.ASSISTED }
        DownloadFlowController.policyProvider = { DownloadPolicy() }
        DownloadFlowController.isMeteredNow = { false }
        DownloadFlowController.notices = notices
        DownloadFlowController.isAppInForeground = { foreground }
        DownloadFlowController.postChoiceNotification = { systemNotices += it }
        AssistedDiscovery.onRunningChanged = {}
        // Queued episodes wait for Wi-Fi rather than reaching for the fake URLs.
        DownloadsRepository.isMeteredNetwork = { true }
        DownloadsRepository.updateDeviceSettings { DownloadDeviceSettings() }
        DownloadBatchCoordinator.contextOverride = { _, policy -> context(policy) }
        DownloadBatchCoordinator.discoverOverride = { target ->
            gate.await()
            discoveries.incrementAndGet()
            found(target)
        }
    }

    @AfterTest
    fun tearDown() {
        DownloadBatchCoordinator.discoverOverride = null
        DownloadBatchCoordinator.contextOverride = null
        DownloadFlowController.modeProvider = defaults.mode
        DownloadFlowController.policyProvider = defaults.policy
        DownloadFlowController.isMeteredNow = defaults.metered
        DownloadFlowController.notices = defaults.notices
        DownloadFlowController.isAppInForeground = defaults.foreground
        DownloadFlowController.postChoiceNotification = defaults.post
        AssistedDiscovery.onRunningChanged = defaults.running
        DownloadFlowController.resetForTests(Dispatchers.Default)
        cleanUp()
        DownloadsRepository.isMeteredNetwork = defaults.repoMetered
    }

    private fun cleanUp() {
        DownloadsRepository.deleteDownloadsForTitle(META_ID)
        DownloadsRepository.batches.value.filter { it.parentMetaId == META_ID }.forEach { DownloadsRepository.removeBatch(it.id) }
    }

    private fun batch(): DownloadBatch? = DownloadsRepository.batches.value.firstOrNull { it.parentMetaId == META_ID }

    private fun startSeason(): String {
        DownloadFlowController.request(show, DownloadScope.Season(1))
        val step = assertIs<DownloadFlowStep.FindingSources>(DownloadFlowController.step.value)
        return assertNotNull(step.batchId, "a season finds its sources in the background")
    }

    private fun await(description: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25L)
        }
        fail("Timed out waiting for $description; step=${DownloadFlowController.step.value} batch=${batch()} notices=${notices.log}")
    }

    @Test
    fun closingTheFindingSheetDoesNotCancelDiscoveryAndTheAppPromptsWhenReady() {
        val batchId = startSeason()
        val preparing = assertNotNull(batch())
        assertTrue(preparing.awaitsQualityChoice)
        assertEquals(EPISODES, preparing.entries.size)
        assertTrue(preparing.entries.all { it.state == DownloadBatchEntryState.DISCOVERING })

        DownloadFlowController.dismiss() // "Continue in background"
        assertEquals(DownloadFlowStep.Idle, DownloadFlowController.step.value)
        assertTrue(AssistedDiscovery.isRunning(batchId), "closing the sheet cancelled discovery")

        gate.complete(Unit)
        await("the in-app prompt") { notices.log.any { it.startsWith("ready:") } }

        assertEquals(listOf("ready:Lanterns:1"), notices.log, "one in-app prompt, nothing else")
        assertTrue(systemNotices.isEmpty(), "no system notification while Nuvio is on screen")
        val ready = assertNotNull(batch())
        assertTrue(ready.isAwaitingQualityChoice)
        assertNotNull(ready.choiceAnnouncedAtEpochMs)
        assertTrue(
            AttentionGrouping.group(DownloadsRepository.uiState.value.items, DownloadsRepository.batches.value, 0L)
                .none { it.parentMetaId == META_ID },
            "ready to choose is not Needs you",
        )
    }

    @Test
    fun inTheBackgroundTheSystemNotificationSaysItInstead() {
        foreground = false
        val batchId = startSeason()
        DownloadFlowController.dismiss()
        gate.complete(Unit)
        await("the system notification") { systemNotices.isNotEmpty() }

        assertEquals(listOf(DownloadChoiceNotice(batchId, "Lanterns", 1, ready = true)), systemNotices)
        assertTrue(notices.log.none { it.startsWith("ready:") }, "no in-app prompt for a user who is not there")
    }

    @Test
    fun withTheSheetStillOpenItMovesStraightToTheChoiceWithRealTotals() {
        startSeason()
        gate.complete(Unit)
        await("the quality sheet") { DownloadFlowController.step.value is DownloadFlowStep.ChooseResolution }

        val sheet = DownloadFlowController.step.value as DownloadFlowStep.ChooseResolution
        assertEquals(listOf(1080, 720), sheet.rows.map { it.height })
        assertEquals(EPISODES * 2 * gb, sheet.rows.first { it.height == 1080 }.totalBytes)
        assertEquals(EPISODES, sheet.targetCount)
        assertTrue(notices.log.isEmpty() && systemNotices.isEmpty(), "the user is looking at it: nothing else to say")
    }

    @Test
    fun choosingAQualityQueuesTheSeasonIntoTheSameBatch() {
        val batchId = startSeason()
        DownloadFlowController.dismiss()
        gate.complete(Unit)
        await("ready") { batch()?.isAwaitingQualityChoice == true }

        DownloadFlowController.chooseQuality(batchId)
        assertIs<DownloadFlowStep.ChooseResolution>(DownloadFlowController.step.value)
        DownloadFlowController.chooseResolution(720)
        await("the episodes to be queued") {
            DownloadsRepository.uiState.value.items.count { it.parentMetaId == META_ID } == EPISODES
        }

        val chosen = assertNotNull(batch())
        assertEquals(batchId, chosen.id, "the choice lands in the batch the user was shown")
        assertFalse(chosen.awaitsQualityChoice)
        assertTrue(DownloadsRepository.uiState.value.items.filter { it.parentMetaId == META_ID }.all { it.expectedSizeBytes == 1 * gb })
        assertNull(AssistedDiscovery.candidates(batchId), "the candidates are dropped once used")
    }

    @Test
    fun afterAProcessDeathTheSourcesAreFoundAgainWithoutASecondPrompt() {
        val batchId = startSeason()
        DownloadFlowController.dismiss()
        gate.complete(Unit)
        await("the first prompt") { notices.log.any { it.startsWith("ready:") } }
        val discoveredOnce = discoveries.get()

        // The process dies: the batch is on disk, the candidates were only in memory.
        AssistedDiscovery.resetForTests(Dispatchers.Default)
        assertNull(AssistedDiscovery.candidates(batchId))
        AssistedDiscovery.resumeInterrupted(DownloadsRepository.batches.value)
        await("the refresh to finish") { AssistedDiscovery.candidates(batchId) != null && batch()?.isAwaitingQualityChoice == true }

        assertEquals(discoveredOnce * 2, discoveries.get(), "every episode was looked up again")
        assertEquals(1, notices.log.count { it.startsWith("ready:") }, "said once; the row says it after that")
        DownloadFlowController.chooseQuality(batchId)
        assertIs<DownloadFlowStep.ChooseResolution>(DownloadFlowController.step.value)
    }

    @Test
    fun choosingWhileSourcesAreStillBeingFoundShowsTheProgressAndThenTheChoice() {
        val batchId = startSeason()
        DownloadFlowController.dismiss()
        DownloadFlowController.chooseQuality(batchId) // tapping the "Finding sources" row
        val finding = assertIs<DownloadFlowStep.FindingSources>(DownloadFlowController.step.value)
        assertEquals(batchId, finding.batchId)
        gate.complete(Unit)
        await("the quality sheet") { DownloadFlowController.step.value is DownloadFlowStep.ChooseResolution }
    }

    @Test
    fun nothingToDownloadBecomesNeedsYouAndNotReady() {
        found = { emptyList() }
        startSeason()
        DownloadFlowController.dismiss()
        gate.complete(Unit)
        await("the batch to settle") { batch()?.awaitsQualityChoice == false }

        val settled = assertNotNull(batch())
        assertTrue(settled.entries.all { it.decision == DownloadEntryDecisionKind.NO_SOURCES })
        assertEquals(listOf("attention"), notices.log)
        assertTrue(
            AttentionGrouping.group(emptyList(), DownloadsRepository.batches.value, 0L).any { it.parentMetaId == META_ID },
            "nothing found is a real problem - Needs you",
        )
    }

    @Test
    fun removingTheBatchStopsTheSearch() {
        val batchId = startSeason()
        DownloadFlowController.dismiss()
        DownloadFlowController.removeChoiceBatch(batchId)
        assertFalse(AssistedDiscovery.isRunning(batchId))
        assertNull(batch())
        gate.complete(Unit)
        Thread.sleep(300L)
        assertTrue(notices.log.isEmpty() && systemNotices.isEmpty(), "a removed batch never announces itself")
    }

    private companion object {
        const val META_ID = "tt-assisted-choice"
        const val EPISODES = 4
    }
}
