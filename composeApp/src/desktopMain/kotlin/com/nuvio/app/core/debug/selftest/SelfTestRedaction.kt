package com.nuvio.app.core.debug.selftest

import java.net.URI
import java.security.MessageDigest

/**
 * Keeps private configuration out of a file that is meant to be read back and quoted.
 *
 * `AGENTS.md` forbids printing addon manifest URLs, AIOStreams URLs, debrid credentials, Trakt
 * secrets, session tokens and account identifiers. A self-test report is exactly the kind of
 * artefact that leaks them: it names every addon it talked to, every link it minted and every
 * account it checked, and then it gets pasted into a conversation.
 *
 * Two rules shape everything here.
 *
 * **A secret is dropped, never masked in place.** `sk-live-****1234` still discloses the provider,
 * the length and the prefix, and a masked token is routinely treated as safe *because* it looks
 * handled. Nothing here emits a partial secret.
 *
 * **Identity is preserved without the value.** A report that called every addon `<redacted>` would
 * be useless for the thing it exists to do - saying which one was slow. Hashing gives a stable
 * name that means the same addon across runs and reveals nothing about which addon it is.
 *
 * ⚠ **Screenshots are outside all of this.** They are pixels, and a settings page shows a key in
 * plain text. The suite does not screenshot the debrid, account or Trakt pages; see `SelfTestUiWalk`.
 */
internal object SelfTestRedaction {

    private val secretPatterns = listOf(
        // Bearer tokens and API keys in free text, e.g. an error message that quoted a header.
        Regex("""(?i)\b(bearer|token|api[-_ ]?key|apikey|password|secret)\b\s*[:=]\s*\S+"""),
        // Real-Debrid / Premiumize / TorBox keys are long opaque alphanumeric runs.
        Regex("""\b[A-Za-z0-9_-]{32,}\b"""),
    )

    /** A stable, meaningless name for a URL: same addon, same label, every run. */
    fun addonLabel(manifestUrl: String): String {
        val host = runCatching { URI(manifestUrl).host }.getOrNull().orEmpty()
        val suffix = if (host.isBlank()) "" else " ($host)"
        return "addon#${shortHash(manifestUrl)}$suffix"
    }

    /**
     * A minted playback URL, reduced to what can be reasoned about.
     *
     * Host and file extension are the two things a report needs - which provider served it and
     * whether it was an mkv, an m3u8 or a magnet. The path is where the credential lives, so the
     * path goes, all of it.
     */
    fun streamUrl(url: String): String {
        if (url.isBlank()) return ""
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme == "magnet") return "magnet:… (${shortHash(url)})"
        val parsed = runCatching { URI(url) }.getOrNull()
            ?: return "<unparseable url ${shortHash(url)}>"
        val host = parsed.host ?: return "<hostless url ${shortHash(url)}>"
        val extension = parsed.path
            ?.substringAfterLast('/', missingDelimiterValue = "")
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
        return buildString {
            append(parsed.scheme ?: "http").append("://").append(host)
            append("/…")
            if (extension != null) append('.').append(extension)
            append(" (").append(shortHash(url)).append(')')
        }
    }

    /**
     * Free text on its way into the report - an error message, an addon's own description.
     *
     * Applied last and to everything, because the sources are not under our control: an addon can
     * put whatever it likes in an error string, and debrid clients have been known to echo the
     * request URL back in one.
     */
    fun text(value: String): String {
        if (value.isBlank()) return value
        var result = value
        // URLs first: the opaque-run pattern below would otherwise chew a URL into fragments and
        // leave the host visible in pieces.
        result = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""").replace(result) { match ->
            streamUrl(match.value)
        }
        secretPatterns.forEach { pattern ->
            result = pattern.replace(result, "<redacted>")
        }
        return result
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
