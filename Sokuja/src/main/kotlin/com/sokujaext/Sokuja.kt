package com.sokujaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Sokuja : MainAPI() {
    override var mainUrl = "https://x5.sokuja.uk"
    override var name = "Sokuja"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.ONA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
        "$mainUrl/anime-type/tv/" to "TV Anime",
        "$mainUrl/anime-type/movie/" to "Movie",
        "$mainUrl/anime-type/ona/" to "ONA",
        "$mainUrl/anime-type/ova/" to "OVA",
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

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = a.attr("abs:href").ifBlank { a.attr("href") }.let(::fixUrl)
        if (!href.contains("/anime/", ignoreCase = true)) return null
        val poster = this.selectFirst("img")?.let {
            fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val title = this.selectFirst("b, strong, .tt, .title, h2, h3")?.text()?.trim()
            ?: a.attr("title")?.trim()
            ?: return null
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun Element.toAnimeCard(): AnimeSearchResponse? {
        val linkEl = this.selectFirst("a[href*=/anime/]") ?: return null
        val href = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }.let(::fixUrl)
        val poster = this.selectFirst("img")?.let {
            fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val title = this.selectFirst("b, strong")?.text()?.trim()
            ?: linkEl.attr("title")?.trim()
            ?: return null
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page > 1) "${request.data}${separator}page=$page" else request.data
        val document = app.get(url).document
        val home = document.select("article, .bs, .bsx, .animposfix, .listupd .bsx, .animepost").mapNotNull {
            it.toAnimeCard()
        }
        return newHomePageResponse(
            list = HomePageList(request.name, home),
            hasNext = document.select(".pagination a, .hpage a, a.next, .page-numbers").isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(Regex("\\s+"), "+")
        val document = app.get("$mainUrl/?s=$q").document
        return document.select("article, .bs, .bsx, .result-item, .animposfix, .animepost").mapNotNull {
            it.toAnimeCard()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.title, .entry-title, h1")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("title")
                ?.text()
                ?.replace(Regex(" \\| .*"), "")
                ?.trim()
            ?: throw ErrorLoadingException("Missing title")

        val poster = document.selectFirst("img.wp-post-image, .thumb img, .poster img, .thumbnail img")
            ?.let { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlNull)

        val synopsis = document.selectFirst(".entry-content, .desc, .sinop, .contentpm p, .sinops")
            ?.text()
            ?.trim()
            ?.ifBlank { null }

        val tags = document.select(".genre a, .genre-info a, .tag a, .meta-genre a")
            .map { it.text().trim().trimEnd(',') }
            .filter { it.isNotEmpty() }

        val rows = document.select(".spe span, .infox p, .meta span")
        fun rowValue(label: String): String? {
            return rows.firstOrNull {
                it.text().startsWith("$label:", ignoreCase = true)
            }?.text()
                ?.replace("$label:", "", ignoreCase = true)
                ?.trim()
                ?.ifBlank { null }
        }

        val year = Regex("(19|20)\\d{2}").find(
            rowValue("Tahun") ?: document.selectFirst(".year, .spe span:contains(Tahun)")?.text()?.trim() ?: ""
        )?.value?.toIntOrNull()

        val typeText = rowValue("Tipe") ?: ""
        val statusText = rowValue("Status") ?: ""

        // Episode list
        val episodeElements = document.select(".lstepsiode ul li, .episodelist ul li, .episode-list li, .bxcl li")
        val episodes = episodeElements.mapNotNull { epEl ->
            val epA = epEl.selectFirst("a[href]") ?: return@mapNotNull null
            val epHref = epA.attr("abs:href").ifBlank { epA.attr("href") }.let(::fixUrl)
            val epName = epA.text().trim().ifBlank { epEl.text().trim() }
            Episode(
                data = epHref,
                name = epName,
            )
        }.reversed()

        // Check if movie
        val isMovie = typeText.contains("Movie", ignoreCase = true) ||
            document.selectFirst(".badge-type")?.text()?.contains("Movie", ignoreCase = true) == true

        if (isMovie || episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
                this.year = year
            }
        } else {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
                this.year = year
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

        // Try iframe embeds
        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            src = fixUrl(src)
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Try video source tags
        document.select("video source, source[src]").forEach { src ->
            val videoUrl = src.attr("abs:src").ifBlank { src.attr("src") }.let(::fixUrl)
            if (videoUrl.isNotBlank()) {
                val label = src.attr("title")
                val quality = when {
                    label?.contains("1080", ignoreCase = true) == true -> Qualities.P1080.value
                    label?.contains("720", ignoreCase = true) == true -> Qualities.P720.value
                    label?.contains("480", ignoreCase = true) == true -> Qualities.P480.value
                    label?.contains("360", ignoreCase = true) == true -> Qualities.P360.value
                    else -> getQualityFromUrl(videoUrl)
                }
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name ${label.ifBlank { "Video" }}",
                        url = videoUrl,
                        referer = data,
                        quality = quality,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        // Try to find download/embed links from quality buttons or download section
        // Sokuja uses sokuja.id/x.php?y=... encrypted download links
        document.select(".download a[href], .mirror a[href], a[data-link]").forEach { link ->
            val href = link.attr("abs:href").ifBlank { link.attr("href") }.let(::fixUrl)
            if (href.isNotBlank() && !href.contains("sokuja.id/x.php")) {
                val qualityText = link.text().trim()
                val quality = when {
                    qualityText.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                    qualityText.contains("720", ignoreCase = true) -> Qualities.P720.value
                    qualityText.contains("480", ignoreCase = true) -> Qualities.P480.value
                    qualityText.contains("360", ignoreCase = true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name ${qualityText.ifBlank { "Download" }}",
                        url = href,
                        referer = data,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        return true
    }

    private fun getQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720", ignoreCase = true) -> Qualities.P720.value
            url.contains("480", ignoreCase = true) -> Qualities.P480.value
            url.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
