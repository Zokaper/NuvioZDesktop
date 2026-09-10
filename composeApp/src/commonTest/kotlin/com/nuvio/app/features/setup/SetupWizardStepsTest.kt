package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wizard's ordering, its branch rules, and the rule that decides whether it appears at all.
 *
 * The wizard is a Compose gate wrapping `AppGateScreen.Main`, so none of it is reachable from
 * a test once it is on screen. Everything asserted here is therefore the only executable proof
 * that a user can get from the first step to the last one and back without falling into a
 * sequence the run never showed them.
 *
 * Revision 7 made that harder rather than easier: three steps are conditional, and two of the
 * conditions can change while the user is standing on the step they govern.
 */
class SetupWizardStepsTest {

    /** Every plan the wizard can actually produce. The property tests below run over all of them. */
    private val everyPlan: List<SetupWizardPlan> = buildList {
        listOf(true, false).forEach { offerSources ->
            listOf("CLASSIC", "STREAMLINED", "INSTANT").forEach { mode ->
                listOf(true, false).forEach { socialEnabled ->
                    listOf(true, false).forEach { offerIdentity ->
                        add(
                            SetupWizardPlan(
                                offerSources = offerSources,
                                playbackModeName = mode,
                                socialEnabled = socialEnabled,
                                offerSocialIdentity = offerIdentity,
                            ),
                        )
                    }
                }
            }
        }
    }

    // --- shouldShowSetupWizard ----------------------------------------------------------

    @Test
    fun aFreshInstallHasNeverCompletedAnyRevision() {
        assertTrue(shouldShowSetupWizard(completedRevision = null, currentRevision = 1))
    }

    @Test
    fun anExistingInstallUpgradingInIsAlsoNull() {
        // The key has never been written for a profile created before 0.5.0-beta, so the
        // upgrade path and the fresh-install path are the same value. That is intended: an
        // existing user has never seen these options either.
        assertTrue(shouldShowSetupWizard(completedRevision = null, currentRevision = SETUP_WIZARD_REVISION))
    }

    @Test
    fun completingTheCurrentRevisionStopsIt() {
        assertFalse(
            shouldShowSetupWizard(
                completedRevision = SETUP_WIZARD_REVISION,
                currentRevision = SETUP_WIZARD_REVISION,
            ),
        )
    }

    @Test
    fun everyEarlierRevisionMustSeeTheCurrentFlow() {
        // The reason this constant keeps moving. Revision 1 was the preset fork; revision 2 was
        // the six-step flow behind a translucent panel; revision 3 asked for a Trakt connection
        // that did not work; revision 6 asked four appearance steps and never mentioned social.
        // Each asked a set of questions this build no longer asks.
        (1..6).forEach { earlier ->
            assertTrue(
                shouldShowSetupWizard(completedRevision = earlier, currentRevision = SETUP_WIZARD_REVISION),
                "revision $earlier must see revision $SETUP_WIZARD_REVISION",
            )
        }
    }

    @Test
    fun aDowngradeMustNotReAsk() {
        // Storage is synced, so a profile can carry a revision from a newer build than the one
        // reading it. That user has answered a superset of what this build would ask.
        assertFalse(
            shouldShowSetupWizard(
                completedRevision = SETUP_WIZARD_REVISION + 1,
                currentRevision = SETUP_WIZARD_REVISION,
            ),
        )
    }

    // --- the playback branch ------------------------------------------------------------

    @Test
    fun eachModeAsksForWhatItCanActuallyUse() {
        // The three names are spelled out rather than looped over `PlaybackMode.entries`,
        // because this file is import-free. `PlaybackSetupVariantCoverageTest` in the playback
        // package is the other half: it asserts every real enum name is covered here.
        assertEquals(PlaybackSetupVariant.None, playbackSetupVariant("CLASSIC"))
        assertEquals(PlaybackSetupVariant.QualityBand, playbackSetupVariant("STREAMLINED"))
        assertEquals(PlaybackSetupVariant.AutomaticBand, playbackSetupVariant("INSTANT"))
    }

