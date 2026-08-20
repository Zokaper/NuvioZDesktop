package com.nuvio.app.core.debug.selftest

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * How a single check came out.
 *
 * ⚠ **[Status.SKIP] is not a pass.** `STATUS.md` states that rule for the hand-run device scripts -
 * *"a step that was not run is not a pass"* - and it matters more here, because a suite that
 * silently skips half of itself when a debrid key is missing looks exactly like a suite that
 * passed. Every skip carries its reason and every summary counts skips separately.
 *
 * [Status.INFO] is for things worth recording that nothing asserts on: the decoder in use, the
 * measured bitrate, how long an addon took. Those are the numbers that make a later failure
 * legible, and inventing a threshold for them just to have something to fail would be worse than
 * reporting them plainly.
 */
@Serializable
internal data class CheckResult(
    val id: String,
    val name: String,
    val status: Status,
    val durationMs: Long,
    /** One line. Goes in the summary table, so it has to stand alone. */
    val detail: String,
    /** Everything observed. Ordered, because these are read top to bottom. */
    val values: Map<String, String> = emptyMap(),
    /** Paths relative to the run directory. */
    val screenshots: List<String> = emptyList(),
) {
    @Serializable
    enum class Status { PASS, FAIL, SKIP, INFO }
}

@Serializable
internal data class SelfTestRun(
    val startedAt: String,
    val finishedAt: String,
    val durationMs: Long,
    val environment: Map<String, String>,
    val checks: List<CheckResult>,
) {
    val passed: Int get() = checks.count { it.status == CheckResult.Status.PASS }
    val failed: Int get() = checks.count { it.status == CheckResult.Status.FAIL }
    val skipped: Int get() = checks.count { it.status == CheckResult.Status.SKIP }
    val informational: Int get() = checks.count { it.status == CheckResult.Status.INFO }
}

internal object SelfTestReportWriter {

    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun runDirectoryName(at: LocalDateTime = LocalDateTime.now()): String =
        at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    fun write(runDir: Path, run: SelfTestRun) {
        Files.createDirectories(runDir)
        Files.writeString(runDir.resolve("report.json"), json.encodeToString(run))
        Files.writeString(runDir.resolve("report.md"), markdown(run))
    }

    /**
     * The report a person - or an agent - actually reads.
     *
     * Failures first, then the full table, then a section per check. Ordering by severity rather
     * than by run order is deliberate: the `setup-wizard-renders` artifact was green for four
     * passes while nobody opened it, and a report whose first screen is thirty passing rows earns
     * exactly that treatment.
     */
    private fun markdown(run: SelfTestRun): String = buildString {
        appendLine("# Nuvio Z desktop self-test")
        appendLine()
        appendLine("`${run.startedAt}` → `${run.finishedAt}` (${humanDuration(run.durationMs)})")
        appendLine()
        appendLine(
            "**${run.passed} passed · ${run.failed} failed · ${run.skipped} skipped · " +
                "${run.informational} informational**",
        )
        appendLine()
        appendLine("> A skipped check is **not** a pass. Each one names why it did not run.")
        appendLine(">")
        appendLine(
            "> ⚠ Screenshots are **not** redacted - they are pixels. The suite avoids the debrid " +
                "and account pages for that reason, but check before sharing this folder.",
        )
        appendLine()

        val failures = run.checks.filter { it.status == CheckResult.Status.FAIL }
        if (failures.isNotEmpty()) {
            appendLine("## Failures")
            appendLine()
            failures.forEach { appendLine("- **${it.id} ${it.name}** — ${it.detail}") }
            appendLine()
        }

        val skips = run.checks.filter { it.status == CheckResult.Status.SKIP }
        if (skips.isNotEmpty()) {
            appendLine("## Not run")
            appendLine()
            skips.forEach { appendLine("- **${it.id} ${it.name}** — ${it.detail}") }
            appendLine()
        }

        appendLine("## Summary")
        appendLine()
        appendLine("| | Check | Status | Time | Detail |")
        appendLine("| --- | --- | --- | --- | --- |")
        run.checks.forEach { check ->
            appendLine(
                "| ${check.id} | ${check.name} | ${statusLabel(check.status)} | " +
                    "${humanDuration(check.durationMs)} | ${escapeCell(check.detail)} |",
            )
        }
        appendLine()

        appendLine("## Environment")
        appendLine()
        run.environment.forEach { (key, value) -> appendLine("- **$key**: $value") }
        appendLine()

        appendLine("## Detail")
        appendLine()
        run.checks.forEach { check ->
            appendLine("### ${check.id} ${check.name} — ${statusLabel(check.status)}")
            appendLine()
            appendLine(check.detail)
            appendLine()
            if (check.values.isNotEmpty()) {
                appendLine("| | |")
                appendLine("| --- | --- |")
                check.values.forEach { (key, value) -> appendLine("| $key | ${escapeCell(value)} |") }
                appendLine()
            }
            if (check.screenshots.isNotEmpty()) {
                check.screenshots.forEach { appendLine("![$it]($it)") }
                appendLine()
            }
        }
    }

    private fun statusLabel(status: CheckResult.Status): String = when (status) {
        CheckResult.Status.PASS -> "PASS"
        CheckResult.Status.FAIL -> "**FAIL**"
        CheckResult.Status.SKIP -> "SKIP"
        CheckResult.Status.INFO -> "info"
    }

    /** Newlines and pipes both break a markdown table row; a broken table hides the failure. */
    private fun escapeCell(value: String): String =
        value.replace("|", "\\|").replace("\n", " ").trim()

    private fun humanDuration(millis: Long): String {
        val duration = Duration.ofMillis(millis)
        return when {
            duration.toMinutes() > 0 -> "${duration.toMinutes()}m ${duration.toSecondsPart()}s"
            duration.seconds > 0 -> "${duration.seconds}.${"%03d".format(duration.toMillisPart())}s"
            else -> "${millis}ms"
        }
    }

    fun formatTimestamp(at: LocalDateTime): String = at.format(timestampFormat)
}
