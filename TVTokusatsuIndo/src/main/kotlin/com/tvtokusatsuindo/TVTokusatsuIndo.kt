package com.tvtokusatsuindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TVTokusatsuIndo : MainAPI() {
    override var mainUrl = "https://www.tvtokusatsuindo.com"
    override var name = "TVTokusatsuIndo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/search/label/Tokusatsu?max-results=24" to "Tokusatsu",
        "$mainUrl/search/label/Kamen%20Rider?max-results=24" to "Kamen Rider",
        "$mainUrl/search/label/Ultraman?max-results=24" to "Ultraman",
        "$mainUrl/search/label/Power%20Ranger?max-results=24" to "Power Ranger",
        "$mainUrl/search/label/Super%20Sentai?max-results=24" to "Super Sentai",
        "$mainUrl/search/label/Movie?max-results=24" to "Movie",
    )

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return url
    }

    private fun fixUrlNull(url: String?): String? {
        return if (url.isNullOrBlank()) null else fixUrl(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page > 1) {
            if (request.data.contains("max-results")) {
                "${request.data}&start-index=${(page - 1) * 24 + 1}"
            } else {
                "${request.data}${separator}page=$page"
            }
        } else request.data

        val document = app.get(url).document

        val home = document.select(".post, article, .blog-posts .post-outer").mapNotNull { post ->
            val title = post.selectFirst("h1, h2, h3")?.text()?.trim()
                ?: post.selectFirst(".post-title")?.text()?.trim()
                ?: return@mapNotNull null

            val href = post.selectFirst("a[href]")?.attr("abs:href")
                ?.ifBlank { post.selectFirst("a[href]")?.attr("href") }
                ?.let(::fixUrl)
                ?: return@mapNotNull null

            val poster = post.selectFirst("img")?.let {
                fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
            }

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        val hasNext = document.select(".blog-pager-older-link, #blog-pager a").any {
            it.text().contains("Older", ignoreCase = true) ||
                it.text().contains("Berikut", ignoreCase = true)
        }

        return newHomePageResponse(HomePageList(request.name, home), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(Regex("\\s+"), "+")
        val document = app.get("$mainUrl/search?q=$q&max-results=24").document

        return document.select(".post, article, .blog-posts .post-outer").mapNotNull { post ->
            val title = post.selectFirst("h1, h2, h3")?.text()?.trim()
                ?: post.selectFirst(".post-title")?.text()?.trim()
                ?: post.selectFirst("a")?.text()?.trim()
                ?: return@mapNotNull null

            val href = post.selectFirst("a[href]")?.attr("abs:href")
                ?.ifBlank { post.selectFirst("a[href]")?.attr("href") }
                ?.let(::fixUrl)
                ?: return@mapNotNull null

            val poster = post.selectFirst("img")?.let {
                fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
            }

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h3.post-title, h1.entry-title, .post h2, .post h1")
            ?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("title")?.text()?.replace(Regex(" \\| .*"), "")?.trim()
            ?: throw ErrorLoadingException("Missing title")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlNull)
            ?: document.selectFirst(".post-body img, .entry-content img, .separator img")
                ?.attr("src")?.let(::fixUrlNull)

        val synopsis = document.selectFirst(".post-body, .entry-content")
            ?.text()?.trim()?.takeIf { it.length > 50 }?.ifBlank { null }

        // Google Drive links from post body
        val driveLinks = document.select(".post-body a[href*=drive.google.com], .entry-content a[href*=drive.google.com]")
        val episodeButtons = document.select(".post-body div[class] button, .entry-content div[class] button")
        val episodes = mutableListOf<Episode>()

        if (episodeButtons.isNotEmpty()) {
            episodeButtons.forEach { btn ->
                val epName = btn.text().trim().ifBlank { "Episode" }
                val driveUrl = btn.selectFirst("a[href]")?.attr("href")
                    ?: btn.attr("onclick")?.let { Regex("'([^']+)'").find(it)?.groupValues?.get(1) }
                    ?: return@forEach
                episodes.add(Episode(data = driveUrl, name = epName))
            }
        } else if (driveLinks.isNotEmpty()) {
            driveLinks.forEach { link ->
                val epName = link.parent()?.text()?.trim()?.take(80)?.ifBlank { "Movie" } ?: "Movie"
                episodes.add(Episode(data = link.attr("href"), name = epName))
            }
        }

        // Fallback: any drive link on page
        if (episodes.isEmpty()) {
            document.select("a[href*=drive.google.com]").forEach { link ->
                val epName = link.parent()?.text()?.trim()?.take(80)?.ifBlank { "Movie" } ?: "Movie"
                episodes.add(Episode(data = link.attr("href"), name = epName))
            }
        }

        if (episodes.size > 1) episodes.reverse()

        val isMovie = episodes.size <= 1 || title.contains("Movie", ignoreCase = true)

        return if (isMovie || episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = synopsis
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = synopsis
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileId = when {
            data.contains("/uc?id=") -> Regex("id=([a-zA-Z0-9_-]+)").find(data)?.groupValues?.get(1)
            data.contains("/file/d/") -> Regex("/file/d/([a-zA-Z0-9_-]+)").find(data)?.groupValues?.get(1)
            else -> null
        }

        if (fileId != null) {
            val driveUrl = "https://drive.google.com/uc?id=$fileId&export=download"
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Google Drive",
                    url = driveUrl,
                    referer = "https://drive.google.com",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        if (data.startsWith("http")) {
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        return false
    }
}
