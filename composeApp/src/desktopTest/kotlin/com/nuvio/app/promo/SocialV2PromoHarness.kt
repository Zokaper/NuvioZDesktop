package com.nuvio.app.promo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.social.FriendHistoryPage
import com.nuvio.app.features.social.LocalSocialTitleArtSource
import com.nuvio.app.features.social.OutgoingJoinRequestState
import com.nuvio.app.features.social.SocialArtRef
import com.nuvio.app.features.social.SocialCapabilities
import com.nuvio.app.features.social.SocialFeed
import com.nuvio.app.features.social.SocialFeedActions
import com.nuvio.app.features.social.SocialFeedModel
import com.nuvio.app.features.social.SocialInboxEvent
import com.nuvio.app.features.social.SocialInboxPayload
import com.nuvio.app.features.social.SocialPlaybackState
import com.nuvio.app.features.social.SocialPopupCard
import com.nuvio.app.features.social.SocialProfileSummary
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.SocialTitleArt
import com.nuvio.app.features.social.SocialTitleArtSource
import com.nuvio.app.features.social.SocialUiState
import com.nuvio.app.features.social.friendActivityTimeline
import com.nuvio.app.features.social.groupFriendActivity
import com.nuvio.app.features.social.toRecommendationPopup
import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.social.WatchingNowItem
import com.nuvio.app.features.social.watchingNowJoinAffordance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The Social V2 launch promo (2026-10-23): Theo recommends The Godfather to Maya with the note
 * "qualityslop", Maya gets the pop-up, opens Recommended to you, and the title's page says who sent it.
 *
 * Theo's side is one continuous take of the production details page and Recommend sheet, driven
 * through the scene's own pointer and key pipeline (the same one a window uses): the three-dot row
 * opens, Recommend opens the sheet, Maya is ticked, the note is typed a key at a time. The cursor path
 * and every click go to `events.json` beside the frames, so the edit draws the pointer where the
 * scene was clicked. Skips itself without `Nuvio Z/promo/assets/app-art`.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*SocialV2PromoHarness" --rerun
 * # frames and stills land in Nuvio Z/promo/renders/social-v2-promo/
 * ```
 */
class SocialV2PromoHarness {

    private val promo = PromoRenderHarness()
    private val people = PromoPeople
    // Draft 5 (2026-10-24 review: the open movies "look eh", "show off the profile page"): real
    // catalogue titles throughout, Theo has a banner, and his profile page gets its own beat. Earlier
    // drafts' frames stay in renders/social-v2-promo/; this draft writes renders/social-v2-promo-d5/.
    private val real = PromoRealCatalog
    private val godfather = real.godfather
    private val titles = real.all
    private val note = "qualityslop"

    /** Theo as Maya's friend list has him: with the banner his profile page shows. */
    private val theo = people.theo.copy(bannerUrl = PromoArt.backdrop(real.bladeRunner.slug))
    private val mayaFriends = people.friends.map { if (it.profileId == theo.profileId) theo else it }

    private val outDir = File(promo.outDir, "social-v2-promo-d5")

    /** Every title's art by its `type:id`, so Social draws backdrops with no network. */
    private inner class TitleArt : SocialTitleArtSource {
        override val art: StateFlow<Map<String, SocialTitleArt>> = MutableStateFlow(
            titles.associate { t ->
                "movie:${t.id}" to SocialTitleArt(
                    name = t.name,
                    background = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) } ?: PromoArt.poster(t.slug),
                    releaseInfo = t.year,
                )
            },
        )
        override fun request(contentType: String, contentId: String) = Unit
    }

    private fun iso(minutesAgo: Int) = java.time.Instant.ofEpochMilli(promo.nowMs - minutesAgo * 60_000L).toString()

    private fun recommendation(id: String, who: SocialProfileSummary, t: PromoTitle, note: String?, minutesAgo: Int, read: Boolean) =
        SocialInboxEvent(
            id = id, kind = SocialInboxEvent.Recommendation, actor = who, createdAt = iso(minutesAgo),
            readAt = if (read) iso(minutesAgo - 1) else null,
            payload = SocialInboxPayload(
                contentId = t.id, contentType = "movie", title = t.name, note = note,
                poster = PromoArt.poster(t.slug),
                background = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) },
            ),
        )

    private val theosPick = recommendation("r-godfather", people.theo, godfather, note, 0, read = false)

    private val mayaInbox = listOf(
        theosPick,
        recommendation("r-parasite", people.priya, real.parasite, "pure qualityslop, insists upon itself", 300, read = true),
        recommendation("r-whiplash", people.ines, real.whiplash, "watch it tonight, thank me later", 1500, read = true),
    )

    private fun watching(
        p: SocialProfileSummary, t: PromoTitle, positionMin: Int, policy: WatchJoinPolicy, state: SocialPlaybackState,
        partyId: String? = null, host: SocialProfileSummary? = null, members: Int? = null,
    ) = WatchingNowItem(
        profile = p, contentId = t.id, contentType = "movie", videoId = t.id, title = t.name,
        poster = PromoArt.poster(t.slug), background = PromoArt.backdrop(t.slug),
        sessionId = "s-${p.handle}", positionMs = positionMin * 60_000L, durationMs = t.durationMs,
        effectiveJoinPolicy = policy, state = state, heartbeatAt = iso(0),
        partyId = partyId, partyHostProfileId = host?.profileId, partyMemberCount = members,
    )

    private val watchingNow =
        listOf(watching(people.theo, real.duneTwo, 41, WatchJoinPolicy.direct, SocialPlaybackState.playing)) +
            listOf(people.jonah, people.priya, people.sam).map {
                watching(it, real.interstellar, 72, WatchJoinPolicy.approval, SocialPlaybackState.playing, "party-interstellar", people.jonah, 3)
            } +
            watching(people.ines, real.laLaLand, 67, WatchJoinPolicy.approval, SocialPlaybackState.paused)

    private val activity = listOf(
        promo.run("a1", people.priya, real.poorThings, 2),
        promo.run("a2", people.theo, real.oppenheimer, 3),
        promo.run("a3", people.sam, real.oppenheimer, 5),
        promo.run("a4", people.ines, real.pastLives, 20),
        promo.run("a5", people.jonah, real.bladeRunner, 26),
        promo.run("a6", people.theo, real.arrival, 30),
        promo.run("a7", people.priya, real.madMax, 50),
        promo.run("a8", people.sam, real.topGun, 75),
        promo.run("a9", people.ines, real.knivesOut, 100),
    )

    /** What Theo's profile page lists: this month, then the start of last month. */
    private val theoHistory = FriendHistoryPage(
        listOf(
            real.oppenheimer to 3, real.arrival to 30, real.inception to 60, real.theBatman to 90, real.interstellar to 130,
            real.dune to 170, real.bladeRunner to 220, real.pulpFiction to 300, real.goodfellas to 380, real.theShining to 460,
            real.madMax to 600, real.knivesOut to 660, real.godfather2 to 720,
        ).mapIndexed { i, (t, h) -> promo.run("h$i", people.theo, t, h) },
        null,
    )

    private fun state(me: SocialProfileSummary, friends: List<SocialProfileSummary>, inbox: List<SocialInboxEvent>) = SocialUiState(
        capabilities = SocialCapabilities(socialEnabled = true, watchPartyEnabled = true, partyContractVersion = 2),
        socialV2Backend = true,
        activeProfileId = me.profileId,
        me = me,
        friends = friends,
        watchingNow = watchingNow,
        activity = activity,
        inbox = inbox,
        // So Theo's profile page shows the three privacy rows, each summarised a different way.
        audiences = com.nuvio.app.features.social.SocialAudiences(
            recent = com.nuvio.app.features.social.SocialAudience(com.nuvio.app.features.social.SocialAudienceMode.Except, except = listOf(people.sam.profileId)),
            notify = com.nuvio.app.features.social.SocialAudience(com.nuvio.app.features.social.SocialAudienceMode.Only, only = listOf(people.theo.profileId, people.priya.profileId)),
        ),
    )

    /** What `SocialRepository` answers the details page with: Recommend needs friends and the V2 backend. */
    private fun seedSocial(state: SocialUiState) {
        val f = SocialRepository.javaClass.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(SocialRepository) as MutableStateFlow<SocialUiState>).value = state
    }

    private fun seedGodfather() {
        // One embedded stream, so the page offers Play as it would with a source installed.
        val stream = promo.stream("Open Movie Archive 1080p", "The.Godfather.1972.1080p.mkv", 9_800_000_000L)
        promo.seedMeta(
            godfather.details(moreLikeThis = listOf(real.godfather2, real.goodfellas, real.pulpFiction, real.theShining, real.knivesOut).map { it.preview() })
                .copy(videos = listOf(com.nuvio.app.features.details.MetaVideo(id = godfather.id, title = godfather.name, streams = listOf(stream)))),
        )
    }

    @Composable
    private fun Details() {
        MetaDetailsScreen(
            type = "movie",
            id = godfather.id,
            onBack = {},
            onWatchTogether = {},
            onPlay = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            onPlayManually = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
        )
    }

    private fun popup(key: String, kind: com.nuvio.app.features.social.SocialPopupKind, who: SocialProfileSummary, line: String, t: PromoTitle, meta: String) =
        com.nuvio.app.features.social.SocialPopup(
            key, kind, who, line, t.name, meta,
            SocialArtRef(
                title = t.name, contentType = "movie", contentId = t.id, poster = PromoArt.poster(t.slug),
                landscape = listOfNotNull(PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) }),
            ),
            com.nuvio.app.features.social.SocialNavigationTarget.Inbox,
        )

    private fun notices() = listOf(
        com.nuvio.app.features.social.SocialNotification(
            id = "party_invite:1", kind = com.nuvio.app.features.social.SocialNotificationKind.PartyInvitation, actor = people.priya,
            createdAt = iso(2), state = "pending", partyId = "party-tos",
            availableActions = setOf(com.nuvio.app.features.social.SocialNotificationAction.Join),
            contentSummary = com.nuvio.app.features.watchparty.PartyContent(
                real.duneTwo.id, "movie", real.duneTwo.id, real.duneTwo.name,
                poster = PromoArt.poster(real.duneTwo.slug),
            ),
        ),
        com.nuvio.app.features.social.SocialNotification(
            id = "friend_request:1", kind = com.nuvio.app.features.social.SocialNotificationKind.FriendRequest,
            actor = PromoPeople.person("Alex", "alexr", "#F4511E"), createdAt = iso(25), state = "pending",
            availableActions = setOf(com.nuvio.app.features.social.SocialNotificationAction.Accept, com.nuvio.app.features.social.SocialNotificationAction.Decline),
        ),
        com.nuvio.app.features.social.SocialNotification(
            id = "join_request:1", kind = com.nuvio.app.features.social.SocialNotificationKind.WatchingNowJoinRequest, actor = people.sam,
            createdAt = iso(70), state = "pending",
            availableActions = setOf(com.nuvio.app.features.social.SocialNotificationAction.Accept, com.nuvio.app.features.social.SocialNotificationAction.Decline),
            contentSummary = com.nuvio.app.features.watchparty.PartyContent(
                real.madMax.id, "movie", real.madMax.id, real.madMax.name, poster = PromoArt.poster(real.madMax.slug),
            ),
        ),
    )

    /** What Theo's profile sheet says Maya and he have watched together. */
    private val togetherWithTheo = com.nuvio.app.features.social.SocialTogetherStats(
        seconds = 14 * 3600L + 20 * 60L,
        parties = 9,
        titles = listOf(real.interstellar to 4, real.inception to 3, real.arrival to 2).map { (t, n) ->
            com.nuvio.app.features.social.SocialTogetherTitle(t.name, t.id, "movie", PromoArt.poster(t.slug), n)
        },
    )

    @Composable
    private fun MayaSocial(state: SocialUiState, overlay: com.nuvio.app.features.social.SocialOverlay? = null) {
        CompositionLocalProvider(LocalSocialTitleArtSource provides TitleArt()) {
            promo.DesktopShell(AppScreenTab.Social) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SocialFeed(
                        model = SocialFeedModel(
                            state = state,
                            handle = "maya",
                            activityGroups = groupFriendActivity(state.activity),
                            activityTimeline = friendActivityTimeline(state.activity, promo.nowMs),
                            activityNowMs = promo.nowMs,
                            joinAffordance = { item -> watchingNowJoinAffordance(item, OutgoingJoinRequestState.Idle, null) },
                            initialOverlay = overlay,
                        ),
                        actions = SocialFeedActions(
                            onLoadTogetherStats = { id -> togetherWithTheo.takeIf { id == theo.profileId } },
                            onLoadFriendHistory = { id, _ -> Result.success(if (id == theo.profileId) theoHistory else FriendHistoryPage(emptyList(), null)) },
                        ),
                        listState = rememberLazyListState(),
                    )
                }
            }
        }
    }

    // --- a scripted take ----------------------------------------------------------------------

    /** Where to point: a semantics node found by its text or content description. */
    private class Target(val label: String, val match: (SemanticsNode) -> Boolean, val nudge: Offset = Offset.Zero)

    private fun text(t: String, nudge: Offset = Offset.Zero) =
        Target("text=$t", { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t } == true }, nudge)

    private fun textStarting(t: String) =
        Target("text^=$t", { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(t) } == true })

    private fun described(d: String) =
        Target("desc=$d", { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == d } == true })

    private sealed interface Step { val frame: Int }
    /** Glide the pointer to [target] over [frames], eased. */
    private data class Move(override val frame: Int, val target: Target, val frames: Int) : Step
    /** Glide the pointer to a point in scene pixels, for a surface with no one label to aim at. */
    private data class Glide(override val frame: Int, val to: Offset, val frames: Int) : Step
    private data class Click(override val frame: Int, val release: Boolean = true) : Step
    private data class Type(override val frame: Int, val text: String, val framesPerChar: Int) : Step
    /** Turn the mouse wheel by [delta] wheel units over [frames], a little each frame, eased. */
    private data class Scroll(override val frame: Int, val delta: Float, val frames: Int) : Step

    private class Take(val name: String, val frames: Int, val start: Offset, val steps: List<Step>)

    private fun ImageComposeScene.find(target: Target): Rect {
        val all = semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.flatten() }
        val node = all.lastOrNull(target.match)
            ?: error("no node for ${target.label}; saw ${all.mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }.flatten().map { it.text }}")
        return node.boundsInWindow
    }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }

    private val awtSource = object : java.awt.Component() {}

    /**
     * Records [take] as JPEG frames at 60 fps after a settle, sending each step's input at its frame,
     * and writes the pointer's position per frame plus the clicks to `events.json`.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun record(take: Take, widthDp: Int, heightDp: Int, density: Float, failures: MutableList<String>, content: @Composable () -> Unit) {
        runCatching {
            val clock = VirtualClockDispatcher()
            val scene = ImageComposeScene(
                width = (widthDp * density).toInt(), height = (heightDp * density).toInt(),
                density = Density(density), coroutineContext = clock,
            ) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false, desktopUiScale = desktopUiScaleForWindow(widthDp.toFloat(), heightDp.toFloat())) {
                    WithPromoArt { content() }
                }
            }
            val dir = File(outDir, take.name).apply { mkdirs() }
            val frameNanos = 16_666_667L
            var t = 0L
            fun frame(): org.jetbrains.skia.Image {
                clock.advanceTo(t / 1_000_000L)
                val image = scene.render(t)
                clock.pump()
                t += frameNanos
                return image
            }
            try {
                repeat(60) { frame() }
                var pointer = take.start
                var moveFrom = pointer
                var moveTo = pointer
                var moveStart = 0
                var moveFrames = 0
                var pressed = false
                var typing: Type? = null
                var scrolling: Scroll? = null
                val pointerTrack = StringBuilder()
                val clicks = mutableListOf<String>()
                scene.sendPointerEvent(PointerEventType.Move, pointer, timeMillis = t / 1_000_000L)
                for (i in 0 until take.frames) {
                    val ms = t / 1_000_000L
                    take.steps.filter { it.frame == i }.forEach { step ->
                        when (step) {
                            is Move -> {
                                moveFrom = pointer
                                moveTo = scene.find(step.target).center + step.target.nudge * density
                                moveStart = i
                                moveFrames = step.frames
                            }
                            is Glide -> {
                                moveFrom = pointer
                                moveTo = step.to
                                moveStart = i
                                moveFrames = step.frames
                            }
                            is Click -> {
                                scene.sendPointerEvent(PointerEventType.Press, pointer, timeMillis = ms, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                pressed = true
                                clicks += "{\"frame\":$i,\"x\":${pointer.x},\"y\":${pointer.y}}"
                            }
                            is Type -> typing = step
                            is Scroll -> scrolling = step
                        }
                    }
                    if (moveFrames > 0 && i in moveStart..(moveStart + moveFrames)) {
                        val p = ((i - moveStart).toFloat() / moveFrames).coerceIn(0f, 1f)
                        val e = if (p < 0.5f) 4 * p * p * p else 1 - Math.pow((-2.0 * p + 2), 3.0).toFloat() / 2
                        pointer = moveFrom + (moveTo - moveFrom) * e
                        scene.sendPointerEvent(PointerEventType.Move, pointer, timeMillis = ms, buttons = if (pressed) PointerButtons(isPrimaryPressed = true) else null)
                    }
                    // A click is a press and, six frames on, a release, as a hand does it.
                    val lastClick = take.steps.filterIsInstance<Click>().lastOrNull { it.frame <= i }
                    if (pressed && lastClick != null && lastClick.release && i == lastClick.frame + 6) {
                        scene.sendPointerEvent(PointerEventType.Release, pointer, timeMillis = ms, buttons = PointerButtons(), button = PointerButton.Primary)
                        pressed = false
                    }
                    scrolling?.let { sc ->
                        val k = i - sc.frame
                        if (k in 0 until sc.frames) {
                            fun eased(x: Float) = if (x < 0.5f) 2 * x * x else 1 - (-2 * x + 2) * (-2 * x + 2) / 2
                            val d = sc.delta * (eased((k + 1f) / sc.frames) - eased(k.toFloat() / sc.frames))
                            scene.sendPointerEvent(PointerEventType.Scroll, pointer, scrollDelta = Offset(0f, d), timeMillis = ms)
                        }
                    }
                    typing?.let { ty ->
                        val k = i - ty.frame
                        if (k >= 0 && k % ty.framesPerChar == 0 && k / ty.framesPerChar < ty.text.length) {
                            val ch = ty.text[k / ty.framesPerChar]
                            val awt = java.awt.event.KeyEvent(awtSource, java.awt.event.KeyEvent.KEY_TYPED, ms, 0, java.awt.event.KeyEvent.VK_UNDEFINED, ch)
                            scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = ch.code, nativeEvent = awt))
                        }
                    }
                    if (i > 0) pointerTrack.append(',')
                    pointerTrack.append("[${"%.1f".format(java.util.Locale.ROOT, pointer.x)},${"%.1f".format(java.util.Locale.ROOT, pointer.y)}]")
                    val image = frame()
                    val data = image.encodeToData(EncodedImageFormat.JPEG, 92) ?: error("encodeToData returned null")
                    File(dir, "f_%04d.jpg".format(i)).writeBytes(data.bytes)
                }
                File(dir, "events.json").writeText(
                    "{\"fps\":60,\"frames\":${take.frames},\"width\":${scene.let { (widthDp * density).toInt() }},\"height\":${(heightDp * density).toInt()}," +
                        "\"density\":$density,\"pointer\":[$pointerTrack],\"clicks\":[${clicks.joinToString(",")}]}",
                )
            } finally {
                scene.close()
            }
        }.onFailure { error ->
            error.printStackTrace()
            failures += "${take.name}: ${error::class.simpleName}: ${error.message}"
        }
    }

    @Test
    fun renderSocialV2Promo() {
        if (!PromoArt.available || !real.available) {
            println("SocialV2PromoHarness: ${PromoArt.promoRoot}/assets/app-art is missing the real titles (tools/fetch_cinemeta.py); skipping.")
            return
        }
        outDir.mkdirs()
        val only = System.getenv("SV2_ONLY")?.split(',')?.map(String::trim)?.toSet()
        fun wants(name: String) = only == null || name in only
        val failures = mutableListOf<String>()
        val theoFriends = listOf(people.maya, people.ines, people.jonah, people.priya, people.sam)

        // 1. Theo, on The Godfather's page: the three-dot row, Recommend, tick Maya, type the note, press send.
        if (wants("theo")) {
            promo.seedProfile(people.theo)
            seedSocial(state(people.theo, theoFriends, emptyList()))
            seedGodfather()
            val theo = Take(
                name = "theo", frames = 660, start = Offset(1900f, 1250f),
                // Unhurried (2026-10-23 review: draft 2 "really fast"): every move glides, then rests.
                steps = listOf(
                    Move(30, described("More actions"), 50),
                    Click(92),
                    Move(140, described("Recommend"), 45),
                    Click(196),
                    Move(270, text("Maya"), 50),
                    Click(332),
                    Move(380, text("Add a message (optional)"), 40),
                    Click(432),
                    Type(450, note, 8),
                    Move(590, textStarting("Recommend to"), 35),
                    Click(640, release = false),
                ),
            )
            record(theo, 1440, 810, 2f, failures) { Details() }
        }

        // 2. The pop-up on Maya's desktop: the production card from the production event-to-popup mapping.
        if (wants("popup")) {
            promo.render("social-v2-promo/popup", 380, 132, PromoRenderHarness.Kind.Desktop, 4f, failures) {
                CompositionLocalProvider(LocalSocialTitleArtSource provides TitleArt()) {
                    SocialPopupCard(theosPick.toRecommendationPopup())
                }
            }
        }

        // 3. Maya's Social tab: Recommended to you opens from its header button.
        promo.seedProfile(people.maya)
        val maya = state(people.maya, mayaFriends, mayaInbox)
        seedSocial(maya)
        // 3. Draft 5: the pop-up opens Recommended to you, which closes onto the tab - Watching Now and
        // the activity timeline - then Theo's row opens his profile page: the banner, what he has
        // watched this month, and what the two of them have watched together, scrolled through.
        if (wants("maya-social-d5")) {
            val take = Take(
                name = "maya-social-d5", frames = 1290, start = Offset(2200f, 900f),
                steps = listOf(
                    Move(10, textStarting("3 recommended"), 45),
                    Click(60),
                    Move(130, text("The Godfather"), 60),
                    Move(290, described("Close"), 45),
                    Click(345),
                    Move(400, textStarting("Jonah, Priya"), 70),
                    Move(520, text("La La Land"), 60),
                    Scroll(620, 9f, 90),
                    Move(740, text("Watching Dune: Part Two"), 55),
                    Click(800),
                    Glide(900, Offset(1500f, 1150f), 70),
                    Scroll(1010, 8f, 110),
                ),
            )
            record(take, 1440, 810, 2f, failures) { MayaSocial(maya) }
        }

        // 4. The Godfather's page, as Maya sees it: who recommended it, and the note.
        if (wants("maya-details")) {
            seedGodfather()
            promo.render("social-v2-promo/maya-details", 1440, 810, PromoRenderHarness.Kind.Desktop, 3f, failures) { Details() }
        }
        // 5. For the closing montage: the Inbox, and two more pop-ups for the tray stack.
        if (wants("maya-inbox")) {
            val inbox = maya.copy(inbox = mayaInbox.map { it.copy(readAt = it.readAt ?: iso(0)) }, notifications = notices())
            promo.render("social-v2-promo/maya-inbox", 1440, 810, PromoRenderHarness.Kind.Desktop, 2f, failures) {
                MayaSocial(inbox, com.nuvio.app.features.social.SocialOverlay.Inbox)
            }
        }
        if (wants("popups")) {
            fun card(name: String, popup: com.nuvio.app.features.social.SocialPopup) =
                promo.render("social-v2-promo/$name", 380, 132, PromoRenderHarness.Kind.Desktop, 4f, failures) {
                    CompositionLocalProvider(LocalSocialTitleArtSource provides TitleArt()) { SocialPopupCard(popup) }
                }
            card("popup-watching", popup("w", com.nuvio.app.features.social.SocialPopupKind.FriendWatching, people.jonah, "is watching", real.interstellar, "Started just now"))
            card("popup-invite", popup("i", com.nuvio.app.features.social.SocialPopupKind.PartyInvite, people.priya, "invited you to watch", real.duneTwo, "Watch party"))
        }
        println("SocialV2PromoHarness: ${outDir.absolutePath}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * The launch post's still (2026-10-24 review: "more of a still", show off the profile page): each
     * surface at 1:1 on Maya's desktop, for `Nuvio Z/promo/tools/sv2_still.py` to compose.
     *
     * ```
     * ./gradlew :composeApp:desktopTest --tests "*SocialV2PromoHarness.renderLaunchStills" --rerun
     * # renders/social-v2-promo-d5/stills/
     * ```
     */
    @Test
    fun renderLaunchStills() {
        if (!PromoArt.available || !real.available) {
            println("SocialV2PromoHarness: ${PromoArt.promoRoot}/assets/app-art is missing the real titles (tools/fetch_cinemeta.py); skipping.")
            return
        }
        val failures = mutableListOf<String>()
        File(outDir, "stills").mkdirs()
        promo.seedProfile(people.maya)
        val maya = state(people.maya, mayaFriends, mayaInbox.map { it.copy(readAt = it.readAt ?: iso(0)) })
        seedSocial(maya)
        fun still(name: String, content: @Composable () -> Unit) =
            promo.render("social-v2-promo-d5/stills/$name", 1440, 810, PromoRenderHarness.Kind.Desktop, 2f, failures, settleMs = 1500, content = content)
        still("social") { MayaSocial(maya) }
        still("recommended") { MayaSocial(maya, com.nuvio.app.features.social.SocialOverlay.Recommended) }
        still("profile") { MayaSocial(maya, com.nuvio.app.features.social.SocialOverlay.Profile(theo.profileId)) }
        fun card(name: String, popup: com.nuvio.app.features.social.SocialPopup) =
            promo.render("social-v2-promo-d5/stills/$name", 380, 132, PromoRenderHarness.Kind.Desktop, 4f, failures) {
                CompositionLocalProvider(LocalSocialTitleArtSource provides TitleArt()) { SocialPopupCard(popup) }
            }
        card("popup-watching", popup("w", com.nuvio.app.features.social.SocialPopupKind.FriendWatching, people.theo, "is watching", real.duneTwo, "Started just now"))
        card("popup-invite", popup("i", com.nuvio.app.features.social.SocialPopupKind.PartyInvite, people.jonah, "invited you to watch", real.interstellar, "Watch party"))
        // Not Theo's pick: The Godfather's art is near-black, and reads as an empty card at this size.
        card("popup-recommend", mayaInbox.first { it.id == "r-parasite" }.toRecommendationPopup())
        println("SocialV2PromoHarness: ${File(outDir, "stills").absolutePath}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
