package com.nuvio.app.promo

/**
 * Real catalogue titles for the Social V2 promo from draft 5 on (2026-10-24 review: the open movies
 * "look eh"). Unlike [PromoCatalog], these are not freely licensed: the art is what Cinemeta / metahub
 * serves the app itself for each IMDb id, fetched by `Nuvio Z/promo/tools/fetch_cinemeta.py`, the way
 * The Godfather's already was. Synopses are written for this file.
 */
internal object PromoRealCatalog {
    private fun t(slug: String, name: String, year: String, runtime: Int, genres: List<String>, description: String, director: String) =
        PromoTitle(slug, name, year, runtime, genres, description, listOf(director))

    val godfather = t(
        "the-godfather", "The Godfather", "1972", 175, listOf("Crime", "Drama"),
        "The aging patriarch of an organized crime dynasty transfers control of his clandestine empire to his reluctant son.",
        "Francis Ford Coppola",
    )
    val godfather2 = t("the-godfather-part-ii", "The Godfather Part II", "1974", 202, listOf("Crime", "Drama"),
        "A young Vito Corleone rises in 1920s New York while his son tightens his grip on the family.", "Francis Ford Coppola")
    val goodfellas = t("goodfellas", "GoodFellas", "1990", 145, listOf("Crime", "Drama"),
        "Henry Hill's life in the mob, from errand boy to the man who has to give it all up.", "Martin Scorsese")
    val pulpFiction = t("pulp-fiction", "Pulp Fiction", "1994", 154, listOf("Crime", "Drama"),
        "Two hitmen, a boxer and a gangster's wife cross paths over one long Los Angeles weekend.", "Quentin Tarantino")
    val theShining = t("the-shining", "The Shining", "1980", 144, listOf("Horror", "Drama"),
        "A writer takes his family to winter over at an empty mountain hotel, and the hotel takes him.", "Stanley Kubrick")
    val duneTwo = t("dune-part-two", "Dune: Part Two", "2024", 167, listOf("Science Fiction", "Adventure"),
        "Paul Atreides joins the Fremen and wages war on the conspirators who destroyed his family.", "Denis Villeneuve")
    val dune = t("dune", "Dune", "2021", 155, listOf("Science Fiction", "Adventure"),
        "The heir of a noble house is sent to the most dangerous planet in the universe.", "Denis Villeneuve")
    val oppenheimer = t("oppenheimer", "Oppenheimer", "2023", 181, listOf("Drama", "History"),
        "The physicist who led the Manhattan Project, and the hearing that tried to unmake him.", "Christopher Nolan")
    val interstellar = t("interstellar", "Interstellar", "2014", 169, listOf("Science Fiction", "Adventure"),
        "A pilot leaves his children behind to search for a new home for humanity beyond a wormhole.", "Christopher Nolan")
    val everything = t("everything-everywhere", "Everything Everywhere All at Once", "2022", 140, listOf("Action", "Comedy"),
        "A laundromat owner in the middle of a tax audit discovers she can live every other life she might have had.", "Daniels")
    val parasite = t("parasite", "Parasite", "2019", 133, listOf("Drama", "Thriller"),
        "A struggling family talks its way, one job at a time, into a wealthy family's house.", "Bong Joon Ho")
    val bladeRunner = t("blade-runner-2049", "Blade Runner 2049", "2017", 164, listOf("Science Fiction", "Drama"),
        "A replicant blade runner unearths a secret that could end what is left of society.", "Denis Villeneuve")
    val pastLives = t("past-lives", "Past Lives", "2023", 106, listOf("Drama", "Romance"),
        "Two childhood friends, separated for decades, meet again in New York for one week.", "Celine Song")
    val theBatman = t("the-batman", "The Batman", "2022", 177, listOf("Crime", "Mystery"),
        "In his second year of fighting crime, Batman follows a killer's riddles into Gotham's corruption.", "Matt Reeves")
    val arrival = t("arrival", "Arrival", "2016", 116, listOf("Science Fiction", "Drama"),
        "A linguist is brought in to talk to the visitors whose ships have settled over the Earth.", "Denis Villeneuve")
    val madMax = t("mad-max-fury-road", "Mad Max: Fury Road", "2015", 121, listOf("Action", "Adventure"),
        "In a desert wasteland, a drifter and a rebel warrior flee a tyrant across the sand.", "George Miller")
    val whiplash = t("whiplash", "Whiplash", "2014", 107, listOf("Drama", "Music"),
        "A young drummer at a top conservatory meets the teacher who will stop at nothing.", "Damien Chazelle")
    val laLaLand = t("la-la-land", "La La Land", "2016", 129, listOf("Comedy", "Drama", "Music"),
        "A jazz pianist and an aspiring actress fall in love while chasing their dreams in Los Angeles.", "Damien Chazelle")
    val inception = t("inception", "Inception", "2010", 148, listOf("Science Fiction", "Thriller"),
        "A thief who steals secrets from dreams is offered one last job: planting an idea instead.", "Christopher Nolan")
    val topGun = t("top-gun-maverick", "Top Gun: Maverick", "2022", 131, listOf("Action", "Drama"),
        "Thirty years on, Maverick is called back to train a squadron for an impossible mission.", "Joseph Kosinski")
    val poorThings = t("poor-things", "Poor Things", "2023", 141, listOf("Comedy", "Drama"),
        "Brought back to life by an eccentric scientist, Bella sets out to see the world for herself.", "Yorgos Lanthimos")
    val knivesOut = t("knives-out", "Knives Out", "2019", 131, listOf("Comedy", "Crime"),
        "A detective investigates the death of a crime novelist at his own birthday party.", "Rian Johnson")

    val all = listOf(
        godfather, godfather2, goodfellas, pulpFiction, theShining, duneTwo, dune, oppenheimer, interstellar,
        everything, parasite, bladeRunner, pastLives, theBatman, arrival, madMax, whiplash, laLaLand, inception,
        topGun, poorThings, knivesOut,
    )

    /** Whether `tools/fetch_cinemeta.py` has been run: every title needs a poster and a backdrop. */
    val available: Boolean get() = all.all { PromoArt.has("poster", it.slug) && PromoArt.has("backdrop", it.slug) }
}
