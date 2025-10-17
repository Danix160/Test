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

    override val mainPage = mainPageOf(
//        mainUrl to "Top 10 Film",
//        mainUrl to "Top 10 Serie TV",
        "$mainUrl/anime-ita/" to "Anime Italiani",
        "$mainUrl/contatti/" to "Anime Sub Ita",

        "$mainUrl/film-animazione/" to "Film: Animazione",

        "$mainUrl/serie-tv/" to "Serie TV",

        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val response = try {
            app.get(request.data).document
        } catch (e: SocketTimeoutException) {
            return null
        }
        val searchResponses = getItems(request.name, response)
        return newHomePageResponse(HomePageList(request.name, searchResponses), false)
    }
     private suspend fun getItems(section: String, page: Document): List<SearchResponse> {
        val searchResponses = when (section) {
            "Film: Ultimi aggiunti", "Serie TV: Ultime aggiunte" -> {
                val itemGrid = page.selectFirst(".wp-block-uagb-post-grid")!!
                val items = itemGrid.select(".uagb-post__inner-wrap")
                items.map {
                    val itemTag = it.select(".uagb-post__title > a")
                    val title = itemTag.text().trim().replace(Regex("""\d{4}$"""), "")
                    val url = itemTag.attr("href")
                    val poster = it.select(".uagb-post__image > a > img").attr("src")

                    newTvSeriesSearchResponse(title, url) {
                        this.posterUrl = poster
                    }
                }
            }
               private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select("h2").text().trim().replace(Regex("""\d{4}$"""), "")
        val url = this.select("a").attr("href")
        val poster = this.select("img").attr("src")
        return newTvSeriesSearchResponse(title, url) {
            this.posterUrl = poster
        }
    }
  override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/?s=$query")
        val page = response.document
        val itemGrid = page.selectFirst("#box_movies")!!
        val items = itemGrid.select(".movie")
        val searchResponses = items.map {
            it.toSearchResponse()
        }
        return searchResponses
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
      private fun getEpisodes(page: Document): List<Episode> {
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
                val links = it.select("a").map { a -> "\"${a.attr("href")}\"" }
                Episode("$links").apply {
//                    name = title
                    this.season = season
                    this.episode = title.substringAfter("x").substringBefore(" ").toIntOrNull()
                }
            }
        }
        return episodes
    }
