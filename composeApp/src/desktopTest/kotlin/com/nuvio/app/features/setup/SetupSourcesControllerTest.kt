package com.nuvio.app.features.setup

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupSourcesControllerTest {

    private val key = "tb_SECRET_key_0123456789abcdef"
    private val manifestUrl = "$Base/stremio/uuid-2/ENC/manifest.json"

    // --- recommended / manual choice -------------------------------------------------------

    @Test
    fun startsOnTheQuestionAndEitherAnswerOpensItsPath() {
        val controller = controller()
        assertEquals(SetupSourcesMode.Choice, controller.state.value.mode)

        controller.chooseRecommended()
        assertEquals(SetupSourcesMode.Recommended, controller.state.value.mode)

        controller.setTorBoxApiKey(key)
        controller.backToChoice()
        assertEquals(SetupSourcesMode.Choice, controller.state.value.mode)
        assertEquals("", controller.state.value.torBoxApiKey, "going back must drop a typed key")

        controller.chooseManual()
        assertEquals(SetupSourcesMode.Manual, controller.state.value.mode)
    }

    @Test
    fun languagesFollowTheLanguageStepUntilPickedHere() {
        val controller = controller()
        controller.seedLanguages("pt-BR", "none")
        assertEquals("pt", controller.state.value.sourceLanguageCode)
        assertEquals("pt", controller.state.value.subtitleLanguageCode, "an unmapped subtitle falls back to the source")

        controller.seedLanguages("device", "ja")
        assertEquals("en", controller.state.value.sourceLanguageCode)
        assertEquals("ja", controller.state.value.subtitleLanguageCode)

        controller.setSubtitleLanguage("fr")
        controller.seedLanguages("de", "de")
        assertEquals("fr", controller.state.value.subtitleLanguageCode, "a pick made here is not overwritten")

        controller.setSourceLanguage("xx")
        assertEquals("en", controller.state.value.sourceLanguageCode, "a code the template cannot express is ignored")
    }

    // --- successful native setup -----------------------------------------------------------

    @Test
    fun successInstallsTheManifestOnceAndClearsTheKey() = runBlocking {
        val requests = mutableListOf<RecommendedSourceRequest>()
        val installs = mutableListOf<String>()
        val remembered = mutableListOf<Pair<String, AioStreamsRecovery>>()
        val controller = controller(
            createConfig = { request -> requests += request; created() },
            install = { url -> installs += url; AddAddonResult.Success(manifest("Nuvio Z Recommended")) },
            rememberCredentials = { url, recovery -> remembered += url to recovery },
        )
        controller.seedLanguages("ja", "en")
        controller.chooseRecommended()
        controller.setTorBoxApiKey(key)

        controller.setUpRecommended()

        val state = controller.state.value
        assertEquals("Nuvio Z Recommended", state.configuredName)
        assertEquals("", state.torBoxApiKey)
        assertFalse(state.busy)
        assertNull(state.recommendedFailure)
        assertEquals(listOf(manifestUrl), installs)
        assertEquals(key, requests.single().torBoxApiKey.value)
        assertEquals("Japanese", requests.single().sourceLanguage)
        assertEquals("English", requests.single().subtitleLanguage)
        assertEquals("uuid-2", state.recovery?.uuid)
        // Kept past the step, so a later template change can be applied to this install.
        assertEquals(manifestUrl to state.recovery, remembered.single())

        controller.forgetSensitive()
        assertNull(controller.state.value.recovery, "recovery details must not outlive the step")
    }

    @Test
    fun keyIsGoneFromStateWhileTheRequestIsStillRunning() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val controller = controller(createConfig = { release.await(); created() })
        controller.setTorBoxApiKey(key)

        val job = async(Dispatchers.Default) { controller.setUpRecommended() }
        while (!controller.state.value.busy) yield()
        assertEquals("", controller.state.value.torBoxApiKey)
        release.complete(Unit)
        job.await()
    }

    // --- failed setup ------------------------------------------------------------------------

    @Test
    fun failureClearsTheKeyAndInstallsNothing() = runBlocking {
        var installed = false
        val controller = controller(
            createConfig = { AioStreamsConfigResult.Failed(RecommendedSourceFailure.Rejected, "Invalid TorBox key") },
            install = { installed = true; AddAddonResult.Success(manifest("x")) },
        )
        controller.chooseRecommended()
        controller.setTorBoxApiKey(key)

        controller.setUpRecommended()

        val state = controller.state.value
        assertEquals(RecommendedSourceFailure.Rejected, state.recommendedFailure)
        assertEquals("Invalid TorBox key", state.recommendedFailureDetail)
        assertEquals("", state.torBoxApiKey)
        assertFalse(state.busy)
        assertNull(state.configuredName)
        assertEquals(SetupSourcesMode.Recommended, state.mode, "the user stays on the form to try again")
        assertFalse(installed)

        controller.setTorBoxApiKey("x")
        assertNull(controller.state.value.recommendedFailure, "typing again clears the old error")
    }

    @Test
    fun unexpectedExceptionIsAFailureNotACrashOrAStuckSpinner() = runBlocking {
        val controller = controller(createConfig = { throw IllegalStateException("boom $key") })
        controller.setTorBoxApiKey(key)

        controller.setUpRecommended()

        val state = controller.state.value
        assertEquals(RecommendedSourceFailure.InstanceUnavailable, state.recommendedFailure)
        assertNull(state.recommendedFailureDetail)
        assertFalse(state.busy)
        assertEquals("", state.torBoxApiKey)
    }

    @Test
    fun installFailureAfterCreationIsReportedAndKeepsPreviousSources() = runBlocking {
        val removed = mutableListOf<String>()
        val previous = installed("$Base/stremio/uuid-1/OLD/manifest.json", "Nuvio Z Recommended")
        val controller = controller(
            createConfig = { created() },
            install = { AddAddonResult.Error("Failed to load https://aio.example/stremio/uuid-2/ENC/manifest.json") },
            remove = { removed += it },
            installedAddons = { listOf(previous) },
        )
        controller.setTorBoxApiKey(key)

        controller.setUpRecommended()

        assertEquals(RecommendedSourceFailure.InstallFailed, controller.state.value.recommendedFailure)
        assertEquals("Failed to load [link]", controller.state.value.recommendedFailureDetail)
        assertTrue(removed.isEmpty(), "a failed install must not remove the sources that still work")
    }

    // --- duplicate install prevention --------------------------------------------------------

    @Test
    fun doublePressCreatesOneConfig() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var creates = 0
        var installs = 0
        val controller = controller(
            createConfig = { creates++; release.await(); created() },
            install = { installs++; AddAddonResult.Success(manifest("Nuvio Z Recommended")) },
        )
        controller.setTorBoxApiKey(key)

        val first = async(Dispatchers.Default) { controller.setUpRecommended() }
        while (!controller.state.value.busy) yield()
        controller.setUpRecommended()
        controller.installManual("blank")
        release.complete(Unit)
        first.await()

        assertEquals(1, creates)
        assertEquals(1, installs)
    }

    @Test
    fun rerunReplacesOnlyEarlierRecommendedInstallsFromTheSameInstance() = runBlocking {
        val removed = mutableListOf<String>()
        val oldRecommended = installed("$Base/stremio/uuid-1/OLD/manifest.json", "Nuvio Z Recommended")
        val ownAio = installed("$Base/stremio/uuid-9/MINE/manifest.json", "My AIOStreams")
        val otherInstance = installed("https://other.example/stremio/uuid-3/X/manifest.json", "Nuvio Z Recommended")
        val controller = controller(
            createConfig = { created() },
            install = { AddAddonResult.Success(manifest("Nuvio Z Recommended")) },
            remove = { removed += it },
            installedAddons = { listOf(oldRecommended, ownAio, otherInstance) },
        )
        controller.setTorBoxApiKey(key)

        controller.setUpRecommended()

        assertEquals(listOf(oldRecommended.manifestUrl), removed)
    }

    @Test
    fun rerunReplacesInstallsUnderTheCurrentAndThePreRenameName() = runBlocking {
        val removed = mutableListOf<String>()
        val legacy = installed("$Base/stremio/uuid-1/OLD/manifest.json", "Nuvio Z Recommended")
        val renamed = installed("$Base/stremio/uuid-2/NEWER/manifest.json", NUVIO_Z_RECOMMENDED_ADDON_NAME)
        val controller = controller(
            createConfig = { created() },
            install = { AddAddonResult.Success(manifest(NUVIO_Z_RECOMMENDED_ADDON_NAME)) },
            remove = { removed += it },
            installedAddons = { listOf(legacy, renamed) },
        )
        controller.setTorBoxApiKey(key)

        controller.setUpRecommended()

        assertEquals(listOf(legacy.manifestUrl, renamed.manifestUrl), removed)
    }

    @Test
    fun alreadyInstalledManifestIsNotInstalledAgain() = runBlocking {
        var installs = 0
        val controller = controller(
            install = { installs++; AddAddonResult.Error("Addon is already installed") },
            installedAddons = { listOf(installed("https://addon.example/manifest.json", "Torrentio")) },
        )
        controller.chooseManual()
        controller.setManualUrl("https://addon.example/manifest.json")

        controller.installManual("blank")

        assertEquals(0, installs)
        assertEquals("Torrentio", controller.state.value.configuredName)
        assertNull(controller.state.value.manualError)
    }

    // --- manual path -------------------------------------------------------------------------

    @Test
    fun manualPathInstallsThroughTheAddonInstaller() = runBlocking {
        val installs = mutableListOf<String>()
        var creates = 0
        val controller = controller(
            createConfig = { creates++; created() },
            install = { url -> installs += url; AddAddonResult.Success(manifest("Torrentio")) },
        )
        controller.chooseManual()
        controller.setManualUrl("https://torrentio.example/manifest.json")

        controller.installManual("Enter a URL")

        assertEquals(listOf("https://torrentio.example/manifest.json"), installs)
        assertEquals("Torrentio", controller.state.value.configuredName)
        assertEquals("", controller.state.value.manualUrl)
        assertEquals(0, creates, "the manual path never talks to AIOStreams")
    }

    @Test
    fun manualPathReportsBlankAndInstallerErrors() = runBlocking {
        val controller = controller(install = { AddAddonResult.Error("Manifest could not be loaded") })
        controller.chooseManual()

        controller.installManual("Enter a URL")
        assertEquals("Enter a URL", controller.state.value.manualError)

        controller.setManualUrl("https://broken.example/manifest.json")
        assertNull(controller.state.value.manualError)
        controller.installManual("Enter a URL")
        assertEquals("Manifest could not be loaded", controller.state.value.manualError)
        assertNull(controller.state.value.configuredName)
        assertFalse(controller.state.value.busy)
    }

    // --- the key is never persisted or logged -----------------------------------------------

    @Test
    fun keyNeverReachesLogsStdoutOrStateDumps() = runBlocking {
        val logged = StringBuilder()
        val writer = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                logged.append(tag).append(' ').append(message).append(' ').append(throwable?.message).append('\n')
            }
        }
        val stdout = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        Logger.addLogWriter(writer)
        System.setOut(PrintStream(stdout, true))
        System.setErr(PrintStream(stdout, true))
        val dumps = StringBuilder()
        try {
            val failing = controller(createConfig = { AioStreamsConfigResult.Failed(RecommendedSourceFailure.Rejected) })
            failing.setTorBoxApiKey(key)
            dumps.append(failing.state.value)
            failing.setUpRecommended()
            dumps.append(failing.state.value)

            val throwing = controller(createConfig = { error("exploded with $key") })
            throwing.setTorBoxApiKey(key)
            throwing.setUpRecommended()
            dumps.append(throwing.state.value)

            val working = controller(
                createConfig = { request -> dumps.append(request); created() },
                install = { AddAddonResult.Success(manifest("Nuvio Z Recommended")) },
            )
            working.setTorBoxApiKey(key)
            working.setUpRecommended()
            dumps.append(working.state.value)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            Logger.setLogWriters(Logger.config.logWriterList - writer)
        }

        assertTrue(logged.contains("Recommended source setup failed"), "the capture saw nothing; the test would prove nothing")
        assertFalse(logged.contains(key), "Kermit output contained the TorBox key:\n$logged")
        assertFalse(stdout.toString().contains(key), "stdout/stderr contained the TorBox key")
        assertFalse(dumps.contains(key), "a toString contained the TorBox key:\n$dumps")
    }

    @Test
    fun stateHoldsNoSecretOnceSetupEnds() = runBlocking {
        // The only persistence the wizard does for this step is the saved mode name; everything else
        // lives in this state. So "not persisted" reduces to: nothing in any field once setup ends.
        val controller = controller(createConfig = { created() }, install = { AddAddonResult.Success(manifest("Nuvio Z Recommended")) })
        controller.setTorBoxApiKey(key)
        controller.setUpRecommended()
        val state = controller.state.value
        val fields = listOf(
            state.mode.name, state.torBoxApiKey, state.sourceLanguageCode, state.subtitleLanguageCode,
            state.recommendedFailureDetail, state.configuredName, state.manualUrl, state.manualError,
            state.recovery?.uuid, state.recovery?.password, state.recovery?.configureUrl,
        )
        assertTrue(fields.none { it?.contains(key) == true })
        assertFalse(manifestUrl.contains(key), "only the manifest URL is stored, and it carries no key")
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun controller(
        createConfig: suspend (RecommendedSourceRequest) -> AioStreamsConfigResult = { created() },
        install: suspend (String) -> AddAddonResult = { AddAddonResult.Success(manifest("Nuvio Z Recommended")) },
        remove: (String) -> Unit = {},
        installedAddons: () -> List<ManagedAddon> = { emptyList() },
        rememberCredentials: (String, AioStreamsRecovery) -> Unit = { _, _ -> },
    ) = SetupSourcesController(
        createConfig = createConfig,
        install = install,
        remove = remove,
        installedAddons = installedAddons,
        rememberCredentials = rememberCredentials,
        instanceBaseUrl = Base,
    )

    private fun created() = AioStreamsConfigResult.Created(
        manifestUrl = manifestUrl,
        addonName = "Nuvio Z Recommended",
        recovery = AioStreamsRecovery("$Base/stremio/configure", "uuid-2", "p".repeat(64)),
    )

    private fun installed(url: String, name: String) =
        ManagedAddon(manifestUrl = url, manifest = manifest(name), enabled = true)

    private fun manifest(name: String) = AddonManifest(
        id = "test.$name",
        name = name,
        description = "",
        version = "1.0.0",
        resources = listOf(AddonResource(name = "stream", types = listOf("movie"))),
        types = listOf("movie"),
        transportUrl = manifestUrl,
    )

    private companion object {
        const val Base = "https://aio.example"
    }
}
