package com.nuvio.app.promo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import com.nuvio.app.features.whatsnew.ChangelogAction
import com.nuvio.app.features.whatsnew.ChangelogPlatform
import com.nuvio.app.features.whatsnew.WhatsNewCategory
import com.nuvio.app.features.whatsnew.WhatsNewContent
import com.nuvio.app.features.whatsnew.WhatsNewEventUi
import com.nuvio.app.features.whatsnew.WhatsNewItem
import com.nuvio.app.features.whatsnew.WhatsNewSection
import com.nuvio.app.features.whatsnew.WhatsNewVersion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.features.social.LocalSocialTitleArtSource
import com.nuvio.app.features.social.SocialArtRef
import com.nuvio.app.features.social.SocialCapabilities
import com.nuvio.app.features.social.SocialFeed
import com.nuvio.app.features.social.SocialFeedActions
import com.nuvio.app.features.social.SocialFeedModel
import com.nuvio.app.features.social.SocialInboxEvent
import com.nuvio.app.features.social.SocialInboxPayload
import com.nuvio.app.features.social.SocialNavigationTarget
import com.nuvio.app.features.social.SocialNotification
import com.nuvio.app.features.social.SocialNotificationAction
import com.nuvio.app.features.social.SocialNotificationKind
import com.nuvio.app.features.social.SocialOverlay
import com.nuvio.app.features.social.SocialPopup
import com.nuvio.app.features.social.SocialPopupCard
import com.nuvio.app.features.social.SocialPopupKind
import com.nuvio.app.features.social.SocialProfileSummary
import com.nuvio.app.features.social.SocialTitleArt
import com.nuvio.app.features.social.SocialTitleArtSource
import com.nuvio.app.features.social.SocialUiState
import com.nuvio.app.features.social.friendActivityTimeline
import com.nuvio.app.features.social.groupFriendActivity
import com.nuvio.app.features.social.watchingNowJoinAffordance
import com.nuvio.app.features.social.OutgoingJoinRequestState
import com.nuvio.app.features.watchparty.PartyContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_icon_original_transparent
import org.jetbrains.compose.resources.painterResource
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The pictures for the Social V2 What's New cards (2026-10-23), at the card's 2:1 shape.
 *
 * Every scene is the production composable on the promo catalogue's real art; only the Windows
 * taskbar under the tray pop-ups is drawn here. Skips itself without `Nuvio Z/promo/assets/app-art`.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*WhatsNewArtHarness" --rerun
 * # PNGs land in Nuvio Z/promo/renders/whatsnew/
 * ```
 */
class WhatsNewArtHarness {

    private val promo = PromoRenderHarness()
    private val c = PromoCatalog
    private val people = PromoPeople

    /** Answers every promo title's art by its `type:id`, so Social draws backdrops with no network. */
    private object PromoTitleArt : SocialTitleArtSource {
        override val art: StateFlow<Map<String, SocialTitleArt>> = MutableStateFlow(
            PromoCatalog.all.associate { t ->
                "movie:${t.id}" to SocialTitleArt(
                    name = t.name,
                    background = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) }
                        ?: PromoArt.poster(t.slug),
                )
            },
        )
        override fun request(contentType: String, contentId: String) = Unit
    }

    private fun iso(minutesAgo: Int) = java.time.Instant.ofEpochMilli(promo.nowMs - minutesAgo * 60_000L).toString()

    private fun recommendation(id: String, who: SocialProfileSummary, t: PromoTitle, note: String?, minutesAgo: Int) =
        SocialInboxEvent(
            id = id, kind = SocialInboxEvent.Recommendation, actor = who, createdAt = iso(minutesAgo),
            payload = SocialInboxPayload(
                contentId = t.id, contentType = "movie", title = t.name, note = note,
                poster = PromoArt.poster(t.slug),
                background = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) },
            ),
        )

    private val recommendations = listOf(
        recommendation("r1", people.priya, c.bigBuckBunny, "trust me on this one", 40),
        recommendation("r2", people.theo, c.bigBuckBunny, null, 90),
        recommendation("r3", people.ines, c.tearsOfSteel, "12 minutes, watch it tonight", 300),
        recommendation("r4", people.jonah, c.elephantsDream, null, 1500),
    )

    private val notices = listOf(
        SocialNotification(
            id = "party_invite:1", kind = SocialNotificationKind.PartyInvitation, actor = people.theo,
            createdAt = iso(2), state = "pending", partyId = "party-sintel",
            availableActions = setOf(SocialNotificationAction.Join),
            contentSummary = PartyContent(c.sintel.id, "movie", c.sintel.id, c.sintel.name, poster = PromoArt.poster(c.sintel.slug)),
        ),
        SocialNotification(
            id = "friend_request:1", kind = SocialNotificationKind.FriendRequest, actor = PromoPeople.person("Alex", "alexr", "#F4511E"),
            createdAt = iso(25), state = "pending",
            availableActions = setOf(SocialNotificationAction.Accept, SocialNotificationAction.Decline),
        ),
        SocialNotification(
            id = "join_request:1", kind = SocialNotificationKind.WatchingNowJoinRequest, actor = people.sam,
            createdAt = iso(70), state = "pending",
            availableActions = setOf(SocialNotificationAction.Accept, SocialNotificationAction.Decline),
            contentSummary = PartyContent(c.charge.id, "movie", c.charge.id, c.charge.name, poster = PromoArt.poster(c.charge.slug)),
        ),
    )

    private val inboxEvents = listOf(
        SocialInboxEvent(
            id = "e-watch", kind = SocialInboxEvent.FriendWatching, actor = people.priya, createdAt = iso(180), readAt = iso(170),
            payload = SocialInboxPayload(contentId = c.bigBuckBunny.id, contentType = "movie", title = c.bigBuckBunny.name),
        ),
        SocialInboxEvent(id = "e-acc", kind = SocialInboxEvent.FriendAccepted, actor = people.jonah, createdAt = iso(1400), readAt = iso(1390)),
    )

    private fun state(inbox: List<SocialInboxEvent> = emptyList(), notifications: List<SocialNotification> = emptyList()) = SocialUiState(
        capabilities = SocialCapabilities(socialEnabled = true, watchPartyEnabled = true, partyContractVersion = 2),
        socialV2Backend = true,
        activeProfileId = people.maya.profileId,
        me = people.maya,
        friends = people.friends,
        watchingNow = promo.watchingNow,
        activity = promo.activity,
        inbox = inbox,
        notifications = notifications,
    )

    @Composable
    private fun Feed(state: SocialUiState, overlay: SocialOverlay? = null) {
        CompositionLocalProvider(LocalSocialTitleArtSource provides PromoTitleArt) {
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
                    actions = SocialFeedActions(),
                    listState = rememberLazyListState(),
                )
            }
        }
    }

    private fun popup(key: String, kind: SocialPopupKind, who: SocialProfileSummary, line: String, title: String, meta: String?, t: PromoTitle?) =
        SocialPopup(
            key, kind, who, line, title, meta,
            SocialArtRef(
                title = title, contentType = null, contentId = null,
                poster = t?.let { PromoArt.poster(it.slug) },
                landscape = listOfNotNull(t?.takeIf { PromoArt.has("backdrop", it.slug) }?.let { PromoArt.backdrop(it.slug) }),
            ),
            if (kind == SocialPopupKind.FriendRequest) SocialNavigationTarget.FriendRequests else SocialNavigationTarget.Inbox,
        )

    /** A Windows desktop corner: wallpaper, the corner pop-ups, and the taskbar with the tray and clock. */
    @Composable
    private fun TrayCorner() {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
            AsyncImage(
                model = PromoArt.backdrop(c.elephantsDream.slug),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0x99000000), Color(0x33000000), Color(0x88000000)))))
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 48.dp + 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SocialPopupCard(popup("w", SocialPopupKind.FriendWatching, people.theo, "is watching", c.sintel.name, "Started just now", c.sintel))
                SocialPopupCard(popup("i", SocialPopupKind.PartyInvite, people.priya, "invited you to watch", c.tearsOfSteel.name, "Watch party", c.tearsOfSteel))
                SocialPopupCard(popup("rec", SocialPopupKind.Recommendation, people.ines, "recommended", c.charge.name, "\"4 minutes, watch it tonight\"", c.charge))
            }
            Taskbar(Modifier.align(Alignment.BottomCenter))
        }
    }

    @Composable
    private fun Taskbar(modifier: Modifier) {
        Row(
            modifier.fillMaxWidth().height(48.dp).background(Color(0xE61C1F26)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            // Neutral tray glyphs either side of Nuvio Z's, the way the notification area reads.
            listOf(0.55f, 0.4f).forEach {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = it)))
                Box(Modifier.width(14.dp))
            }
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(Res.drawable.app_icon_original_transparent), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.width(14.dp))
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)))
            Box(Modifier.width(18.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("21:40", color = Color.White, fontSize = 12.sp)
                Text("23/10/2026", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
    }

    /** One panel of the hero: a rendered 1600x800 scene, cut from [cropLeftDp] (at 800dp wide). */
    private class HeroSlice(file: File, val cropLeftDp: Dp) {
        val bitmap: ImageBitmap = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    }

    /**
     * The update's hero (2026-10-23): one 2:1 picture cut into three panels by two parallel diagonal
     * slashes. Each panel shows its scene from [HeroSlice.cropLeftDp], lined up with the panel's left edge.
     */
    private class Hero(private val slices: List<HeroSlice>) {
        private val width = 800.dp
        private val height = 400.dp
        /** How far each slash leans across the picture's height. */
        private val slant = 50.dp
        private val gap = 4.dp

        @Composable
        fun Draw() {
            // The gaps show this through: light enough to read as a cut between two dark panels.
            Box(Modifier.fillMaxSize().background(Color(0xFF4A4A55))) {
                val third = width / slices.size
                slices.forEachIndexed { i, slice ->
                    val first = i == 0
                    val last = i == slices.lastIndex
                    // Each slash runs from (x + slant/2) at the top to (x - slant/2) at the bottom.
                    val left = third * i
                    val right = third * (i + 1)
                    val boxLeft = if (first) 0.dp else left - slant / 2
                    val shape = GenericShape { size, _ ->
                        val px = size.width / width.value
                        fun x(d: Dp) = d.value * px
                        val inset = x(gap / 2)
                        if (first) moveTo(0f, 0f) else moveTo(x(left + slant / 2) + inset, 0f)
                        if (last) {
                            lineTo(size.width, 0f)
                            lineTo(size.width, size.height)
                        } else {
                            lineTo(x(right + slant / 2) - inset, 0f)
                            lineTo(x(right - slant / 2) - inset, size.height)
                        }
                        if (first) lineTo(0f, size.height) else lineTo(x(left - slant / 2) + inset, size.height)
                        close()
                    }
                    Box(Modifier.fillMaxSize().clip(shape)) {
                        Image(
                            bitmap = slice.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.offset(x = boxLeft - slice.cropLeftDp).requiredSize(width, height),
                        )
                    }
                }
            }
        }
    }

    /** The What's New dialog as the release would show it: the hero, then every entry as a line. */
    @Composable
    private fun WhatsNewExample(hero: ImageBitmap) {
        val all = listOf(ChangelogPlatform.DESKTOP, ChangelogPlatform.ANDROID, ChangelogPlatform.IOS)
        val windows = listOf(ChangelogPlatform.DESKTOP)
        val event = WhatsNewEventUi(
            seq = 5,
            date = "Oct 25, 2026",
            summary = "Social got better: recommend titles to friends, one Inbox for everything, and notifications that reach you even when the app is closed.",
            versions = listOf(
                WhatsNewVersion(ChangelogPlatform.DESKTOP, "0.1.31-alpha-z1", isViewer = true),
                WhatsNewVersion(ChangelogPlatform.ANDROID, "0.6.2-z1", isViewer = false),
                WhatsNewVersion(ChangelogPlatform.IOS, "0.6.2-z1", isViewer = false),
            ),
            sections = listOf(
                WhatsNewSection(
                    WhatsNewCategory.NewFeatures,
                    listOf(
                        WhatsNewItem("Recommend to friends", "You can now send a movie or show to friends with a note, and you'll know when they start watching it.", platforms = all),
                        WhatsNewItem("Inbox and notifications", "Friend requests, invites and recommendations land in one Inbox, and a notification tells you as they arrive.", platforms = all),
                        WhatsNewItem("Runs in the tray", "Nuvio Z can now run in the tray, so notifications reach you even when the app is closed.", platforms = windows),
                    ),
                ),
                WhatsNewSection(
                    WhatsNewCategory.Improvements,
                    listOf(
                        WhatsNewItem("A better Social tab", "Friends who are watching show as big cards, and each friend has a profile.", platforms = all),
                        WhatsNewItem("Language and subtitles", "Subtitles can follow different rules for titles in your language and in other languages.", ChangelogAction.REVIEW_SETTINGS, all),
                        WhatsNewItem("Social UI improvements", "Invite several friends at once, and see who's invited or waiting in the lobby.", platforms = all),
                        WhatsNewItem("Updated to Nuvio 0.6.2 / 0.1.31", "Jellyfin and Emby libraries, MDBList, and more.", platforms = all),
                    ),
                ),
                WhatsNewSection(
                    WhatsNewCategory.BugFixes,
                    listOf(
                        WhatsNewItem("Subtitles you picked stay in the same language on the next episode", "", platforms = all),
                        WhatsNewItem("A notice when you've been signed out, with a Sign in button", "", platforms = all),
                        WhatsNewItem("Social pop-ups no longer stop after an hour idle", "", platforms = windows),
                    ),
                ),
            ),
            otherDevices = emptyList(),
            image = "hero.jpg",
        )
        Box(Modifier.fillMaxSize().background(Color(0xFF050506)), contentAlignment = Alignment.Center) {
            WhatsNewContent(
                versionName = "0.1.31-alpha-z1",
                events = listOf(event),
                onContinue = {},
                showHistory = false,
                onAction = {},
                imageContent = {
                    Image(hero, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2f))
                },
            )
        }
    }

    @Test
    fun renderWhatsNewArt() {
        if (!PromoArt.available) {
            println("WhatsNewArtHarness: ${PromoArt.promoRoot}/assets/app-art is missing; skipping.")
            return
        }
        val dir = File(promo.outDir, "whatsnew").apply { mkdirs() }
        promo.seedProfile()
        val failures = mutableListOf<String>()
        // 2:1, the card's own shape, always 1600x800 px: [widthDp] picks the layout, the density fills it.
        fun art(name: String, widthDp: Int, content: @Composable () -> Unit) =
            promo.render("whatsnew/$name", widthDp, widthDp / 2, PromoRenderHarness.Kind.Desktop, 1600f / widthDp, failures, content = content)
        // At or above 1040dp Social is the dashboard (Friends rail, overlays as a side panel); below it, a page.
        art("social", 1080) { Feed(state()) }
        art("recommend", 1080) { Feed(state(inbox = recommendations), SocialOverlay.Recommended) }
        art("inbox", 1000) { Feed(state(inbox = inboxEvents, notifications = notices), SocialOverlay.Inbox) }
        art("tray", 1100) { TrayCorner() }
        if (failures.isEmpty()) {
            val hero = Hero(
                listOf(
                    HeroSlice(File(dir, "social.png"), cropLeftDp = 0.dp),
                    HeroSlice(File(dir, "recommend.png"), cropLeftDp = 402.dp),
                    HeroSlice(File(dir, "tray.png"), cropLeftDp = 460.dp),
                ),
            )
            art("hero", 800) { hero.Draw() }
            val heroBitmap = org.jetbrains.skia.Image.makeFromEncoded(File(dir, "hero.png").readBytes()).toComposeImageBitmap()
            promo.render("whatsnew/example-desktop", 1280, 820, PromoRenderHarness.Kind.Desktop, 1.5f, failures) {
                WhatsNewExample(heroBitmap)
            }
            promo.render("whatsnew/example-phone", 411, 914, PromoRenderHarness.Kind.Phone, 3f, failures) {
                WhatsNewExample(heroBitmap)
            }
        }
        println("WhatsNewArtHarness: ${dir.absolutePath}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
