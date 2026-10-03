package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher

internal fun createPluginQuickJs(dispatcher: CoroutineDispatcher): QuickJs = QuickJs.create(dispatcher)
internal fun closePluginQuickJs(runtime: QuickJs) = runtime.close()
