package com.tokusatsuindo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class GdPlayer : ExtractorApi() {
    override val name = "GdPlayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to (referer ?: mainUrl)
            )
        ).text

        val videoUrl =
            Regex(""""file"\s*:\s*"(https?[^"]+\.(?:mp4|m3u8)[^"]*)"""")
                .find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            ?: Regex("""file\s*:\s*["'](https?[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                .find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            ?: Regex("""src\s*=\s*["'](https?[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                .find(html)?.groupValues?.getOrNull(1)
            ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}