package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher

/**
 * quickjs-kt 1.0.15's JNI VM/cache instance counter is process-global and non-atomic.
 * Protect only create/close; each runtime's evaluations and async host work remain parallel.
 */
private val pluginQuickJsLifecycleLock = Any()

internal fun createPluginQuickJs(dispatcher: CoroutineDispatcher): QuickJs =
    synchronized(pluginQuickJsLifecycleLock) { QuickJs.create(dispatcher) }

internal fun closePluginQuickJs(runtime: QuickJs) =
    synchronized(pluginQuickJsLifecycleLock) { runtime.close() }
