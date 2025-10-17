package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import android.util.Log // Aggiunto per la funzione bypassUprot

class ToonitaliaPlugin : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toonitalia"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val posts = document.select(".post, article")

        val items = posts.mapNotNull {
            val title = it.selectFirst("h2, h3")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            val imgEl = it.selectFirst("img")
            val img = imgEl?.attr("src")?.takeIf { src -> src.isNotBlank() }
                ?: imgEl?.attr("data-src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = img
            }
        }

        return newHomePageResponse(listOf(HomePageList("Ultimi Aggiornamenti", items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        val posts = document.select(".post, article")

        return posts.mapNotNull {
            val title = it.selectFirst("h2, h3")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            val imgEl = it.selectFirst("img")
            val img = imgEl?.attr("src")?.takeIf { src -> src.isNotBlank() }
                ?: imgEl?.attr("data-src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = img
            }
        }
    }

    // NUOVA FUNZIONE PER ESTRARRE EPISODI CON INFORMAZIONI SU STAGIONI E NUMERI
    private fun getEpisodes(page: Document): List<Episode> {
        // Usa `selectFirst` con '!!' solo se sei sicuro che l'elemento esista, altrimenti usa '?' e gestisci il null.
        val table = page.selectFirst("#hostlinks > table:nth-child(1)")!!
        var season: Int? = 1
        val rows = table.select("tr")

        val episodes: List<Episode> = rows.mapNotNull {
            if (it.childrenSize() == 0) {
                null
            } else if (it.childrenSize() == 1) {
                val seasonText =
                    it.select("td:nth-child(1)").text().substringBefore("- Episodi disponibi")
                season = Regex("""\d+""").find(seasonText)?.value?.toInt()
                null
            } else {
                val title = it.select("td:nth-child(1)").text()
                // Mappiamo i link come una stringa JSON
                val links = it.select("a").map { a -> a.attr("href") }
                val dataString = links.joinToString(prefix = "[", separator = ", ", postfix = "]") { link -> "\"$link\"" }
                
                // Correggo l'uso del costruttore e imposto i campi usando newEpisode con una lambda
                newEpisode(title) {
                    this.data = dataString // data ora contiene la lista di URL come stringa JSON
                    this.season = season
                    // Estrae il numero dell'episodio. Assumiamo che il formato sia '...x[Numero] ...'
                    this.episode = title.substringAfter("x").substringBefore(" ").toIntOrNull() 
                }
            }
        }
        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).document
        val dati = response.selectFirst(".headingder")!!
        val poster = dati.select(".imgs > img").attr("src").replace(Regex("""-\d+x\d+"""), "")
        val title = dati.select(".dataplus > div:nth-child(1) > h1").text().trim()
            .replace(Regex("""\d{4}$"""), "")
        val rating = dati.select(".stars > span:nth-child(3)").text().trim().removeSuffix("/10")
        val genres = dati.select(".stars > span:nth-child(6) > i:nth-child(1)").text().trim()
        val year = dati.select(".stars > span:nth-child(8) > i:nth-child(1)").text().trim()
        val duration = dati.select(".stars > span:nth-child(10) > i:nth-child(1)").text()
            .removeSuffix(" minuti")
        val isMovie = url.contains("/film/")

        return if (isMovie) {
            val streamUrl = response.select("#hostlinks").select("a").map { it.attr("href") }
            val plot = response.select(".post > p:nth-child(16)").text().trim()
            newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                addPoster(poster)
                addRating(rating)
                this.duration = duration.toIntOrNull()
                this.year = year.toIntOrNull()
                this.tags = genres.split(",")
                this.plot = plot
            }
        } else {
            val episodes = getEpisodes(response)
            val plot = response.select(".post > p:nth-child(17)").text().trim()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster)
                addRating(rating)
                this.year = year.toIntOrNull()
                this.tags = genres.split(",")
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // La variabile 'data' qui contiene la stringa JSON dei link creata in getEpisodes.
        // Decodifichiamo la stringa JSON in una lista di URL.
        val linkList = data.substring(1, data.length - 1).split(", ").map { it.trim().removeSurrounding("\"") }

        linkList.forEach { src ->
            if (src.isNotBlank()) {
                if (src.contains("uprot")) {
                    // Bypass di Uprot se il link lo contiene
                    val bypassedUrl = bypassUprot(src)
                    if (bypassedUrl != null) {
                        // Passa l'URL bypassato all'estrattore. mainUrl è usato come referer.
                        loadExtractor(bypassedUrl, mainUrl, subtitleCallback, callback)
                    }
                } else {
                    // Tratta link diretti o altri link non-uprot
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return linkList.isNotEmpty()
    }

    // FUNZIONE DI BYPASS COPIATA DA OnlineSerieTV
    private suspend fun bypassUprot(link: String): String? {
        // Sostituisce msf con mse se presente, come fatto nel codice originale
        val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link

        // Genera headers
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        )

        // Esegue la richiesta HTTP
        val response = app.get(updatedLink, headers = headers, timeout = 10_000)

        // Parse l'HTML per trovare il link finale. Uprot spesso reindirizza o nasconde il link
        val document = response.document
        // Logging disabilitato per default per pulizia, ma puoi riattivarlo per debug
        // Log.d("Uprot", document.toString()) 
        val maxstreamUrl = document.selectFirst("a")?.attr("href")

        return maxstreamUrl
    }
}
