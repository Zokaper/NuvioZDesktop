package com.nuvio.app.core.debug.selftest

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.streams.StreamItem
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** Thrown by [CheckScope.skip]; caught by [SelfTestContext.check] and recorded as SKIP. */
internal class SelfTestSkip(val reason: String) : RuntimeException(reason)

/** Thrown by [CheckScope.fail]; caught by [SelfTestContext.check] and recorded as FAIL. */
internal class SelfTestFailure(val detail: String) : RuntimeException(detail)

/**
 * What one check can say about itself.
 *
 * Values accumulate whether the check passes or fails, and are written either way. That is the
 * point of them: a failure that reports only *"first frame never arrived"* sends the reader back
 * to the machine, whereas the same failure carrying the resolved host, the decoder, the cache
 * readings and the last five positions usually explains itself.
 */
internal class CheckScope {
    internal val values = LinkedHashMap<String, String>()
    internal val screenshots = mutableListOf<String>()

    /**
     * The one-line detail for a check that succeeds.
     *
     * Set it to the finding rather than leaving the default: *"first frame in 2.4s, hwdec d3d11va,
     * 0 dropped"* is the sentence that belongs in a summary table, and "Passed." is the sentence
     * that makes thirty rows of a report indistinguishable from each other.
     */
    var summary: String? = null

    /** Records an observation. Runs through [SelfTestRedaction.text] on the way in - always. */
    fun value(name: String, value: String) {
        values[name] = SelfTestRedaction.text(value)
    }

    fun value(name: String, value: Number) {
        values[name] = value.toString()
    }

    fun value(name: String, value: Boolean) {
        values[name] = value.toString()
    }

    /**
     * Records an already-redacted value verbatim.
     *
     * For output of [SelfTestRedaction.streamUrl] and friends, which would otherwise be re-mangled
     * by the free-text pass - the hash suffix looks enough like an opaque token to be eaten.
     */
    fun redactedValue(name: String, value: String) {
        values[name] = value
    }

    fun screenshot(paths: List<String?>) {
        paths.filterNotNull().forEach(screenshots::add)
    }

    fun screenshot(path: String?) {
        path?.let(screenshots::add)
    }

    /** Ends the check as not-run. **Never counted as a pass** - see [CheckResult]. */
    fun skip(reason: String): Nothing = throw SelfTestSkip(reason)

    /** Ends the check as failed. */
    fun fail(detail: String): Nothing = throw SelfTestFailure(detail)

    /** Asserts, with the failure text written by the caller so the report reads as prose. */
    fun require(condition: Boolean, detail: () -> String) {
        if (!condition) fail(detail())
    }
}

/**
 * State shared across the suites, and the recorder they report into.
 *
 * The suites are ordered and **not independent**: S1 finds sources, S2 resolves one of them into a
 * playable link, and S3 plays that link. Threading the results through a context rather than
 * re-deriving them per suite is what keeps the run to a few minutes instead of repeating the whole
 * addon fan-out three times - and it means S3's failure is about playback, not about a source
 * search that happened to answer differently the second time.
 *
 * The consequence is that a suite must handle its input being absent, because the suite before it
 * may have skipped. Every one of them does so by skipping with the reason, not by throwing.
 */
internal class SelfTestContext(
    val runDir: Path,
    val capture: SelfTestCapture,
    val fixtures: SelfTestFixtures,
    private val onProgress: (String) -> Unit,
) {
    private val log = Logger.withTag("SelfTest")

    val results = mutableListOf<CheckResult>()
    val environment = LinkedHashMap<String, String>()

    // --- Threaded between suites -------------------------------------------------------------

    var filmMeta: MetaDetails? = null
    var seriesMeta: MetaDetails? = null

    /** The episode id taken from the series' own `videos` array, not synthesised. */
    var episodeVideoId: String? = null

    var filmStreams: List<StreamItem> = emptyList()
    var episodeStreams: List<StreamItem> = emptyList()

    /** The stream S2 minted into something playable, and S3 plays. */
    var playableStream: StreamItem? = null

    /** Set when the environment check finds no usable addon; later suites skip on it by name. */
    var addonsConfigured: Boolean = false
    var debridConfigured: Boolean = false

    fun progress(message: String) {
        log.i { message }
        onProgress(message)
    }

    /**
     * Runs one check, times it, and records exactly one result whatever happens.
     *
     * An unexpected exception is a **failure**, not a crash: a harness that dies on check 4 of 30
     * and writes nothing is worse than no harness, because the run looks like it was never started.
     * The type and message are recorded so an infrastructure fault is still distinguishable from a
     * real one.
     */
    suspend fun check(id: String, name: String, block: suspend (CheckScope) -> Unit) {
        progress("$id $name")
        val scope = CheckScope()
        val started = System.currentTimeMillis()
        val result = try {
            block(scope)
            outcome(id, name, CheckResult.Status.PASS, started, scope, scope.summary ?: "Passed.")
        } catch (skip: SelfTestSkip) {
            outcome(id, name, CheckResult.Status.SKIP, started, scope, skip.reason)
        } catch (failure: SelfTestFailure) {
            outcome(id, name, CheckResult.Status.FAIL, started, scope, failure.detail)
        } catch (cancellation: CancellationException) {
            // ⚠ Never swallowed. A blanket `catch (Throwable)` here would turn a cancelled run into
            // a run that appears to have failed one check and carried on, with the suite still
            // driving the mouse afterwards.
            throw cancellation
        } catch (error: Throwable) {
            log.w(error) { "$id threw" }
            outcome(
                id,
                name,
                CheckResult.Status.FAIL,
                started,
                scope,
                "Threw ${error::class.simpleName}: ${SelfTestRedaction.text(error.message.orEmpty())}",
            )
        }
        results += result
    }

    /**
     * Records an observation with nothing asserted on it.
     *
     * Kept distinct from a pass so the headline count means something. Inventing a threshold for
     * "what bitrate is acceptable" just to have a pass/fail would make the summary confidently
     * wrong; reporting the number and letting a person judge it is the honest option.
     */
    suspend fun observe(id: String, name: String, block: suspend (CheckScope) -> Unit) {
        progress("$id $name")
        val scope = CheckScope()
        val started = System.currentTimeMillis()
        val result = try {
            block(scope)
            outcome(id, name, CheckResult.Status.INFO, started, scope, scope.summary ?: "Recorded.")
        } catch (skip: SelfTestSkip) {
            outcome(id, name, CheckResult.Status.SKIP, started, scope, skip.reason)
        } catch (cancellation: CancellationException) {
            // ⚠ Never swallowed. A blanket `catch (Throwable)` here would turn a cancelled run into
            // a run that appears to have failed one check and carried on, with the suite still
            // driving the mouse afterwards.
            throw cancellation
        } catch (error: Throwable) {
            outcome(
                id,
                name,
                CheckResult.Status.FAIL,
                started,
                scope,
                "Threw ${error::class.simpleName}: ${SelfTestRedaction.text(error.message.orEmpty())}",
            )
        }
        results += result
    }

    private fun outcome(
        id: String,
        name: String,
        status: CheckResult.Status,
        startedAtMs: Long,
        scope: CheckScope,
        detail: String,
    ) = CheckResult(
        id = id,
        name = name,
        status = status,
        durationMs = System.currentTimeMillis() - startedAtMs,
        detail = detail,
        values = scope.values.toMap(),
        screenshots = scope.screenshots.toList(),
    )
}
