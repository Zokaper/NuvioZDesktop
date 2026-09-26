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
        videos = (1..EPISODES).map { MetaVideo(id = "$META_ID:1:$it", title = "E$it", season = 1, episode = it, released = "2001-01-01", runtime = 45) },
    )

    private val notices = object : DownloadFlowNotices {
        val log = mutableListOf<String>()
        override fun findingSource() { log += "finding" }
        override fun started(item: DownloadItem?, height: Int?, bytes: Long?, onChange: (() -> Unit)?) { log += "started" }
        override fun startedMany(count: Int, needAttention: Int) { log += "many:$count" }
        override fun needsAttention() { log += "attention" }
        override fun nothingNew() { log += "nothing" }
        override fun qualityReady(title: String, season: Int?, onChoose: () -> Unit) { log += "ready:$title:$season" }
        override fun qualityChosenEarly(height: Int) { log += "early:$height" }
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
        // "Choose now" decides as Automatic does, which HEAD-checks a direct source's size.
        DownloadBatchCoordinator.verifySizeOverride = { it }
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
        DownloadBatchCoordinator.verifySizeOverride = null
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

    // --- "Choose now": a quality from estimates while the sources are still being found --------

    private fun items() = DownloadsRepository.uiState.value.items.filter { it.parentMetaId == META_ID }

    private fun chooseEarly(height: Int): String {
        val batchId = startSeason()
        DownloadFlowController.chooseNow()
        val sheet = assertIs<DownloadFlowStep.ChooseResolution>(DownloadFlowController.step.value)
        assertTrue(sheet.estimated)
        DownloadFlowController.chooseResolution(height)
        return batchId
    }

    @Test
    fun chooseNowShowsEstimatesAndRecordsTheChoiceWithoutStartingAnything() {
        val batchId = startSeason()
        DownloadFlowController.chooseNow()
        val sheet = assertIs<DownloadFlowStep.ChooseResolution>(DownloadFlowController.step.value)
        assertTrue(sheet.estimated)
        assertEquals(listOf(2160, 1080, 720), sheet.rows.map { it.height })
        assertEquals(1080, sheet.preselectedHeight)
        // 4 episodes x 45 min = 3 h at 1080p Medium (1-2 GB/h), not a single exact figure.
        assertEquals(3 * gb..6 * gb, sheet.rows.first { it.height == 1080 }.estimate)
        assertTrue(sheet.rows.all { it.totalBytes == 0L }, "no row pretends to know the real size")

        DownloadFlowController.chooseResolution(1080)
        assertEquals(DownloadFlowStep.Idle, DownloadFlowController.step.value)
        val chosen = assertNotNull(batch())
        assertEquals(1080, chosen.earlyResolutionHeight)
        assertTrue(chosen.awaitsQualityChoice)
        assertTrue(AssistedDiscovery.isRunning(batchId))
        assertTrue(items().isEmpty(), "nothing starts before its source is found")
        assertEquals(listOf("early:1080"), notices.log)
    }

    /**
     * Physical `.56` (iOS): after Choose now the screen was empty while the season was resolved.
     * The claim cleared the batch's flag before the sizes were checked, one network check per
     * episode, and for that whole pass the batch belonged to no row. Held open here.
     */
    @Test
    fun whileTheSourcesFoundAreCheckedTheBatchKeepsItsRow() {
        val checks = CompletableDeferred<Unit>()
        val checking = AtomicInteger()
        DownloadBatchCoordinator.verifySizeOverride = { candidate ->
            checking.incrementAndGet()
            checks.await()
            candidate
        }
        val batchId = chooseEarly(1080)
        gate.complete(Unit)
        await("the size checks to start") { checking.get() > 0 }

        val held = assertNotNull(DownloadsRepository.batches.value.firstOrNull { it.id == batchId })
        assertFalse(held.awaitsQualityChoice)
        assertTrue(held.showsAsChoiceRow, "the row the Downloads screen draws: ${held.entries.map { it.state }}")
        assertTrue(held.isPreparing, "what the Live Activity and the Android summary read")
        val status = assertNotNull(held.choiceStatus(refreshing = false))
        assertEquals(DownloadChoicePhase.CHECKING, status.phase)
        assertEquals(1080, status.chosenHeight)
        assertEquals(EPISODES, status.total)
        assertTrue(items().isEmpty())

        checks.complete(Unit)
        await("the season to be queued") { items().size == EPISODES && notices.log.size == 2 }
        val done = assertNotNull(batch())
        assertFalse(done.showsAsChoiceRow, "the downloads carry it from here")
        assertEquals(listOf("early:1080", "many:$EPISODES"), notices.log)
    }

    @Test
    fun removingABatchWhileItsSourcesAreCheckedStartsNothing() {
        val checks = CompletableDeferred<Unit>()
        val checking = AtomicInteger()
        DownloadBatchCoordinator.verifySizeOverride = { candidate ->
            checking.incrementAndGet()
            checks.await()
            candidate
        }
        val batchId = chooseEarly(1080)
        gate.complete(Unit)
        await("the size checks to start") { checking.get() > 0 }
        DownloadFlowController.removeChoiceBatch(batchId)
        checks.complete(Unit)
        Thread.sleep(300L)
        assertNull(batch())
        assertTrue(items().isEmpty(), "a removed batch never queues")
    }

    @Test
    fun noLimitMeansNoEstimateRatherThanAMadeUpOne() {
        DownloadFlowController.policyProvider = { DownloadPolicy(sizeLevel = DownloadSizeLevel.ANY) }
        startSeason()
        DownloadFlowController.chooseNow()
        val sheet = assertIs<DownloadFlowStep.ChooseResolution>(DownloadFlowController.step.value)
        assertTrue(sheet.rows.all { it.estimate == null })
    }

    @Test
    fun whenTheSourcesAreInTheEarlyChoiceStartsWithoutAskingAgain() {
        val batchId = chooseEarly(1080)
        gate.complete(Unit)
        await("the season to be queued") { items().size == EPISODES }

        assertTrue(items().all { it.expectedSizeBytes == 2 * gb }, "the 1080p sources, at their real sizes")
        val settled = assertNotNull(batch())
        assertEquals(batchId, settled.id)
        assertFalse(settled.awaitsQualityChoice)
        assertTrue(notices.log.none { it.startsWith("ready:") }, "never asked for the same quality twice")
        assertTrue(systemNotices.isEmpty())
        assertEquals(listOf("early:1080", "many:$EPISODES"), notices.log)
        assertNull(AssistedDiscovery.candidates(batchId))
    }

    @Test
    fun theSheetOpenWhenDiscoveryEndsClosesInsteadOfOfferingTheChoiceAgain() {
        val batchId = chooseEarly(720)
        DownloadFlowController.chooseQuality(batchId) // tapping the row: progress, and what was chosen
        val finding = assertIs<DownloadFlowStep.FindingSources>(DownloadFlowController.step.value)
        assertEquals(720, finding.chosenHeight)
        gate.complete(Unit)
        await("the season to be queued") { items().size == EPISODES }
        assertEquals(DownloadFlowStep.Idle, DownloadFlowController.step.value)
        assertTrue(items().all { it.expectedSizeBytes == 1 * gb })
    }

    @Test
    fun aMissingResolutionFollowsTheFallbackAndAskIsTheGroupedDecision() {
        chooseEarly(2160) // nobody has 4K; the default fallback is Ask
        gate.complete(Unit)
        await("the batch to settle") { batch()?.awaitsQualityChoice == false && batch()?.entries?.none { it.state == DownloadBatchEntryState.AWAITING_CHOICE } == true }

        assertTrue(items().isEmpty())
        assertTrue(assertNotNull(batch()).entries.all { it.decision == DownloadEntryDecisionKind.RESOLUTION_MISSING })
        assertTrue(
            AttentionGrouping.group(emptyList(), DownloadsRepository.batches.value, 0L).any { it.parentMetaId == META_ID },
            "one Needs you card with Use nearest",
        )
    }

    @Test
    fun aMissingResolutionWithFallbackLowerStartsTheNearestBelow() {
        DownloadFlowController.policyProvider = { DownloadPolicy(resolutionFallback = DownloadResolutionFallback.LOWER) }
        chooseEarly(2160)
        gate.complete(Unit)
        await("the season to be queued") { items().size == EPISODES }
        assertTrue(items().all { it.expectedSizeBytes == 2 * gb }, "1080p, the nearest below 4K")
    }

    @Test
    fun realSizesOverTheSizeRuleBecomeTheOverLimitDecision() {
        // Small at 1080p for an hour is 1 GB; every 1080p source here is 2 GB.
        DownloadFlowController.policyProvider = { DownloadPolicy(sizeLevel = DownloadSizeLevel.SMALL) }
        chooseEarly(1080)
        gate.complete(Unit)
        await("the batch to settle") { batch()?.awaitsQualityChoice == false && batch()?.entries?.none { it.state == DownloadBatchEntryState.AWAITING_CHOICE } == true }

        assertTrue(items().isEmpty(), "the estimate was not a promise: nothing over the rule starts")
        assertTrue(assertNotNull(batch()).entries.all { it.decision == DownloadEntryDecisionKind.OVER_LIMIT })
    }

    @Test
    fun anEarlyChoiceSurvivesAProcessDeath() {
        val batchId = chooseEarly(720)
        // The process dies before the sources are in: the batch and its choice are on disk.
        AssistedDiscovery.resetForTests(Dispatchers.Default)
        gate.complete(Unit)
        assertEquals(720, assertNotNull(batch()).earlyResolutionHeight)
        AssistedDiscovery.resumeInterrupted(DownloadsRepository.batches.value)
        await("the season to be queued") { items().size == EPISODES }

        assertTrue(items().all { it.expectedSizeBytes == 1 * gb })
        assertTrue(notices.log.none { it.startsWith("ready:") })
        assertTrue(systemNotices.isEmpty())
        assertNull(AssistedDiscovery.candidates(batchId))
    }

    @Test
    fun theEstimateSheetBecomesTheExactOneWhenTheSourcesArrive() {
        startSeason()
        DownloadFlowController.chooseNow()
        gate.complete(Unit)
        await("the exact sheet") {
            (DownloadFlowController.step.value as? DownloadFlowStep.ChooseResolution)?.estimated == false
        }
        val exact = DownloadFlowController.step.value as DownloadFlowStep.ChooseResolution
        assertEquals(EPISODES * 2 * gb, exact.rows.first { it.height == 1080 }.totalBytes)
        assertTrue(notices.log.isEmpty() && systemNotices.isEmpty(), "the user is looking at it: nothing else to say")
        DownloadFlowController.chooseResolution(1080)
        await("the season to be queued") { items().size == EPISODES }
    }

    private companion object {
        const val META_ID = "tt-assisted-choice"
        const val EPISODES = 4
    }
}
