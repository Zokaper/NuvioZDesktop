package com.nuvio.app.core.debug.selftest.suites

import com.nuvio.app.core.debug.selftest.CheckScope
import com.nuvio.app.core.debug.selftest.SelfTestContext
import com.nuvio.app.core.debug.selftest.SelfTestRedaction
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import kotlinx.coroutines.delay

/**
 * Metadata and sources, over the network, from the user's own addons.
 *
 * Nothing in `commonTest` crosses a network boundary - 130 test files and not one real addon fetch
 * - so this is the first thing in either repository that can say whether the installed addon set
 * actually answers. That makes the per-addon timings as valuable as the pass: an addon that takes
 * eleven seconds is a user-visible fault that no assertion here would catch, and the report names
 * it either way.
 *
 * Results are threaded into the context for S2 and S3 rather than re-fetched. Repeating the fan-out
 * would triple the run and, worse, let S3 fail over a source search that answered differently the
 * second time - a flake dressed up as a playback fault.
 */
internal object S1Sources {

    /**
     * How long to let every addon answer.
     *
     * Generous on purpose. This is a timeout for *the suite*, not a quality bar - the point is to
     * record what each addon did, and cutting a slow one off at five seconds would report it as
     * absent rather than slow. The distinction matters: absent is a configuration problem and slow
     * is a user-experience one.
     */
    private const val STREAM_LOAD_TIMEOUT_MS = 90_000L
    private const val POLL_INTERVAL_MS = 250L

    suspend fun run(context: SelfTestContext) {
        context.check("S1.1", "Film metadata fetches") { scope ->
            if (!context.addonsConfigured) scope.skip("No active addons - see S0.1.")
            val started = System.currentTimeMillis()
            val meta = MetaDetailsRepository.fetch("movie", context.fixtures.filmId)
            scope.value("film id", context.fixtures.filmId)
            scope.value("fetch ms", System.currentTimeMillis() - started)
            if (meta == null) {
                scope.fail(
                    "No addon returned metadata for ${context.fixtures.filmId}. Either no installed " +
                        "addon serves `meta` for movies, or the fixture id is one they do not carry.",
                )
            }
            context.filmMeta = meta
            describeMeta(scope, meta)
            scope.summary = "${meta.name} in ${System.currentTimeMillis() - started}ms."
        }

        context.check("S1.2", "Series metadata fetches and carries episodes") { scope ->
            if (!context.addonsConfigured) scope.skip("No active addons - see S0.1.")
            val meta = MetaDetailsRepository.fetch("series", context.fixtures.seriesId)
                ?: scope.fail("No addon returned metadata for ${context.fixtures.seriesId}.")
            scope.value("series id", context.fixtures.seriesId)
            context.seriesMeta = meta
            describeMeta(scope, meta)
            scope.value("episodes listed", meta.videos.size)

            // ⚠ The episode id comes from the addon's own `videos` array. Synthesising
            // "<imdb>:<season>:<episode>" assumes a convention that is not part of the protocol,
            // and an addon that names episodes differently would send every later check chasing a
            // video that does not exist - which would look like "no sources found".
            val episode = meta.videos.firstOrNull {
                it.season == context.fixtures.seasonNumber && it.episode == context.fixtures.episodeNumber
            } ?: scope.fail(
                "No S${context.fixtures.seasonNumber}E${context.fixtures.episodeNumber} in the " +
                    "returned video list (${meta.videos.size} episodes).",
            )
            context.episodeVideoId = episode.id
            scope.value("episode id", episode.id)
            scope.value("episode title", episode.title)
            scope.summary = "${meta.name}, ${meta.videos.size} episodes, target ${episode.id}."
        }

        context.check("S1.3", "Sources load for the film") { scope ->
            if (context.filmMeta == null) scope.skip("Film metadata did not load - see S1.1.")
            val streams = loadStreams(
                scope = scope,
                type = "movie",
                videoId = context.fixtures.filmId,
                parentMetaId = context.fixtures.filmId,
                season = null,
                episode = null,
            )
            context.filmStreams = streams
            scope.summary = "${streams.size} source(s)."
        }

        context.check("S1.4", "Sources load for the episode") { scope ->
            val videoId = context.episodeVideoId
                ?: scope.skip("No episode id - see S1.2.")
            val streams = loadStreams(
                scope = scope,
                type = "series",
                videoId = videoId,
                parentMetaId = context.fixtures.seriesId,
                season = context.fixtures.seasonNumber,
                episode = context.fixtures.episodeNumber,
            )
            context.episodeStreams = streams
            scope.summary = "${streams.size} source(s)."
        }
    }

