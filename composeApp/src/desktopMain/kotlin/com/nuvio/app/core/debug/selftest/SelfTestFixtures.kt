package com.nuvio.app.core.debug.selftest

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.exists

/**
 * The titles the suite exercises.
 *
 * These have to be *real* content that the user's own addons will actually return sources for,
 * which makes them the one part of the harness that cannot be decided from inside the codebase.
 * The defaults are chosen to be as close to universally indexed as anything gets - a famous film
 * and a famous completed series - so a first run on a normally-configured install finds something.
 * When it does not, that is a finding about the addon set, and the report says so rather than
 * failing the playback checks for a reason that has nothing to do with playback.
 *
 * Override without rebuilding by dropping `self-test-fixtures.json` next to the app's stores in
 * `<appData>/`:
 *
 * ```json
 * { "filmId": "tt0111161", "seriesId": "tt0903747", "seasonNumber": 1, "episodeNumber": 1 }
 * ```
 *
 * ⚠ **Episode ids are deliberately not configurable.** A Stremio episode id is whatever the addon
 * put in its `videos` array, and hardcoding `tt0903747:1:1` assumes a convention that is not
 * guaranteed. The suite fetches the series meta and takes the real id for the requested
 * season/episode, which is also what the app itself does.
 */
@Serializable
internal data class SelfTestFixtures(
    val filmId: String = DEFAULT_FILM_ID,
    val seriesId: String = DEFAULT_SERIES_ID,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    /**
     * How long a real stream is watched before the playback check reports.
     *
     * Wall-clock, and everything sampled over it is expressed the same way. Snapshot **counts**
     * mean different things on different platforms - Android polls at 250 ms and desktop at 500 ms
     * - and that mismatch is called out in `AGENTS.md` as a trap this project has fallen into.
     */
    val playbackWatchSeconds: Int = 45,
    /** Cap on how long the suite waits for a first frame before calling it a failure. */
    val firstFrameTimeoutSeconds: Int = 60,
    /** Whether to enqueue a real download. Off would make S7 skip, not silently pass. */
    val runDownloadCheck: Boolean = true,
    /** Whether to write and restore real settings for the persistence check. */
    val runSettingsPersistenceCheck: Boolean = true,
) {
    companion object {
        const val DEFAULT_FILM_ID = "tt0111161"
        const val DEFAULT_SERIES_ID = "tt0903747"
        private const val FIXTURE_FILE_NAME = "self-test-fixtures.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the override file if present; falls back to the defaults, never throws. */
        fun load(): SelfTestFixtures {
            val path = DesktopStorage.rootDir.resolve(FIXTURE_FILE_NAME)
            if (!path.exists()) return SelfTestFixtures()
            return runCatching { json.decodeFromString<SelfTestFixtures>(Files.readString(path)) }
                .getOrDefault(SelfTestFixtures())
        }

        /** Where an override would go, for the report to name when the defaults were used. */
        fun overridePath(): String = DesktopStorage.rootDir.resolve(FIXTURE_FILE_NAME).toString()
    }
}
