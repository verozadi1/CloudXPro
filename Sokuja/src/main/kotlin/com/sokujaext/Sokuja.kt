package com.sokujaext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
    )

    // Category pages return WAF blocks — homepage only
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
    )

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return url
    }

    private fun fixUrlNull(url: String?): String? =
        if (url.isNullOrBlank()) null else fixUrl(url)

    /**
     * Validate URL is actually an anime detail page.
     * Anime detail: /anime/one-piece-subtitle-indonesia/
     * NOT nav links: /daftar-anime/, /genre/action/, etc.
     */
    private fun isValidAnimeUrl(href: String): Boolean {
        if (href.isEmpty()) return false
        if (!href.contains("/anime/")) return false
        if (href.count { it == '/' } < 5) return false
        // Skip nav/sidebar links
        if (href.contains("/episode-", ignoreCase = true)) return false
        if (href.contains("/daftar-", ignoreCase = true)) return false
        if (href.contains("/genre/", ignoreCase = true)) return false
        if (href.contains("/season/", ignoreCase = true)) return false
        if (href.contains("/studio/", ignoreCase = true)) return false
        if (href.contains("/director/", ignoreCase = true)) return false
        if (href.contains("/cast/", ignoreCase = true)) return false
        if (href.contains("/anime-type/", ignoreCase = true)) return false
        if (href.contains("/jadwal", ignoreCase = true)) return false
        if (href.endsWith("/anime/") || href.endsWith("/anime")) return false
        return true
    }

    private fun Element.toAnimeCard(): AnimeSearchResponse? {
        val linkEl = selectFirst("a[href*=/anime/]") ?: return null
        val href = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }
        if (!isValidAnimeUrl(fixUrl(href))) return null
        val poster = selectFirst("img")?.let {
            fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val title = selectFirst("b, strong")?.text()?.trim()
            ?: linkEl.attr("title")?.trim()
            ?: return null
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page > 1) "${request.data}${separator}page=$page" else request.data
        val document = app.get(url).document

        val home = mutableListOf<AnimeSearchResponse>()
        val seen = mutableSetOf<String>()

        // Strategy 1: Try standard WordPress/DooPlay card selectors
        val selectors = listOf(".bsx", ".animposfix", ".animepost", "article", ".bs")
        var parsed = false
        for (sel in selectors) {
            val items = document.select(sel)
            if (items.isNotEmpty()) {
                val results = items.mapNotNull { it.toAnimeCard() }
                if (results.isNotEmpty()) {
                    home.addAll(results)
                    parsed = true
                    break
                }
            }
        }

        // Strategy 2: Fallback — find all valid /anime/ links
        if (!parsed) {
            document.select("a[href*=/anime/]").forEach { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                val fixed = fixUrl(href)
                if (fixed in seen || !isValidAnimeUrl(fixed)) return@forEach
                seen.add(fixed)
                val card = a.closest("article, .bsx, .animposfix, .animepost, div") ?: a.parent() ?: a
                val poster = card.selectFirst("img")?.let {
                    fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
                }
                val title = card.selectFirst("b, strong")?.text()?.trim()
                    ?: a.attr("title")?.trim()
                    ?: return@forEach
                home.add(newAnimeSearchResponse(title, fixed, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }

        val hasNext = document.select(".pagination a, .hpage a, a.next, .page-numbers").isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, home), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(Regex("\\s+"), "+")
        val document = app.get("$mainUrl/?s=$q").document
        val results = mutableListOf<AnimeSearchResponse>()
        val seen = mutableSetOf<String>()

        document.select("a[href*=/anime/]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val fixed = fixUrl(href)
            if (fixed in seen || !isValidAnimeUrl(fixed)) return@forEach
            seen.add(fixed)
            val card = a.closest("article, .bsx, .animposfix, .animepost, div") ?: a.parent() ?: a
            val poster = card.selectFirst("img")?.let {
                fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") })
            }
            val title = card.selectFirst("b, strong")?.text()?.trim()
                ?: a.attr("title")?.trim()
                ?: return@forEach
            results.add(newAnimeSearchResponse(title, fixed, TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.title, .entry-title, h1")
            ?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("title")?.text()?.replace(Regex(" \\| .*"), "")?.trim()
            ?: throw ErrorLoadingException("Missing title")

        val poster = document.selectFirst("img.wp-post-image, .thumb img, .poster img, .thumbnail img")
            ?.let { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlNull)

        val synopsis = document.selectFirst(".entry-content, .desc, .sinop, .contentpm p, .sinops")
            ?.text()?.trim()?.ifBlank { null }

        val tags = document.select(".genre a, .genre-info a, .tag a, .meta-genre a")
            .map { it.text().trim().trimEnd(',') }.filter { it.isNotEmpty() }

        val year = Regex("(19|20)\\d{2}").find(
            document.selectFirst(".year, .spe span:contains(Tahun)")?.text()?.trim() ?: ""
        )?.value?.toIntOrNull()

        val typeText = document.selectFirst(".spe span:contains(Tipe)")?.text()?.trim() ?: ""

        // Parse episode list — try multiple selectors
        val episodeSelectors = listOf(
            ".lstepsiode ul li",
            ".episodelist ul li",
            ".episode-list li",
            ".bxcl li",
            ".listepisode li",
            ".epslist ul li",
            "#episodelist li",
        )
        val episodes = mutableListOf<Episode>()
        for (sel in episodeSelectors) {
            val items = document.select(sel)
            if (items.isNotEmpty()) {
                episodes.addAll(items.mapNotNull { epEl ->
                    val epA = epEl.selectFirst("a[href]") ?: return@mapNotNull null
                    val epHref = epA.attr("abs:href").ifBlank { epA.attr("href") }.let(::fixUrl)
                    val epName = epA.text().trim().ifBlank { epEl.text().trim() }
                    newEpisode(epHref) { this.name = epName }
                })
                if (episodes.isNotEmpty()) break
            }
        }
        if (episodes.size > 1) episodes.reverse()

        val isMovie = typeText.contains("Movie", ignoreCase = true)

        return if (isMovie || episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
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

        // 1. Try iframe embeds (if any exist statically)
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }.let(::fixUrl)
            if (src.isNotBlank() && src != "about:blank") {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. Try video source tags
        document.select("video source, source[src]").forEach { src ->
            val videoUrl = src.attr("abs:src").ifBlank { src.attr("src") }.let(::fixUrl)
            if (videoUrl.isNotBlank()) {
                val label = src.attr("title")
                callback(ExtractorLink(
                    source = name,
                    name = "$name ${label.ifBlank { "Video" }}",
                    url = videoUrl,
                    referer = data,
                    quality = when {
                        label.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                        label.contains("720", ignoreCase = true) -> Qualities.P720.value
                        label.contains("480", ignoreCase = true) -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    },
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ))
            }
        }

        // 3. Extract Google Drive file IDs from quality buttons
        // Sokuja uses buttons like: onclick="loadIframe('FILE_ID', ...)"
        // with nested <a href="https://drive.google.com/uc?id=FILE_ID&export=download">
        document.select(".playvideos button, .btn, [onclick*=loadIframe]").forEach { btn ->
            // Try to get drive URL from nested anchor
            val driveLink = btn.selectFirst("a[href*=drive.google.com]")?.attr("href")
            if (driveLink != null) {
                val fileId = Regex("id=([a-zA-Z0-9_-]+)").find(driveLink)?.groupValues?.get(1)
                if (fileId != null) {
                    loadExtractor("https://drive.google.com/file/d/$fileId/view", data, subtitleCallback, callback)
                }
            }
            // Try to get file ID from onclick handler
            val onclick = btn.attr("onclick")
            if (onclick.isNotEmpty()) {
                val fileId = Regex("'([a-zA-Z0-9_-]{20,})'").find(onclick)?.groupValues?.get(1)
                if (fileId != null) {
                    loadExtractor("https://drive.google.com/file/d/$fileId/view", data, subtitleCallback, callback)
                }
            }
        }

        // 4. Also look for drive links in post body
        document.select(".post-body a[href*=drive.google.com], .entry-content a[href*=drive.google.com]").forEach { link ->
            val driveUrl = link.attr("href")
            val fileId = Regex("id=([a-zA-Z0-9_-]+)").find(driveUrl)?.groupValues?.get(1)
                ?: Regex("/file/d/([a-zA-Z0-9_-]+)").find(driveUrl)?.groupValues?.get(1)
            if (fileId != null) {
                loadExtractor("https://drive.google.com/file/d/$fileId/view", data, subtitleCallback, callback)
            }
        }

        // 5. Fallback: look for any mp4/m3u8 links
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            if (href.contains(".mp4", ignoreCase = true) || href.contains(".m3u8", ignoreCase = true)) {
                callback(ExtractorLink(
                    source = name, name = "$name Direct",
                    url = fixUrl(href), referer = data,
                    quality = Qualities.Unknown.value,
                    type = if (fixUrl(href).contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ))
            }
        }

        return true
    }
}