    /**
     * Drives the real `StreamsRepository` and waits for the fan-out to finish.
     *
     * `load` is fire-and-forget into a `StateFlow`, so the wait is a poll rather than an await -
     * which is also what the UI does. Per-addon timings are taken by watching each group stop
     * loading, which is the only place that information exists at all.
     */
    private suspend fun loadStreams(
        scope: CheckScope,
        type: String,
        videoId: String,
        parentMetaId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        StreamsRepository.clear()
        val started = System.currentTimeMillis()
        StreamsRepository.load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            season = season,
            episode = episode,
            // Manual: the suite wants the whole ranked list, not whatever auto-play would have
            // grabbed and navigated away with.
            manualSelection = true,
        )

        val addonFinishedAtMs = LinkedHashMap<String, Long>()
        var state = StreamsRepository.uiState.value
        while (System.currentTimeMillis() - started < STREAM_LOAD_TIMEOUT_MS) {
            state = StreamsRepository.uiState.value
            state.groups.filterNot { it.isLoading }.forEach { group ->
                addonFinishedAtMs.putIfAbsent(group.addonName, System.currentTimeMillis() - started)
            }
            if (!state.isAnyLoading && state.groups.isNotEmpty()) break
            delay(POLL_INTERVAL_MS)
        }
        val elapsed = System.currentTimeMillis() - started

        scope.value("total ms", elapsed)
        scope.value("addons answering", state.groups.size)
        scope.value("streams", state.allStreams.size)
        state.emptyStateReason?.let { scope.value("empty reason", it.name) }

        state.groups.forEach { group ->
            val timing = addonFinishedAtMs[group.addonName]?.let { "${it}ms" } ?: "did not finish"
            val error = group.error?.let { " · error: ${SelfTestRedaction.text(it)}" }.orEmpty()
            scope.value("  ${SelfTestRedaction.text(group.addonName)}", "${group.streams.size} in $timing$error")
        }

        // The ranked head, which is what a user sees and what S2 will try to play. Names are
        // addon-authored free text, so they go through the redactor like everything else.
        state.allStreams.take(10).forEachIndexed { index, stream ->
            scope.value(
                "  #${index + 1}",
                buildString {
                    // Not `streamLabel`: that falls back through `runBlocking { getString(...) }`,
                    // and blocking a dispatcher thread on resource loading from a background suite
                    // is not worth the word "Stream" as a default.
                    append(SelfTestRedaction.text(stream.name ?: stream.title ?: "(unnamed)"))
                    stream.behaviorHints.videoSize?.let { append(" · ").append(humanBytes(it)) }
                    stream.debridCacheStatus?.let { append(" · cache=").append(it) }
                    append(" · ").append(SelfTestRedaction.text(stream.addonName))
                },
            )
        }

        if (state.isAnyLoading) {
            scope.fail("Still loading after ${elapsed}ms - at least one addon never answered.")
        }
        scope.require(state.allStreams.isNotEmpty()) {
            "No sources at all after ${elapsed}ms" +
                (state.emptyStateReason?.let { " (${it.name})" } ?: "") +
                ". Every later playback check depends on this."
        }
        return state.allStreams
    }

    private fun describeMeta(scope: CheckScope, meta: MetaDetails) {
        scope.value("name", meta.name)
        scope.value("year", meta.releaseInfo ?: "(none)")
        scope.value("has poster", meta.poster != null)
        scope.value("has background", meta.background != null)
        scope.value("has logo", meta.logo != null)
        scope.value("cast", meta.cast.size)
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GiB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
        else -> "$bytes B"
    }
}
