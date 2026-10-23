package com.nuvio.app.promo

import androidx.compose.foundation.Image
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
        recommendation("r1", people.priya, c.bigBuckBunny, "the funniest 10 minutes you'll watch this week", 40),
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
        println("WhatsNewArtHarness: ${dir.absolutePath}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
