package com.nuvio.app.features.player.desktop

/** Synthetic lifecycle handles never reach an OS JNI bridge, even when that bridge loads on CI. */
internal fun lifecycleController(
    host: NativePlayerHost,
    nativeCreate: NativePlayerCreate,
    nativeDispose: (Long) -> Unit,
    isHostDisplayable: () -> Boolean = { host.isDisplayable },
    resolveHostView: () -> Long = { AwtNativeViewResolver.resolveNativeViewPointer(host) },
    createWaitTimeoutMs: Long = 5_000L,
    releaseTimeoutMs: Long = 10_000L,
    onCreateWaitCompleted: () -> Unit = {},
): NativePlayerController = NativePlayerController(
    host = host, nativeCreate = nativeCreate, nativeDispose = nativeDispose,
    nativeSeekTo = { _, _ -> }, nativePromoteOpeningContainer = {},
    nativeSetPaused = { _, _ -> }, nativeHasFirstFrame = { false },
    isHostDisplayable = isHostDisplayable, resolveHostView = resolveHostView,
    createWaitTimeoutMs = createWaitTimeoutMs, releaseTimeoutMs = releaseTimeoutMs,
    onCreateWaitCompleted = onCreateWaitCompleted, configureCreatedPlayer = { _ -> },
)
