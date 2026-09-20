package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerEngineReadiness

/**
 * mpv's own cache verdict, as the bit flags [NativePlayerBridge.engineReadinessFlags] returns.
 *
 * One export rather than five is the same trade the diagnostics export made: the JNI surface costs
 * per symbol, and every one of these is read at the same instant anyway. The values are duplicated in
 * `player_bridge.cpp`, `player_bridge.mm` and the Linux bridge; changing one means changing all four.
 */
internal object NativeMpvReadinessFlags {
    /** `paused-for-cache`: the engine stopped itself because the cache ran dry. */
    const val PAUSED_FOR_CACHE = 1 shl 0

    /** `cache-buffering-state` is a percentage below 100, so the cache is still filling to its target. */
    const val CACHE_BUFFERING = 1 shl 1

    /** `pause`, read raw rather than from the controller's intent. */
    const val PAUSED = 1 shl 2

    /** `seeking`: a seek is in flight, so nothing it reports is an answer yet. */
    const val SEEKING = 1 shl 3

    /** `core-idle`: the engine is not producing frames, for any reason including a deliberate pause. */
    const val CORE_IDLE = 1 shl 4
}

/**
 * libmpv's own cache verdict, which is what decides when it will resume - not a buffer constant
 * chosen here.
 *
 * `paused-for-cache` is the engine saying it stopped because the cache ran dry, and it is reported
 * independently of the `pause` flag, so a member the party has paused still tells the truth about its
 * cache. `cache-buffering-state` is the percentage of cache fill until it unpauses, which is only
 * meaningful while playback is intended - a deliberate pause can leave the last value standing - so it
 * is read only when not paused. A seek in flight is a transition rather than an answer, and the
 * caller's fallback reads it better than either verdict would.
 *
 * The same mapping the Android bridge applies to the same properties, deliberately: an Android host
 * and a desktop guest have to mean the same thing by "starved" or the stall hold is asymmetric.
 */
internal fun mpvEngineReadiness(
    pausedForCache: Boolean,
    cacheBuffering: Boolean,
    paused: Boolean,
    seeking: Boolean,
    idle: Boolean,
    durationMs: Long,
): PlayerEngineReadiness = when {
    durationMs <= 0L && idle -> PlayerEngineReadiness.NoSource
    pausedForCache -> PlayerEngineReadiness.Buffering
    cacheBuffering && !paused -> PlayerEngineReadiness.Buffering
    seeking -> PlayerEngineReadiness.Unknown
    else -> PlayerEngineReadiness.Ready
}

/** [mpvEngineReadiness] over the packed flags, so the unpacking is tested with the mapping. */
internal fun mpvEngineReadinessFromFlags(flags: Int, durationMs: Long): PlayerEngineReadiness =
    mpvEngineReadiness(
        pausedForCache = flags and NativeMpvReadinessFlags.PAUSED_FOR_CACHE != 0,
        cacheBuffering = flags and NativeMpvReadinessFlags.CACHE_BUFFERING != 0,
        paused = flags and NativeMpvReadinessFlags.PAUSED != 0,
        seeking = flags and NativeMpvReadinessFlags.SEEKING != 0,
        idle = flags and NativeMpvReadinessFlags.CORE_IDLE != 0,
        durationMs = durationMs,
    )