    @Test
    fun anUnknownModeAsksNothingRatherThanTheWrongThing() {
        // Reachable for real: a profile can carry a mode written by a newer build, or one this
        // build has withdrawn. `PlaybackMode.fromStorage` resolves those to CLASSIC, and None
        // is the answer that agrees with it. Asking another mode's questions would write
        // preferences the user's actual mode cannot use.
        listOf(null, "", "classic", "Streamlined", "TIER_MODE", "  INSTANT  ").forEach { name ->
            assertEquals(
                PlaybackSetupVariant.None,
                playbackSetupVariant(name),
                "unrecognised mode name <$name> must ask nothing",
            )
        }
    }

    @Test
    fun classicHasNoPlaybackSetupStepAtAll() {
        // Not shown-and-empty. Classic's own card says the user reads the releases and picks
        // one; a configuration screen with nothing on it directly after that reads as a bug.
        val classic = SetupWizardPlan(playbackModeName = "CLASSIC")
        assertFalse(setupWizardSteps(classic).contains(SetupStep.PlaybackSetup))
        assertEquals(SetupStep.Sources, nextSetupStep(SetupStep.PlaybackMode, classic))
    }

    @Test
    fun theAutomaticModesBothGetTheStep() {
        listOf("STREAMLINED", "INSTANT").forEach { mode ->
            val plan = SetupWizardPlan(playbackModeName = mode)
            assertTrue(setupWizardSteps(plan).contains(SetupStep.PlaybackSetup), mode)
            assertEquals(SetupStep.PlaybackSetup, nextSetupStep(SetupStep.PlaybackMode, plan), mode)
        }
    }

    // --- the sequence -------------------------------------------------------------------

    @Test
    fun theFullPlanIsEveryStepInDeclarationOrder() {
        val full = SetupWizardPlan(
            offerSources = true,
            playbackModeName = "STREAMLINED",
            socialEnabled = true,
            offerSocialIdentity = true,
        )
        assertEquals(SetupStep.entries, setupWizardSteps(full))
        assertEquals(9, SetupStep.entries.size)
    }

    @Test
    fun playbackIsAskedBeforeAppearance() {
        // The Phase 5 product decision, pinned: what Nuvio Z does before what it looks like.
        val steps = SetupStep.entries
        assertTrue(steps.indexOf(SetupStep.PlaybackMode) < steps.indexOf(SetupStep.Look))
        assertTrue(steps.indexOf(SetupStep.PlaybackSetup) < steps.indexOf(SetupStep.Look))
        assertTrue(steps.indexOf(SetupStep.Sources) < steps.indexOf(SetupStep.Look))
        assertTrue(steps.indexOf(SetupStep.SocialOptIn) < steps.indexOf(SetupStep.Look))
    }

    @Test
    fun sourcesSitsWithPlaybackRatherThanWithAppearance() {
        // Source readiness is playback readiness. Revision 6 had it at position seven, after
        // four appearance steps.
        val steps = SetupStep.entries
        assertTrue(steps.indexOf(SetupStep.Sources) > steps.indexOf(SetupStep.PlaybackSetup))
        assertTrue(steps.indexOf(SetupStep.Sources) < steps.indexOf(SetupStep.SocialOptIn))
    }

    @Test
    fun appearanceIsTwoStepsNotFour() {
        // Revision 6 spent Cards, Home, Details and Theme - fourteen controls - on appearance.
        // Revision 7 keeps the four that change something before the app has been used.
        val appearance = SetupStep.entries.filter { it == SetupStep.Look || it == SetupStep.Theme }
        assertEquals(listOf(SetupStep.Look, SetupStep.Theme), appearance)
        assertFalse(SetupStep.entries.any { it.name == "Cards" })
        assertFalse(SetupStep.entries.any { it.name == "Home" })
        assertFalse(SetupStep.entries.any { it.name == "Details" })
    }

