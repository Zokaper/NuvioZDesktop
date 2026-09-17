package com.nuvio.app.core.network

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Startup reads [SupabaseProvider.client] from several threads at once. Each client owns its own Auth,
 * so two of them meant AuthRepository could watch one while sign-in ran on the other: the session was
 * saved, the login screen never moved, and only a restart let the user in.
 */
class SupabaseProviderConcurrencyTest {

    @Test
    fun concurrentFirstReadsShareOneClient() {
        // supabase-kt keeps its session in java.util.prefs, which on Windows is the real registry
        // key the installed app signs in with. A client built here would load that session and could
        // rotate its refresh token, so the test must never reach the real store.
        System.setProperty("java.util.prefs.PreferencesFactory", InMemoryPreferencesFactory::class.java.name)
        check(Preferences.userRoot() is InMemoryPreferences) {
            "java.util.prefs was initialised before this test; refusing to touch the real session store"
        }

        runBlocking { SupabaseProvider.reset() }
        val threads = 16
        val start = CountDownLatch(1)
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    val client = SupabaseProvider.client
                    synchronized(seen) { seen += client }
                }
            }
            start.countDown()
            pool.shutdown()
            check(pool.awaitTermination(60, TimeUnit.SECONDS)) { "client creation did not finish" }
            assertEquals(1, seen.size, "concurrent first reads built ${seen.size} Supabase clients")
        } finally {
            pool.shutdownNow()
            runBlocking { SupabaseProvider.reset() }
        }
    }
}

class InMemoryPreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
    private val values = HashMap<String, String>()
    private val children = HashMap<String, InMemoryPreferences>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() {}
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { InMemoryPreferences(this, name) }
    override fun syncSpi() {}
    override fun flushSpi() {}
}

class InMemoryPreferencesFactory : PreferencesFactory {
    override fun userRoot(): Preferences = root
    override fun systemRoot(): Preferences = root

    private companion object {
        val root = InMemoryPreferences(null, "")
    }
}
