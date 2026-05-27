package com.sad25kag.dracinema

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dracinema : MainAPI() {
    override var mainUrl = "https://www.dracinema.com"
    override var name = "Dracinema"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "collections" to "Koleksi",

        "genre/romantis" to "Romantis",
        "genre/balas-dendam" to "Balas Dendam",
        "genre/identitas-tersembunyi" to "Identitas Tersembunyi",
        "genre/dari-miskin-ke-kaya" to "Dari Miskin ke Kaya",
        "genre/pengkhianatan" to "Pengkhianatan",
        "genre/serangan-balik" to "Serangan Balik",
        "genre/terlahir-kembali" to "Terlahir Kembali",
        "genre/miliarder" to "Miliarder",
        "genre/romansa" to "Romansa",
        "genre/kontemporer" to "Kontemporer",
        "genre/modern" to "Modern",
        "genre/pahlawan-wanita-kuat" to "Pahlawan Wanita Kuat",
        "genre/keluarga" to "Keluarga",
        "genre/ceo--bos" to "CEO / Bos",
        "genre/fantasi" to "Fantasi",
        "genre/komedi" to "Komedi",
        "genre/perjalanan-waktu" to "Perjalanan Waktu",
        "genre/aksi" to "Aksi",
        "genre/misteri" to "Misteri",
        "genre/pernikahan-kontrak" to "Pernikahan Kontrak"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = commonHeaders).document

        if (request.data.isBlank() && page <= 1) {
            val rows = parseHomeRows(document)
            if (rows.isNotEmpty()) return newHomePageResponse(rows)
        }

        val list = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            list,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    "a:contains(Muat Lebih Banyak), " +
                    "button:contains(Muat Lebih Banyak), " +
                    "a[href*='page=${page + 1}'], " +
                    "a[href*='/page/${page + 1}']"
            ) != null || list.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl?page=$page"
            page <= 1 -> "$mainUrl/$cleanPath"
            cleanPath == "collections" -> "$mainUrl/collections?page=$page"
            cleanPath.startsWith("genre/") -> "$mainUrl/$cleanPath?page=$page"
            else -> "$mainUrl/$cleanPath?page=$page"
        }
    }

    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        document.select("section, main > div, div[class*=section]").forEach { section ->
            val title = section.selectFirst("h1, h2, h3")
                ?.text()
                ?.trim()
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach

            val items = section.select("a[href*='/movie/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                rows.add(HomePageList(title, items))
            }
        }

        if (rows.isEmpty()) {
            val fallback = parseCards(document)
            if (fallback.isNotEmpty()) {
                rows.add(HomePageList("Beranda", fallback))
            }
        }

        return rows.distinctBy { it.name }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "a[href*='/movie/'], " +
                "article a[href*='/movie/'], " +
                ".card a[href*='/movie/'], " +
                ".grid a[href*='/movie/'], " +
                ".swiper-slide a[href*='/movie/'], " +
                "[class*=movie] a[href*='/movie/']"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (!href.contains("/movie/", true)) return null

        val rawTitle = listOf(
            selectFirst("h1")?.text()?.trim(),
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst("[class*=title]")?.text()?.trim(),
            anchor.attr("title").trim(),
            anchor.attr("aria-label").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Putar Sekarang", true) &&
                !it.equals("Tonton Sekarang", true) &&
                !it.equals("Detail Info", true) &&
                !it.equals("Muat Lebih Banyak", true)
        } ?: return null

        val title = rawTitle.cleanTitle()
            .takeIf { it.length >= 2 }
            ?: return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.AsianDrama
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/search?q=$encoded&page=${page.coerceAtLeast(1)}",
            "$mainUrl/collections?search=$encoded&page=${page.coerceAtLeast(1)}",
            "$mainUrl/collections?q=$encoded&page=${page.coerceAtLeast(1)}",
            "$mainUrl/collections"
        )

        val results = linkedMapOf<String, SearchResponse>()

        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = commonHeaders).document
            }.getOrNull() ?: return@forEach

            parseCards(document)
                .filter { it.name.contains(keyword, ignoreCase = true) }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) return@forEach
        }

        return newSearchResponseList(
            results.values.toList(),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val title = document.selectFirst(
            "h1, " +
                "h1[class*=title], " +
                "meta[property=og:title], " +
                "meta[name=title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("?").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    "img[class*=poster], " +
                    "img[class*=cover], " +
                    "picture img, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "meta[name=description], " +
                ".description, " +
                "[class*=description], " +
                ".synopsis, " +
                "[class*=synopsis], " +
                "p"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document.select(
            "a[href*='/genre/'], " +
                "[class*=genre] a, " +
                "[class*=tag] a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document, url)
        val recommendations = parseCards(document)
            .filter { it.url != url }
            .distinctBy { it.url }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.AsianDrama,
            episodes
        ) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun parseEpisodes(
        document: Document,
        fallbackUrl: String
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode'], " +
                "a[href*='eps'], " +
                "a[href*='play'], " +
                "button[data-url], " +
                "button[data-src], " +
                "[class*=episode] a[href], " +
                "[class*=eps] a[href]"
        ).forEachIndexed { index, element ->
            val href = element.attr("href")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-src") }
                .trim()

            if (href.isBlank()) return@forEachIndexed

            val fixed = fixUrlNull(href) ?: return@forEachIndexed
            if (!fixed.startsWith(mainUrl)) return@forEachIndexed

            val text = element.text().trim()
            val epNum = extractEpisodeNumber(text, fixed) ?: index + 1

            episodes[fixed] = newEpisode(fixed) {
                name = text.ifBlank { "Episode $epNum" }
                episode = epNum
            }
        }

        return episodes.values
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(
                    newEpisode(fallbackUrl) {
                        name = "Full Episode"
                        episode = 1
                    }
                )
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = commonHeaders, referer = mainUrl)
        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        extractMediaUrls(html).forEach { raw ->
            val fixed = fixUrl(raw)

            when {
                fixed.contains(".m3u8", true) -> directLinks.add(fixed)
                fixed.contains(".mp4", true) -> directLinks.add(fixed)
                else -> embedLinks.add(fixed)
            }
        }

        document.select(
            "video source[src], " +
                "source[src], " +
                "video[src], " +
                "iframe[src], " +
                "embed[src], " +
                "a[href*='.m3u8'], " +
                "a[href*='.mp4'], " +
                "a[href*='embed'], " +
                "a[href*='player'], " +
                "[data-url], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .trim()

            if (raw.isBlank()) return@forEach

            val fixed = fixUrl(raw)

            when {
                fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        var found = false

        directLinks.forEach { link ->
            if (link.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = data
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = getQualityFromName(link).takeIf {
                            it != Qualities.Unknown.value
                        } ?: Qualities.Unknown.value
                    }
                )
            }

            found = true
        }

        embedLinks.forEach { embed ->
            val success = loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )

            if (success) found = true
        }

        return found
    }

    private fun extractMediaUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map {
                runCatching {
                    java.net.URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex("""(?:file|src|source|url|video|playUrl|videoUrl)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)-?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+Dracinema.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full Episode Subtitle Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}