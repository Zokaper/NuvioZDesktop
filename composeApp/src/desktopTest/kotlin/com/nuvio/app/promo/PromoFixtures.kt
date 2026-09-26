package com.nuvio.app.promo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.Image
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.social.SocialProfileSummary
import org.jetbrains.skia.Bitmap
import java.io.File

/**
 * The demo catalogue for the promo renders.
 *
 * Every title is either a Blender Studio open movie (CC BY) or a public-domain film, and every image
 * is the real poster or a real frame from the film, fetched by `Nuvio Z/promo/tools/`. Nothing here is
 * generated. The synopses are written for this file.
 *
 * Images are answered through Coil's preview handler, exactly as `DownloadRenderFixtures` does it,
 * so the production `AsyncImage` calls draw them on the first frame without a network.
 */
internal object PromoArt {
    /** `Nuvio Z/promo`, relative to the Gradle test working directory (`composeApp/`). */
    val promoRoot: File = File("../../promo").canonicalFile
    private val artRoot = File(promoRoot, "assets/app-art")

    val available: Boolean get() = artRoot.isDirectory

    fun poster(slug: String) = "promo://poster/$slug"
    fun backdrop(slug: String) = "promo://backdrop/$slug"
    fun logo(slug: String) = "promo://logo/$slug"

    private val cache = mutableMapOf<String, Image?>()

    private val blank: Image by lazy { Bitmap().apply { allocN32Pixels(2, 3); erase(0xFF1B1B1D.toInt()) }.asImage() }

    fun image(url: String): Image {
        if (!url.startsWith("promo://")) return blank
        return cache.getOrPut(url) {
            val (kind, slug) = url.removePrefix("promo://").split('/', limit = 2)
            val file = listOf("jpg", "png").map { File(artRoot, "$kind/$slug.$it") }.firstOrNull { it.isFile }
                ?: return@getOrPut null
            val skia = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
            Bitmap.makeFromImage(skia).asImage()
        } ?: blank
    }

    fun has(kind: String, slug: String) =
        listOf("jpg", "png").any { File(artRoot, "$kind/$slug.$it").isFile }
}

@Composable
internal fun WithPromoArt(content: @Composable () -> Unit) {
    val handler = AsyncImagePreviewHandler { request -> PromoArt.image(request.data.toString()) }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides handler,
    ) { content() }
}

internal data class PromoTitle(
    val slug: String,
    val name: String,
    val year: String,
    val runtimeMin: Int,
    val genres: List<String>,
    val description: String,
    val director: List<String> = emptyList(),
) {
    val id: String get() = "nz-$slug"
    val durationMs: Long get() = runtimeMin * 60_000L

    fun preview() = MetaPreview(
        id = id,
        type = "movie",
        name = name,
        poster = PromoArt.poster(slug),
        banner = PromoArt.backdrop(slug).takeIf { PromoArt.has("backdrop", slug) },
        logo = PromoArt.logo(slug).takeIf { PromoArt.has("logo", slug) },
        description = description,
        releaseInfo = year,
        genres = genres,
    )

    fun details(moreLikeThis: List<MetaPreview>) = MetaDetails(
        id = id,
        type = "movie",
        name = name,
        poster = PromoArt.poster(slug),
        background = PromoArt.backdrop(slug).takeIf { PromoArt.has("backdrop", slug) },
        logo = PromoArt.logo(slug).takeIf { PromoArt.has("logo", slug) },
        description = description,
        releaseInfo = year,
        runtime = "$runtimeMin min",
        genres = genres,
        director = director,
        moreLikeThis = moreLikeThis,
    )
}