    @Test
    fun anOptionalStepWithNothingToOfferIsDroppedNotShown() {
        val steps = setupWizardSteps(SetupWizardPlan(offerSources = false))
        assertFalse(steps.contains(SetupStep.Sources))
        assertEquals(SetupStep.Done, steps.last())
        assertEquals(SetupStep.Theme, steps[steps.size - 2])
    }

    @Test
    fun socialIdentityIsAskedOnlyWhenSocialIsOnAndThereIsNoHandle() {
        fun plan(enabled: Boolean, offer: Boolean) =
            SetupWizardPlan(socialEnabled = enabled, offerSocialIdentity = offer)

        assertTrue(setupWizardSteps(plan(enabled = true, offer = true)).contains(SetupStep.SocialIdentity))
        assertFalse(setupWizardSteps(plan(enabled = true, offer = false)).contains(SetupStep.SocialIdentity))
        assertFalse(setupWizardSteps(plan(enabled = false, offer = true)).contains(SetupStep.SocialIdentity))
        assertFalse(setupWizardSteps(plan(enabled = false, offer = false)).contains(SetupStep.SocialIdentity))
    }

    @Test
    fun anExistingSocialUserIsNotAskedToChooseAHandleAgain() {
        // The counterpart of the Sources rule: a profile that already has an identity gets the
        // social layer switched on and nothing else asked of it.
        val plan = SetupWizardPlan(socialEnabled = true, offerSocialIdentity = false)
        assertEquals(SetupStep.Look, nextSetupStep(SetupStep.SocialOptIn, plan))
    }

    @Test
    fun traktIsGoneEntirelyRatherThanDefaultedOff() {
        // Revision 4. It offered a connection that does not work yet, and a first-run flow that
        // asks for an account it cannot use is worse than one that does not ask.
        assertFalse(SetupStep.entries.any { it.name == "Trakt" })
    }

    // --- next / previous ----------------------------------------------------------------

    @Test
    fun theStepsRunInOrder() {
        val plan = SetupWizardPlan(
            playbackModeName = "STREAMLINED",
            socialEnabled = true,
            offerSocialIdentity = true,
        )
        assertEquals(SetupStep.PlaybackMode, nextSetupStep(SetupStep.Welcome, plan))
        assertEquals(SetupStep.PlaybackSetup, nextSetupStep(SetupStep.PlaybackMode, plan))
        assertEquals(SetupStep.Sources, nextSetupStep(SetupStep.PlaybackSetup, plan))
        assertEquals(SetupStep.SocialOptIn, nextSetupStep(SetupStep.Sources, plan))
        assertEquals(SetupStep.SocialIdentity, nextSetupStep(SetupStep.SocialOptIn, plan))
        assertEquals(SetupStep.Look, nextSetupStep(SetupStep.SocialIdentity, plan))
        assertEquals(SetupStep.Theme, nextSetupStep(SetupStep.Look, plan))
        assertEquals(SetupStep.Done, nextSetupStep(SetupStep.Theme, plan))
    }

    @Test
    fun backWalksTheSameOrderInReverse() {
        val plan = SetupWizardPlan(
            playbackModeName = "STREAMLINED",
            socialEnabled = true,
            offerSocialIdentity = true,
        )
        assertEquals(SetupStep.Theme, previousSetupStep(SetupStep.Done, plan))
        assertEquals(SetupStep.Look, previousSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.SocialIdentity, previousSetupStep(SetupStep.Look, plan))
        assertEquals(SetupStep.SocialOptIn, previousSetupStep(SetupStep.SocialIdentity, plan))
        assertEquals(SetupStep.PlaybackMode, previousSetupStep(SetupStep.PlaybackSetup, plan))
    }

    // --- resuming a saved position ------------------------------------------------------

