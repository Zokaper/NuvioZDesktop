package com.nuvio.app.core.network

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a live official Nuvio session into a Nuvio Z session.
 *
 * Users sign in once, to official Nuvio. Nuvio Z's backend cannot be configured to trust that
 * issuer directly - Supabase third-party auth only accepts five named providers - so the Z backend
 * runs a `z-session` function that verifies the official token against Nuvio's published ES256
 * JWKS, confirms with the official API that the profile really belongs to the caller, and has
 * Supabase issue a Z session for it.
 *
 * Nothing here holds or needs a credential belonging to NuvioMedia. The only thing sent is the
 * user's own official access token, to the user's own Z backend, which verifies it against a public
 * key.
 *
 * The exchange is bound to a specific profile: the Z token carries the profile the function
 * verified, and the server reads the identity from that claim rather than from any argument the
 * client passes. Switching profile therefore means a new exchange, not a new argument.
 */
object ZSessionBridge {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient()
    private val mutex = Mutex()

    private var boundProfileId: String? = null

    /** True when a Z session is currently installed for [profileId]. */
    fun hasSessionFor(profileId: String): Boolean =
        boundProfileId == profileId && ZSupabaseProvider.client.auth.currentSessionOrNull() != null

    /**
     * Ensures a Z session exists for [profileId], exchanging one if needed.
     *
     * Returns false rather than throwing when the social surface simply cannot be reached - an
     * unconfigured Z backend, no official session, or a refused exchange. Callers treat that as
     * "social is unavailable", which is the same state an undeployed backend produces, so a failure
     * here hides the surface instead of surfacing an error the user cannot act on.
     */
    suspend fun ensureSession(profileId: String): Boolean {
        if (!ZSupabaseProvider.isConfigured) return false
        if (hasSessionFor(profileId)) return true
        return mutex.withLock {
            // Another caller may have completed the exchange while this one waited for the lock.
            if (hasSessionFor(profileId)) return@withLock true
            exchange(profileId)
        }
    }

    /**
     * Drops the current Z session so the next call exchanges a fresh one.
     *
     * Called when the backend rejects a Z token, which normally means it expired. The official
     * session is the source of truth and is still live, so re-exchanging is always the recovery.
     */
    suspend fun invalidate() {
        mutex.withLock {
            boundProfileId = null
            runCatching { ZSupabaseProvider.client.auth.clearSession() }
        }
    }

    private suspend fun exchange(profileId: String): Boolean {
        val officialToken = runCatching {
            SupabaseProvider.client.auth.currentAccessTokenOrNull()
        }.getOrNull() ?: return false

        val response = runCatching {
            http.post("${ZSupabaseConfig.URL}/functions/v1/z-session") {
                header("apikey", ZSupabaseConfig.PUBLISHABLE_KEY)
                // Set explicitly: this endpoint authenticates the *official* token, not a Z one, so
                // it must not carry whatever session the Z client happens to hold.
                header("Authorization", "Bearer $officialToken")
                contentType(ContentType.Application.Json)
                setBody("""{"profile_id":"$profileId"}""")
            }
        }.getOrNull() ?: return false

        if (!response.status.isSuccess()) return false

        val payload = runCatching {
            json.parseToJsonElement(response.bodyAsText()) as JsonObject
        }.getOrNull() ?: return false

        val accessToken = payload["access_token"]?.jsonPrimitive?.content ?: return false
        val refreshToken = payload["refresh_token"]?.jsonPrimitive?.content.orEmpty()
        val expiresAt = payload["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()

        val installed = runCatching {
            ZSupabaseProvider.client.auth.importSession(
                UserSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = expiresAt?.let { it - currentEpochSeconds() }?.coerceAtLeast(0) ?: DEFAULT_EXPIRY_SECONDS,
                    tokenType = "bearer",
                    user = null,
                ),
            )
        }.isSuccess
        if (!installed) return false

        boundProfileId = profileId
        return true
    }

    private fun currentEpochSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

    private const val DEFAULT_EXPIRY_SECONDS = 3600L
}
