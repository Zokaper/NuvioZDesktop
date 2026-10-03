package com.nuvio.app.features.plugins

import com.dokar.quickjs.binding.asyncFunction
import com.nuvio.app.features.plugins.runtime.js.JsRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginQuickJsLifecycleDesktopTest {
    @Test
    fun `lifecycle fencing preserves concurrent runtime and host work`() = runBlocking {
        val entered = AtomicInteger()
        val allEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withTimeout(10_000) {
            coroutineScope {
                val jobs = (0 until 10).map { index ->
                    async(Dispatchers.Default) {
                        JsRuntime().use {
                            asyncFunction("hold") {
                                if (entered.incrementAndGet() == 10) allEntered.complete(Unit)
                                release.await()
                                index
                            }
                            evaluate<Int>("await hold()")
                        }
                    }
                }
                allEntered.await()
                // Ten independent native runtimes are alive and awaiting host work together.
                assertEquals(10, entered.get())
                release.complete(Unit)
                assertEquals((0 until 10).toList(), jobs.awaitAll())
            }
        }
    }
}