    @Test
    fun aSavedStepResumesWhereItWasLeft() {
        assertEquals(SetupStep.Theme, setupStepForSavedName("Theme"))
        assertEquals(SetupStep.SocialOptIn, setupStepForSavedName("SocialOptIn"))
    }

    @Test
    fun aSavedStepThatNoLongerExistsFallsBackToTheStart() {
        // Reachable for real: revision 3 deleted `ContinueWatching` and `Episodes`, revision 4
        // deleted `Trakt`, and revision 7 deleted `Cards`, `Home` and `Details`. A wizard
        // restored after an app update can be holding any of them. The wizard gates the app, so
        // the only acceptable answer is a step that exists.
        listOf(
            "Cards", "Home", "Details", "Trakt", "ContinueWatching", "Episodes",
            "HomeScreen", "DetailsScreen", null, "",
        ).forEach { name ->
            assertEquals(SetupStep.Welcome, setupStepForSavedName(name), "saved name <$name>")
        }
    }

    @Test
    fun theFirstStepHasNothingBeforeIt() {
        assertNull(previousSetupStep(SetupStep.Welcome, SetupWizardPlan()))
    }

    @Test
    fun theLastStepHasNothingAfterIt() {
        assertNull(nextSetupStep(SetupStep.Done, SetupWizardPlan()))
    }

    // --- a step that leaves the plan under the user's feet -------------------------------

    @Test
    fun aDroppedOptionalStepIsSteppedOver() {
        val plan = SetupWizardPlan(offerSources = false)
        assertEquals(SetupStep.SocialOptIn, nextSetupStep(SetupStep.PlaybackMode, plan))
        assertEquals(SetupStep.PlaybackMode, previousSetupStep(SetupStep.SocialOptIn, plan))
    }

    @Test
    fun aStepTheRunDroppedStillFindsItsWayForward() {
        // All three ways a step can leave the plan while the user stands on it. Each would
        // strand somebody if `nextSetupStep` answered null for a step outside the plan.
        val addonInstalled = SetupWizardPlan(offerSources = false)
        assertEquals(SetupStep.SocialOptIn, nextSetupStep(SetupStep.Sources, addonInstalled))
        assertEquals(SetupStep.PlaybackMode, previousSetupStep(SetupStep.Sources, addonInstalled))

        val switchedToClassic = SetupWizardPlan(playbackModeName = "CLASSIC")
        assertEquals(SetupStep.Sources, nextSetupStep(SetupStep.PlaybackSetup, switchedToClassic))
        assertEquals(SetupStep.PlaybackMode, previousSetupStep(SetupStep.PlaybackSetup, switchedToClassic))

        val socialTurnedOff = SetupWizardPlan(socialEnabled = false)
        assertEquals(SetupStep.Look, nextSetupStep(SetupStep.SocialIdentity, socialTurnedOff))
        assertEquals(SetupStep.SocialOptIn, previousSetupStep(SetupStep.SocialIdentity, socialTurnedOff))
    }

    // --- progress and completion --------------------------------------------------------

    @Test
    fun positionsAreOneBasedAndFollowThePlan() {
        val full = SetupWizardPlan(
            playbackModeName = "STREAMLINED",
            socialEnabled = true,
            offerSocialIdentity = true,
        )
        assertEquals(1, setupStepPosition(SetupStep.Welcome, full))
        assertEquals(9, setupStepPosition(SetupStep.Done, full))

        // Classic, addons already installed, social off: the three droppable steps all gone.
        val lean = SetupWizardPlan(offerSources = false, playbackModeName = "CLASSIC")
        assertEquals(6, setupWizardSteps(lean).size)
        assertEquals(6, setupStepPosition(SetupStep.Done, lean))
    }

    @Test
    fun aStepOutsideThePlanHasNoPosition() {
        assertNull(setupStepPosition(SetupStep.Sources, SetupWizardPlan(offerSources = false)))
        assertNull(setupStepPosition(SetupStep.PlaybackSetup, SetupWizardPlan(playbackModeName = "CLASSIC")))
        assertNull(setupStepPosition(SetupStep.SocialIdentity, SetupWizardPlan(socialEnabled = false)))
    }

