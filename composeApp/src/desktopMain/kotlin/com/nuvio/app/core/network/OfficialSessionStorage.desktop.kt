package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences

/**
 * ⚠ **Vanilla-bug patch, `drop-at-next-sync`** (Docs/VANILLA-BUGS.md V3, Docs/PATCH-SURFACE.md).
 *
 * Upstream installs `Auth` with supabase-kt's default session storage, which on the JVM is
 * `Preferences.userRoot()`: the Windows registry key `HKCU\Software\JavaSoft\Prefs`, value
 * `sb-api-nuvio-tv-session`. That name comes from the backend host alone, so Nuvio Z, Nuvio Z Debug and
 * vanilla Nuvio desktop all read and write **one** stored login - while everything else each of them
 * stores lives in its own data root (`DesktopStorage`), precisely so they cannot corrupt one another.
 *
 * Each app refreshes its own in-memory copy of that login. Supabase rotates the refresh token on every
 * refresh and treats a rotated token presented again as stolen, revoking the whole session. So two of
 * these apps running on one machine eventually sign each other out, and signing out of any one clears
 * the login under all of them. Seen 2026-09-28: the Debug build had been signed out for three days, and
 * the only symptom was Social saying "Sign in to your Nuvio account", because the app itself carries on
 * over cached profiles.
 *
 * The login now lives in this install's own data root, beside the rest of its state.
 */
internal actual fun officialSessionManager(backendUrl: String): SessionManager? {
    val key = supabaseSessionKey(backendUrl)
    return InstallScopedSessionManager(
        own = DesktopStoreSlot(key),
        legacy = PreferencesSlot(Preferences.userRoot(), key),
        // Beside the value it guards, not in the data root: signing out wipes the data root, and the
        // marker must outlive that (see `InstallScopedSessionManager.adoptLegacySession`).
        legacyChecked = PreferencesSlot(
            Preferences.userRoot().node(MIGRATION_NODE),
            // Preferences refuses keys over 80 characters; the install name is the part that must survive.
            "$key@${DesktopStorage.rootDir.fileName}".takeLast(Preferences.MAX_KEY_LENGTH),
        ),
    )
}

/**
 * Keeps the session in one slot and, once per install, takes over the login supabase-kt's default left
 * in the shared registry value.
 *
 * The takeover **moves** the login rather than copying it. A copy would leave two apps holding the same
 * refresh token, which is exactly the fault being removed: whichever refreshed second would revoke both.
 * The cost is one-off and bounded - the first Nuvio Z build to start after this update keeps the user
 * signed in, and any other desktop app that shared the login (the other Z build, vanilla Nuvio) asks
 * for a sign-in once.
 */
internal class InstallScopedSessionManager(
    private val own: StoredSessionSlot,
    private val legacy: StoredSessionSlot,
    private val legacyChecked: StoredSessionSlot,
) : SessionManager {

    override suspend fun saveSession(session: UserSession) {
        own.write(sessionJson.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession? {
        own.read()?.let { stored -> return decode(stored) }
        return adoptLegacySession()
    }

    override suspend fun deleteSession() {
        own.clear()
    }

    /**
     * Only ever once. After this install has looked, the shared value belongs to whatever other app
     * wrote it; taking it again after a sign-out would sign this app in as that app's session and put
     * two holders on one refresh token all over again.
     */
    private fun adoptLegacySession(): UserSession? {
        if (legacyChecked.read() != null) return null
        val stored = legacy.read()
        val session = stored?.let(::decode)
        if (stored != null && session != null) {
            own.write(stored)
            legacy.clear()
        }
        // Marked last, so an interruption before the move completes is retried, not lost.
        legacyChecked.write(LEGACY_CHECKED)
        return session
    }

    private fun decode(stored: String): UserSession? =
        runCatching { sessionJson.decodeFromString(UserSession.serializer(), stored) }.getOrNull()
}

/** One stored string. Real slots are the data root and the registry; tests use memory. */
internal interface StoredSessionSlot {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/**
 * supabase-kt's own key for a backend, so the legacy value is found under exactly the name it was
 * written with: `sb-` + the URL without its scheme, `/` and `.` turned into `-`, + `-session`.
 * Also this install's key, so a self-hosted server never reads the official server's login.
 */
internal fun supabaseSessionKey(backendUrl: String): String {
    val host = backendUrl.split("//").last().removeSuffix("/").replace('/', '-').replace('.', '-')
    return "sb-$host-session"
}

/**
 * Looks the store up on every call rather than holding it: `DesktopStorage.wipe()`, which sign-out
 * runs, discards the store instances along with the files.
 */
private class DesktopStoreSlot(private val key: String) : StoredSessionSlot {
    override fun read(): String? = DesktopStorage.store(STORE_NAME).getString(key)
    override fun write(value: String) {
        DesktopStorage.store(STORE_NAME).putString(key, value)
    }
    override fun clear() {
        DesktopStorage.store(STORE_NAME).remove(key)
    }
}

private class PreferencesSlot(private val node: Preferences, private val key: String) : StoredSessionSlot {
    override fun read(): String? = runCatching { node.get(key, null) }.getOrNull()
    override fun write(value: String) {
        runCatching { node.put(key, value); node.flush() }
    }
    override fun clear() {
        runCatching { node.remove(key); node.flush() }
    }
}

private val sessionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val STORE_NAME = "nuvio_official_session"
private const val MIGRATION_NODE = "nuvio-z/official-session-moved"
private const val LEGACY_CHECKED = "1"
