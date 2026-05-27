package com.JAVHd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Stbturbo : ExtractorApi() {
    override var name = "Stbturbo"
    override var mainUrl = "https://stbturbo.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val candidates = linkedSetOf<String>()

        response.document.selectFirst("#video_player[data-hash]")
            ?.attr("data-hash")
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(httpsify(it)) }

        extractDirectUrls(html).forEach { candidates.add(httpsify(it)) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractDirectUrls(unpacked.cleanEscaped()).forEach { candidates.add(httpsify(it)) }
        }

        candidates.distinct().forEach { link ->
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                        ?: qualityFromUrl(link)
                }
            )
        }
    }
}

class Turbovid : Stbturbo() {
    override var name = "Turbovid"
    override var mainUrl = "https://turbovid.xyz"
}

class MyCloudZ : VidhideExtractor() {
    override var name = "MyCloudZ"
    override var mainUrl = "https://mycloudz.cc"
    override var requiresReferer = false
}

class Cloudwish : StreamWishExtractor() {
    override var name = "Cloudwish"
    override var mainUrl = "https://cloudwish.xyz"
    override var requiresReferer = false
}

private fun extractDirectUrls(text: String): List<String> {
    val clean = text.cleanEscaped()
    val urls = linkedSetOf<String>()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped() }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|src|source|url|hls|videoUrl)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped() }
        .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()
}