    @Test
    fun onlyTheLastStepOfTheActualSequenceCompletes() {
        everyPlan.forEach { plan ->
            assertTrue(isFinalSetupStep(SetupStep.Done, plan), "Done must complete in $plan")
            assertFalse(isFinalSetupStep(SetupStep.Theme, plan), "Theme must not complete in $plan")
        }
    }

    @Test
    fun everyStepInEveryPlanReachesTheEnd() {
        // The property that matters more than any single case above: from any starting step,
        // in any plan, walking `nextSetupStep` terminates at the plan's final step. A wizard
        // that can be entered at a step it cannot leave is the failure this whole file exists
        // to make impossible.
        everyPlan.forEach { plan ->
            SetupStep.entries.forEach { start ->
                var current = start
                var hops = 0
                while (!isFinalSetupStep(current, plan)) {
                    val next = nextSetupStep(current, plan)
                    assertTrue(next != null, "$start stranded at $current in $plan")
                    current = next
                    hops++
                    assertTrue(hops <= SetupStep.entries.size, "$start looped in $plan")
                }
            }
        }
    }

    @Test
    fun everyStepInEveryPlanReachesTheStart() {
        // The same property backwards. Back is the direction a user is most likely to press
        // repeatedly, and the one where a dropped step used to walk into a sequence the run
        // never showed.
        everyPlan.forEach { plan ->
            SetupStep.entries.forEach { start ->
                var current = start
                var hops = 0
                while (current != SetupStep.Welcome) {
                    val previous = previousSetupStep(current, plan)
                    assertTrue(previous != null, "$start stranded at $current in $plan")
                    current = previous
                    hops++
                    assertTrue(hops <= SetupStep.entries.size, "$start looped back in $plan")
                }
            }
        }
    }

    // --- the social-preference migration rule --------------------------------------------

    @Test
    fun anExplicitAnswerAlwaysWins() {
        // Including over a cached identity and a positive probe: a user who turned social off
        // must stay off, however much social history the profile carries.
        listOf(true, false).forEach { cached ->
            SocialIdentityProbe.entries.forEach { probe ->
                assertTrue(resolveSocialFeaturesEnabled(true, cached, probe), "stored=true $cached $probe")
                assertFalse(resolveSocialFeaturesEnabled(false, cached, probe), "stored=false $cached $probe")
            }
        }
    }

    @Test
    fun anEstablishedSocialUserOnThisMachineKeepsSocial() {
        // The outcome this rule exists to prevent is a new boolean defaulting false and hiding
        // an existing identity, friend list and activity.
        SocialIdentityProbe.entries.forEach { probe ->
            assertTrue(resolveSocialFeaturesEnabled(null, hasCachedIdentity = true, probe = probe), "$probe")
        }
    }

    @Test
    fun aCacheColdExistingUserIsRescuedByTheProbe() {
        // A second install, a cleared cache or a fresh data root: a real backend identity that
        // looks locally new. Without the probe this user would silently lose social.
        assertTrue(
            resolveSocialFeaturesEnabled(
                stored = null,
                hasCachedIdentity = false,
                probe = SocialIdentityProbe.Present,
            ),
        )
    }

    @Test
    fun anUnanswerableProbeFallsBackToOff() {
        // Signed out, offline, or the call failed. Off is the safe behaviour - and the caller
        // must not write it down as though the user had chosen it.
        listOf(SocialIdentityProbe.NotRun, SocialIdentityProbe.Absent, SocialIdentityProbe.Indeterminate)
            .forEach { probe ->
                assertFalse(
                    resolveSocialFeaturesEnabled(stored = null, hasCachedIdentity = false, probe = probe),
                    "$probe",
                )
            }
    }
}