internal object PromoCatalog {
    val sintel = PromoTitle(
        "sintel", "Sintel", "2010", 15, listOf("Animation", "Fantasy", "Adventure"),
        "A young woman crosses a frozen, dangerous world to find the baby dragon she once nursed back to health, and learns what the search has cost her.",
        director = listOf("Colin Levy"),
    )
    val tearsOfSteel = PromoTitle(
        "tears-of-steel", "Tears of Steel", "2012", 12, listOf("Science Fiction", "Action"),
        "In a future Amsterdam, a group of scientists stage a desperate experiment to undo the day their world was lost to machines.",
        director = listOf("Ian Hubert"),
    )
    val elephantsDream = PromoTitle(
        "elephants-dream", "Elephants Dream", "2006", 11, listOf("Animation", "Science Fiction"),
        "Two men wander an endless, shifting machine that only one of them believes in.",
    )
    val bigBuckBunny = PromoTitle(
        "big-buck-bunny", "Big Buck Bunny", "2008", 10, listOf("Animation", "Comedy"),
        "A gentle giant of a rabbit finally loses patience with the three rodents who torment the meadow.",
    )
    val cosmos = PromoTitle(
        "cosmos-laundromat", "Cosmos Laundromat", "2015", 12, listOf("Animation", "Fantasy"),
        "On a desolate island, a despairing sheep meets a strange salesman who offers him the gift of a lifetime.",
    )
    val spring = PromoTitle(
        "spring", "Spring", "2019", 8, listOf("Animation", "Fantasy"),
        "A shepherd girl and her dog face ancient spirits to keep the cycle of life turning.",
    )
    val spriteFright = PromoTitle(
        "sprite-fright", "Sprite Fright", "2021", 10, listOf("Animation", "Horror", "Comedy"),
        "A group of teenagers on a forest trip meet the mushroom sprites who live there, and fail to make a good impression.",
    )
    val charge = PromoTitle(
        "charge", "Charge", "2022", 4, listOf("Animation", "Action"),
        "In an energy-starved world, an old man breaks into a guarded power plant for the charge he needs.",
    )
    val metropolis = PromoTitle(
        "metropolis", "Metropolis", "1927", 153, listOf("Science Fiction", "Drama"),
        "In a vast future city, the son of its master descends to the workers' depths and finds the machine his world runs on.",
        director = listOf("Fritz Lang"),
    )
    val nightOfTheLivingDead = PromoTitle(
        "night-of-the-living-dead", "Night of the Living Dead", "1968", 96, listOf("Horror"),
        "Strangers barricade themselves in a farmhouse as the dead begin to rise.",
        director = listOf("George A. Romero"),
    )
    val theGeneral = PromoTitle(
        "the-general", "The General", "1926", 78, listOf("Comedy", "Action"),
        "A railway engineer chases his stolen locomotive across enemy lines.",
        director = listOf("Buster Keaton", "Clyde Bruckman"),
    )
    val charade = PromoTitle(
        "charade", "Charade", "1963", 113, listOf("Thriller", "Romance", "Comedy"),
        "A widow in Paris is pursued by the men who want the fortune her late husband stole.",
        director = listOf("Stanley Donen"),
    )
    val hisGirlFriday = PromoTitle(
        "his-girl-friday", "His Girl Friday", "1940", 92, listOf("Comedy", "Romance"),
        "A newspaper editor schemes to win back his star reporter, who is also his ex-wife.",
        director = listOf("Howard Hawks"),
    )
    val sherlockJr = PromoTitle(
        "sherlock-jr", "Sherlock Jr.", "1924", 45, listOf("Comedy"),
        "A projectionist who dreams of being a detective walks into the film he is showing.",
        director = listOf("Buster Keaton"),
    )
    val caligari = PromoTitle(
        "caligari", "The Cabinet of Dr. Caligari", "1920", 76, listOf("Horror", "Mystery"),
        "A hypnotist and his sleepwalker arrive at a village fair, and the murders begin.",
        director = listOf("Robert Wiene"),
    )
    val phantom = PromoTitle(
        "phantom-of-the-opera", "The Phantom of the Opera", "1925", 93, listOf("Horror", "Drama"),
        "A masked figure haunting the Paris Opera falls for a young soprano.",
    )
    val carnival = PromoTitle(
        "carnival-of-souls", "Carnival of Souls", "1962", 78, listOf("Horror", "Mystery"),
        "After surviving a crash, a church organist is drawn to an abandoned lakeside pavilion.",
    )

    val openMovies = listOf(sintel, spring, charge, spriteFright, cosmos, tearsOfSteel, bigBuckBunny, elephantsDream)
        .filter { PromoArt.has("poster", it.slug) }
    val classics = listOf(metropolis, nightOfTheLivingDead, charade, theGeneral, hisGirlFriday, sherlockJr, caligari, phantom, carnival)
        .filter { PromoArt.has("poster", it.slug) }
    val all = openMovies + classics

    fun section(key: String, title: String, titles: List<PromoTitle>) = HomeCatalogSection(
        key = key,
        title = title,
        subtitle = "",
        addonName = "",
        target = CatalogTarget.Addon(manifestUrl = "", contentType = "movie", catalogId = key),
        items = titles.map { it.preview() },
    )
}

/** The people in the demo. Initials on colour - there are no photographs of anyone. */
internal object PromoPeople {
    fun person(name: String, handle: String, color: String) = SocialProfileSummary(
        profileId = "p-$handle",
        handle = handle,
        displayName = name,
        avatarUrl = null,
        avatarColorHex = color,
        isFriend = true,
    )

    val maya = person("Maya", "maya", "#7E57C2")
    val theo = person("Theo", "theo", "#26A69A")
    val ines = person("Ines", "ines", "#EF6C00")
    val jonah = person("Jonah", "jonah", "#1E88E5")
    val priya = person("Priya", "priya", "#D81B60")
    val sam = person("Sam", "samk", "#7CB342")

    val friends = listOf(theo, ines, jonah, priya, sam)
}
