package com.nuvio.app.core.network

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InstallScopedSessionManagerTest {

    private class MemorySlot(var value: String? = null) : StoredSessionSlot {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    private val own = MemorySlot()
    private val legacy = MemorySlot()
    private val checked = MemorySlot()
    private val manager = InstallScopedSessionManager(own, legacy, checked)

    private fun session(token: String) = UserSession(
        accessToken = token,
        refreshToken = "refresh-$token",
        expiresIn = 3600,
        tokenType = "bearer",
        user = null,
    )

    private fun stored(token: String): String = runBlocking {
        val scratch = MemorySlot()
        InstallScopedSessionManager(scratch, MemorySlot(), MemorySlot("1")).saveSession(session(token))
        scratch.value!!
    }

    @Test
    fun theKeyIsTheOneSupabaseKtWroteTheSharedLoginUnder() {
        // Read off the registry of the machine this was diagnosed on.
        assertEquals("sb-api-nuvio-tv-session", supabaseSessionKey("https://api.nuvio.tv"))
        assertEquals("sb-api-nuvio-tv-session", supabaseSessionKey("https://api.nuvio.tv/"))
        assertEquals("sb-example-com-auth-session", supabaseSessionKey("http://example.com/auth"))
    }

    @Test
    fun aSessionSavedHereIsLoadedFromHere() = runBlocking {
        manager.saveSession(session("mine"))
        assertEquals("mine", manager.loadSession()?.accessToken)
        assertNull(legacy.value, "the shared registry value is never written")
    }

    @Test
    fun theSharedLoginIsMovedNotCopied() = runBlocking {
        legacy.value = stored("shared")

        assertEquals("shared", manager.loadSession()?.accessToken)
        assertNull(legacy.value, "a copy would leave two apps holding one refresh token")
        assertNotNull(own.value)
        assertEquals("shared", manager.loadSession()?.accessToken, "and it is now this install's")
    }

    @Test
    fun theSharedValueIsNeverTakenTwice() = runBlocking {
        manager.loadSession()
        manager.saveSession(session("mine"))
        manager.deleteSession() // signing out

        // Another app writes its own login to the shared value afterwards.
        val vanilla = stored("vanilla")
        legacy.value = vanilla
        assertNull(manager.loadSession(), "a sign-out must not be undone with another app's session")
        assertEquals(vanilla, legacy.value)
    }

    @Test
    fun thisInstallsOwnLoginWinsOverTheSharedOne() = runBlocking {
        manager.saveSession(session("mine"))
        val shared = stored("shared")
        legacy.value = shared

        assertEquals("mine", manager.loadSession()?.accessToken)
        assertEquals(shared, legacy.value, "untouched: nothing was adopted")
    }

    @Test
    fun anUnreadableSharedValueIsLeftWhereItIs() = runBlocking {
        legacy.value = "{not json"

        assertNull(manager.loadSession())
        assertEquals("{not json", legacy.value)
        assertNull(own.value)
    }
}
