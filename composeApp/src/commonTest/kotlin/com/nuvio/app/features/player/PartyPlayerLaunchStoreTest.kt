package com.nuvio.app.features.player

import com.nuvio.app.features.watchparty.PartySourceDescriptorV2
import com.nuvio.app.features.watchparty.PartySourceOriginKind
import com.nuvio.app.features.watchparty.partyReleaseFingerprint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class PartyPlayerLaunchStoreTest {
    @AfterTest fun clear() = PlayerLaunchStore.clear()

    @Test fun samePartyContentAndSourceGenerationReusesResolvedLaunch() {
        val key=key(sourceGeneration=4)
        val launch=launch(key.descriptor)
        PlayerLaunchStore.retainPartyLaunch(key,launch)
        assertSame(launch,PlayerLaunchStore.reusablePartyLaunch(key))
    }

    @Test fun realSourceGenerationChangeInvalidatesResolvedLaunch() {
        val old=key(sourceGeneration=4)
        PlayerLaunchStore.retainPartyLaunch(old,launch(old.descriptor))
        val next=key(sourceGeneration=5)
        PlayerLaunchStore.invalidateRetainedPartyLaunchUnless(next)
        assertNull(PlayerLaunchStore.reusablePartyLaunch(old))
        assertNull(PlayerLaunchStore.reusablePartyLaunch(next))
    }

    @Test fun realContentGenerationChangeInvalidatesResolvedLaunch() {
        val old=key(contentGeneration=2)
        PlayerLaunchStore.retainPartyLaunch(old,launch(old.descriptor))
        PlayerLaunchStore.invalidateRetainedPartyLaunchUnless(old.copy(contentGeneration=3))
        assertNull(PlayerLaunchStore.reusablePartyLaunch(old))
    }

    private fun key(contentGeneration:Int=2,sourceGeneration:Int=4)=PartyPlayerLaunchKey(
        partyId="party",contentGeneration=contentGeneration,sourceGeneration=sourceGeneration,
        descriptor=PartySourceDescriptorV2(
            originKind=PartySourceOriginKind.embedded,originId="nuvio",
            releaseFingerprint=partyReleaseFingerprint("Movie 2026 1080p"),
        ),
    )

    private fun launch(descriptor:PartySourceDescriptorV2)=PlayerLaunch(
        profileId=1,title="Movie",sourceUrl="https://local.invalid/media",
        streamTitle="Movie 1080p",providerName="Local",contentType="movie",videoId="tt1",
        parentMetaId="tt1",parentMetaType="movie",partySourceDescriptor=descriptor,
    )
}
