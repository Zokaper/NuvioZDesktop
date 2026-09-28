package com.nuvio.app.promo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.LibrarySubDestination
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.downloads.AttentionGrouping
import com.nuvio.app.features.downloads.DownloadActivity
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPresenter
import com.nuvio.app.features.downloads.DownloadQueueGrouping
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadStorageSummary
import com.nuvio.app.features.downloads.DownloadUserPhase
import com.nuvio.app.features.downloads.DownloadsPart
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.downloads.DownloadsWideLayout
import com.nuvio.app.features.downloads.downloadsContentWidth
import com.nuvio.app.features.downloads.downloadsRootContent
import com.nuvio.app.features.library.LibraryTopSwitcher
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the surfaces for the **static screenshot set** (`Nuvio Z/promo/screenshots/`): Discord and
 * store-style stills, beside the trailer and the feature reel. Same rule as [PromoRenderHarness],
 * whose seeding, shells and scenes it reuses: production entry points, only the data replaced.
 *
 * Most scenes are the trailer's surfaces again at the current HEAD and at 2-3x density, into their
 * own folder so the trailer's and the reel's inputs are never overwritten. The new ones:
 * - **iPhone** screens are pushed routes (a title's page), because on iOS 26 an iPhone's root tabs
 *   sit under the native Liquid Glass tab bar, which this host cannot draw. A pushed route hides that
 *   bar on the device too, so what is rendered is what the phone shows. 402 x 874 dp, an iPhone 16/17
 *   Pro, with a 59 dp stand-in for the Dynamic Island's status bar.
 * - **Desktop Downloads**: the Phase 9 two-pane layout (`DownloadsWideLayout`) in the desktop window,
 *   as `DownloadsScreen` lays it out while something is under way.
 *
 * Skips itself unless `Nuvio Z/promo/assets/app-art` exists, so CI never runs it.
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*ScreenshotRenderHarness" --rerun
 * SHOTS_ONLY=iphone-details,desktop-downloads ./gradlew ...   # a subset
 * # PNGs land in Nuvio Z/promo/renders/screenshots/
 * ```
 */
class ScreenshotRenderHarness {

    private val promo = PromoRenderHarness()
    private val c = PromoCatalog
    private val Desktop = PromoRenderHarness.Kind.Desktop
    private val Phone = PromoRenderHarness.Kind.Phone
    private val mb = 1_000_000L

    private fun dl(
        t: PromoTitle,
        status: DownloadStatus,
        total: Long,
        fraction: Float = 1f,
        activity: DownloadActivity? = null,
        position: Long = 0,
        quality: String = "2160p WEB-DL HEVC",
    ) = DownloadItem(
        id = "dl-${t.slug}",
        ownerProfileId = 1,
        contentType = "movie",
        parentMetaId = t.id,
        parentMetaType = "movie",
        videoId = t.id,
        title = t.name,
        poster = PromoArt.poster(t.slug),
        background = PromoArt.backdrop(t.slug).takeIf { PromoArt.has("backdrop", t.slug) },
        logo = PromoArt.logo(t.slug).takeIf { PromoArt.has("logo", t.slug) },
        streamTitle = "${t.name.replace(' ', '.')}.${t.year}.${quality.replace(' ', '.')}",
        providerName = "Open Movie Archive",
        fileName = "${t.slug}.mkv",
        status = status,
        activity = activity,
        downloadedBytes = (total * fraction).toLong(),
        totalBytes = total,
        queuePosition = position,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        localFileUri = if (status == DownloadStatus.Completed) "file:///${t.slug}.mkv" else null,
    )

    /** Two transfers under way, three queued, and a library of finished films with backdrops. */
    private val items = listOf(
        dl(c.sintel, DownloadStatus.Downloading, 3_100 * mb, 0.64f, DownloadActivity.TRANSFERRING, 0),
        dl(c.spring, DownloadStatus.Downloading, 1_450 * mb, 0.31f, DownloadActivity.TRANSFERRING, 1),
        dl(c.tearsOfSteel, DownloadStatus.Queued, 1_900 * mb, 0f, null, 2),
        dl(c.spriteFright, DownloadStatus.Queued, 1_600 * mb, 0f, null, 3),
        dl(c.nightOfTheLivingDead, DownloadStatus.Queued, 2_700 * mb, 0f, null, 4, quality = "1080p BluRay x264"),
        dl(c.bigBuckBunny, DownloadStatus.Completed, 1_280 * mb, quality = "1080p BluRay x264"),
        dl(c.charge, DownloadStatus.Completed, 610 * mb),
        dl(c.elephantsDream, DownloadStatus.Completed, 980 * mb, quality = "1080p BluRay x264"),
        dl(c.metropolis, DownloadStatus.Completed, 4_900 * mb, quality = "1080p BluRay x264"),
        dl(c.theGeneral, DownloadStatus.Completed, 2_200 * mb, quality = "1080p BluRay x264"),
    )

    private fun downloadsContent(libraryColumns: Int): LazyListScope.(DownloadsPart) -> Unit = { part ->
        val attention = AttentionGrouping.group(items, emptyList(), promo.nowMs)
        val unfinished = items.filter {
            it.status != DownloadStatus.Completed && DownloadPresenter.item(it, promo.nowMs).phase != DownloadUserPhase.NEEDS_YOU
        }
        downloadsRootContent(
            uiState = DownloadsUiState(items),
            batches = emptyList(),
            storage = DownloadStorageSummary(usedBytes = items.sumOf { it.downloadedBytes }, freeBytes = 96_000 * mb),
            attention = attention,
            queue = DownloadQueueGrouping.group(unfinished, items, emptyList(), promo.nowMs),
            cleanup = null,
            nowEpochMs = promo.nowMs,
            onOpenDownload = {},
            onOpenShow = { _, _ -> },
            onRequestTitleDeletion = {},
            onAttentionAction = { _, _ -> },
            onChooseMember = {},
            onOpenDetail = {},
            onReviewCleanup = {},
            onCancelGroup = {},
            part = part,
            libraryColumns = libraryColumns,
        )
    }

    @Composable
    private fun HeaderActions() {
        IconButton(onClick = {}) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = {}) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /**
     * Library's Downloads tab on a phone: the header and switcher `DownloadsScreen` draws, then the list.
     * `NuvioScreen`'s default top padding, as the app's phone branch leaves it (`topChromePadding` is null).
     */
    @Composable
    private fun PhoneDownloads() {
        NuvioScreen {
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    NuvioScreenHeader(modifier = Modifier.downloadsContentWidth(), title = "Downloads", actions = { HeaderActions() })
                    Box(Modifier.downloadsContentWidth()) {
                        LibraryTopSwitcher(selectedDestination = LibrarySubDestination.Downloads, onDestinationSelected = {})
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            downloadsContent(libraryColumns = 1)(DownloadsPart.All)
        }
    }

    /** Desktop's own Downloads destination with something under way: the two-pane layout. */
    @Composable
    private fun DesktopDownloads() {
        promo.DesktopShell(AppScreenTab.Downloads) {
            DownloadsWideLayout(
                header = { width -> NuvioScreenHeader(modifier = width, title = "Downloads", actions = { HeaderActions() }) },
                content = downloadsContent(libraryColumns = 1),
            )
        }
    }

    /** A title's page pushed on an iPhone: no tab bar, the status bar over the page's own top. */
    @Composable
    private fun IphoneDetails(t: PromoTitle, statusInset: Boolean) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .padding(top = if (statusInset) IphoneStatusBar else 0.dp),
        ) {
            MetaDetailsScreen(
                type = "movie",
                id = t.id,
                onBack = {},
                onWatchTogether = {},
                onPlay = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                onPlayManually = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            )
        }
    }

    @Test
    fun renderScreenshotSurfaces() {
        if (!PromoArt.available) {
            println("ScreenshotRenderHarness: ${PromoArt.promoRoot}/assets/app-art is missing; skipping.")
            return
        }
        File(promo.outDir, "screenshots").mkdirs()
        promo.seedProfile()
        val more = c.openMovies.drop(1).map { it.preview() }
        promo.seedMeta(c.sintel.details(moreLikeThis = more))
        val only = System.getenv("SHOTS_ONLY")?.split(',')?.map(String::trim)?.toSet()
        fun wants(name: String) = only == null || name in only
        val failures = mutableListOf<String>()
        fun shot(name: String, w: Int, h: Int, kind: PromoRenderHarness.Kind, density: Float, content: @Composable () -> Unit) {
            if (wants(name)) promo.render("screenshots/$name", w, h, kind, density, failures, content = content)
        }

        shot("desktop-home", 1920, 1080, Desktop, 2f) { promo.DesktopShell(AppScreenTab.Home) { promo.HomeComposition() } }
        shot("desktop-social", 1920, 1080, Desktop, 2f) { promo.DesktopShell(AppScreenTab.Social) { promo.Social() } }
        shot("desktop-downloads", 1920, 1080, Desktop, 2f) { DesktopDownloads() }
        shot("desktop-details", 1920, 1080, Desktop, 2f) { promo.Details() }
        shot("android-home", 411, 914, Phone, 3f) { promo.PhoneShell(AppScreenTab.Home, statusInset = true) { promo.HomeComposition() } }
        shot("android-social", 411, 914, Phone, 3f) { promo.PhoneShell(AppScreenTab.Social, statusInset = true) { promo.Social() } }
        shot("android-downloads", 411, 914, Phone, 3f) { promo.PhoneShell(AppScreenTab.Library, statusInset = true) { PhoneDownloads() } }
        shot("iphone-details", 402, 874, Phone, 3f) { IphoneDetails(c.sintel, statusInset = true) }
        shot("iphone-details-edge", 402, 874, Phone, 3f) { IphoneDetails(c.sintel, statusInset = false) }
        if (wants("iphone-details-spring")) {
            promo.seedMeta(c.spring.details(moreLikeThis = c.openMovies.filter { it != c.spring }.map { it.preview() }))
            promo.render("screenshots/iphone-details-spring", 402, 874, Phone, 3f, failures) { IphoneDetails(c.spring, statusInset = true) }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private companion object {
        /** An iPhone 16/17 Pro's status bar around the Dynamic Island, which the desktop test host reports as zero. */
        val IphoneStatusBar = 59.dp
    }
}
