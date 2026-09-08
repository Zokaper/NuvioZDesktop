package com.nuvio.app.features.watchparty

/**
 * Resolves the Details-screen Watch Together action against the one-active-party contract.
 *
 * A failed source publication can leave a perfectly valid party active. Creating another party in
 * that state is correctly rejected by the backend, so entry must reopen the held/restored party
 * before it attempts creation.
 */
internal suspend fun resolveWatchPartyEntry(
    heldParty: WatchPartyState?,
    restoreActive: suspend () -> Result<WatchPartyState?>,
    createParty: suspend () -> Result<WatchPartyState>,
): Result<WatchPartyState> {
    heldParty?.takeUnless { it.status == WatchPartyStatus.ended }?.let { return Result.success(it) }

    return restoreActive().fold(
        onSuccess = { restored ->
            restored?.takeUnless { it.status == WatchPartyStatus.ended }
                ?.let { Result.success(it) }
                ?: createParty()
        },
        onFailure = { Result.failure(it) },
    )
}
