package com.tokusatsuindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class TokusatsuIndoProvider : MainAPI() {
    override var mainUrl = "https://www.tokusatsuindo.com"
    override var name = "Tokusatsu Indo🏍"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Latest Update", 
        "kamen-rider/" to "Kamen Rider",
        "super-sentai/" to "Super Sentai",
        "ultraman/" to "Ultraman",
        "other-tokusatsu/" to "Other Tokusatsu",
        "movie/" to "Movie & Special"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }
        
        val document = app.get(url).document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        val hasNext = document.select(".next.page-numbers").isNotEmpty() || items.isNotEmpty()
        
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" -") 
            ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: document.selectFirst(".content-thumbnail img")?.attr("src")
        val description = document.selectFirst(".entry-content p")?.text()?.trim()
        val tags = document.select(".gmr-movie-on a").map { it.text().trim() }
        val episodeElements = document.select("ul.lcp_catlist li a")

        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epUrl = ep.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epTitle = ep.text().trim()
                val epNum = Regex("""(?i)episode\s*(\d+(?:\.\d+)?)""").find(epTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum?.toInt()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    var linkFound = false

    val postId = document.selectFirst("link[rel=shortlink]")?.attr("href")?.substringAfter("p=")
        ?: document.selectFirst("body")?.classNames()
            ?.find { it.startsWith("postid-") }?.substringAfter("-")
        ?: document.selectFirst("article")?.attr("id")?.substringAfter("-")
        ?: return false

    for (tab in listOf("p1", "p2", "p3")) {
        runCatching {
            val response = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action"  to "muvipro_player_content",
                    "tab"     to tab,
                    "post_id" to postId
                ),
                headers = mapOf(
                    "Accept"           to "*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Referer"          to data
                )
            ).text

            val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
            if (iframeSrc.isNotBlank()) {
                val fixedUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

                // Google Drive: ubah /preview -> /view agar extractor bawaan CloudStream bisa handle
                val finalUrl = if (fixedUrl.contains("drive.google.com") && fixedUrl.contains("/preview")) {
                    fixedUrl.replace("/preview", "/view")
                } else {
                    fixedUrl
                }

                // Ikutin pola Anichin: 3 parameter saja
                loadExtractor(finalUrl, subtitleCallback, callback)
                linkFound = true
            }
        }
    }

    return linkFound
}

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst(".entry-title a") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst("img.wp-post-image")?.attr("src")?.takeIf { it.isNotBlank() }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}
