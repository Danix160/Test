package com.toonitalia


import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null

        val imgEl = document.selectFirst(".post img")
        val poster = imgEl?.attr("src")?.takeIf { it.isNotBlank() } ?: imgEl?.attr("data-src")

        val episodes = document.select(".entry-content a[href]").mapNotNull {
            val name = it.text().trim()
            val link = it.attr("href")
            
            if (name.isNotBlank() && !name.contains("download", true)) {
                // CORREZIONE 1: L'errore suggerisce che newEpisode(String, String) non esiste.
                // Usiamo newEpisode() e lo configuriamo, o usiamo un costruttore funzionante
                // Riprovo con newEpisode() e lambda
                newEpisode(name) {
                    this.data = link
                }
            } else {
                null
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            // CORREZIONE 2: Risoluzione di DubStatus.SUB e inferenza del tipo esplicita
            // Ho anche corretto 'SUB' in 'Sub' (case sensitve)
            this.episodes = mapOf<DubStatus, List<Episode>>(DubStatus.Sub to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe")

        iframes.forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return iframes.isNotEmpty()
    }
}
