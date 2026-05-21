package com.tokusatsuindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TokusatsuindoProvider : MainAPI() {
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

    // Homepage standar WordPress
    override val mainPage = mainPageOf(
        "page/%d/" to "Latest Update"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Jika page 1, url-nya mainUrl, jika lebih dari 1 pake format /page/2/
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        val hasNext = document.select(".next.page-numbers").isNotEmpty() || items.isNotEmpty()
        
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Ambil Judul
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" -") 
            ?: "Unknown Title"

        // Ambil Poster
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: document.selectFirst(".content-thumbnail img")?.attr("src")

        // Ambil Sinopsis
        val description = document.selectFirst(".entry-content p")?.text()?.trim()

        // Ambil Kategori/Tags
        val tags = document.select(".gmr-movie-on a").map { it.text().trim() }

        // Cek apakah halaman ini punya list episode (Class lcp_catlist)
        val episodeElements = document.select("ul.lcp_catlist li a")

        return if (episodeElements.isNotEmpty()) {
            // ==========================================
            // KONDISI 1: HALAMAN SERIES (Ada List Episode)
            // ==========================================
            val episodes = episodeElements.mapNotNull { ep ->
                val epUrl = ep.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epTitle = ep.text().trim()
                
                // Ambil angka episode dari teks
                val epNum = Regex("""(?i)episode\s*(\d+(?:\.\d+)?)""").find(epTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum?.toInt()
                }
            }.reversed() // Di-reverse karena di web episode paling baru ada di atas

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            // ==========================================
            // KONDISI 2: HALAMAN MOVIE / SINGLE EPISODE (Langsung Iframe Player)
            // ==========================================
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
        val htmlText = document.html()
        var linkFound = false

        // Wadah untuk ngumpulin semua link yang ketemu
        val allLinks = mutableListOf<String>()

        // 1. Ambil dari tag iframe biasa
        document.select("iframe").forEach {
            val src = it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            if (src.isNotBlank()) allLinks.add(src)
        }

        // 2. Jurus Regex khusus nangkap link GDPlayer & Google Drive yang tersembunyii
        val regex = Regex("""(https://(?:drive\.google\.com|gdplayer\.[a-z]+)[^"']+)""")
        regex.findAll(htmlText).forEach { match ->
            allLinks.add(match.groupValues[1])
        }

        // Hapus link yang dobel/duplikat, lalu eksekusi satu per satu
        allLinks.distinct().forEach { url ->
            val fixedUrl = if (url.startsWith("//")) "https:$url" else url
            
            runCatching {
                // loadExtractor Cloudstream sudah punya mesin bawaan untuk membongkar gdplayer.to
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                linkFound = true
            }
        }

        return linkFound
    }

    // Fungsi bantuan untuk parsing data card di Homepage dan Search
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst(".entry-title a") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        // Coba ambil dari tag img.wp-post-image
        val poster = selectFirst("img.wp-post-image")?.let { img ->
            img.attr("src").takeIf { it.isNotBlank() } 
        }

        // Return sementara sebagai TvSeries, nanti akan di validasi fix-nya saat di masuk fungsi load()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}
