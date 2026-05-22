package com.tvtokusatsuindo // Lu bisa ganti package ini sesuai struktur folder lu, misal: com.tvtokusatsuindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TvTokusatsuIndoProvider : MainAPI() {
    override var mainUrl = "https://www.tvtokusatsuindo.com"
    override var name = "TV Tokusatsu Indo🤩"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama // LiveAction dihapus karena web tokusatsu cukup di-cover oleh TvSeries/AsianDrama
    )

    // Karena ini web Blogger, kita ambil halaman depannya langsung
    override val mainPage = mainPageOf(
        "/" to "Latest Update"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Simple pagination bypass untuk blogger
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val document = app.get(mainUrl).document
        val items = document.select("div.post.hentry").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search?q=$encoded").document
        
        return document.select("div.post.hentry").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleNode = selectFirst("h2.post-title a") ?: return null
        val href = titleNode.attr("href").trim()
        val title = titleNode.text().trim()
        
        // Ambil gambar dan maksimalkan resolusinya (Trik Blogger URL image /s0/)
        var poster = selectFirst("img.post-thumbnail")?.attr("src") 
            ?: selectFirst("img")?.attr("src")
        poster = poster?.replace(Regex("""/[sw]\d+(?:-h\d+)?(-c)?/"""), "/s0/")

        if (href.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Ekstraksi Judul
        val title = document.selectFirst("h2.post-title, h1.post-title, h3.post-title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.replace("TV Tokusatsu Indo", "")?.replace("-", "")?.trim()
            ?: "Unknown Title"

        // Ekstraksi Poster Original HD
        var poster = document.selectFirst("div.postbody2 img, div.entry-content img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        poster = poster?.replace(Regex("""/[sw]\d+(?:-h\d+)?(-c)?/"""), "/s0/")

        // Ekstraksi Plot / Sinopsis
        val plotText = document.selectFirst("div.postbody2, div.entry-content")?.text()
        val plot = if (plotText?.contains("Sinopsis", true) == true) {
            plotText.substringAfter("Sinopsis").substringBefore("Terima kasih").replace(title, "").trim()
        } else {
            plotText?.take(600)
        }

        val episodes = mutableListOf<Episode>()

        // =======================================================
        // METODE 1: Scrape dari Button onclick (Berdasarkan HTML lu)
        // =======================================================
        val buttons = document.select("div#episodevideo button.btn")
        if (buttons.isNotEmpty()) {
            buttons.forEach { btn ->
                val epName = btn.ownText().trim() // e.g., "Episode 1"
                val onclickAttr = btn.attr("onclick")
                
                // Ambil ID Gdrive dari -> loadIframe('10mRc3Y2mN5bOQHbTx037vYhy_gGiQy8Z', ...)
                val idMatch = Regex("""loadIframe\(['"]([^'"]+)['"]""").find(onclickAttr)
                val gDriveId = idMatch?.groupValues?.get(1)
                
                // Fallback kalau nemu ahref target Gdrive di dalam tag <a>
                val aHref = btn.selectFirst("a")?.attr("href")

                val targetUrl = if (gDriveId != null) {
                    "https://drive.google.com/file/d/$gDriveId/preview" // URL Valid buat Extractor GDrive
                } else if (aHref?.contains("drive.google.com") == true) {
                    aHref
                } else null

                if (targetUrl != null) {
                    val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode(targetUrl) {
                        this.name = epName
                        this.episode = epNum
                    })
                }
            }
        }

        // =======================================================
        // METODE 2: Scrape dari ul.lcp_catlist (Sesuai deskripsi lu)
        // =======================================================
        if (episodes.isEmpty()) {
            document.select("ul.lcp_catlist li a").forEach { a ->
                val epUrl = a.attr("href")
                val epName = a.text().trim()
                val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()

                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                })
            }
        }

        // =======================================================
        // PERHATIAN: LOGIKA IF/ELSE LOAD YANG LU MINTA
        // =======================================================
        return if (episodes.isNotEmpty()) {
            // TIPE 1: HALAMAN MEMILIKI DAFTAR EPISODE (SERI)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // TIPE 2: HALAMAN LANGSUNG PLAYER (MOVIE / EPISODE TUNGGAL)
            // Cari Iframe Gdrive di dalam halaman (Mencari iframe preview)
            val iframeSrc = document.selectFirst("iframe[src*='drive.google.com']")?.attr("src") ?: url
            
            newMovieLoadResponse(title, url, TvType.Movie, iframeSrc) {
                this.posterUrl = poster
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
        
        // Skenario 1: Jika 'data' sudah berupa link GDrive (dari Episode / Iframe halaman)
        if (data.contains("drive.google.com", true)) {
            // Built-in loadExtractor Cloudstream akan langsung mengekstrak GDrive secara otomatis
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // Skenario 2: Jika data ternyata nge-load halaman utuh (Backup logic)
        val document = app.get(data).document
        val iframes = document.select("iframe[src]")

        iframes.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
