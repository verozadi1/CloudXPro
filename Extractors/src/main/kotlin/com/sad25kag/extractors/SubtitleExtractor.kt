package com.sad25kag.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.collections.forEach
import kotlin.collections.ifEmpty

class SubtitleCat : ExtractorApi() {
    override val name = "SubtitleCat"
    override val mainUrl = "https://subtitlecat.com"
    override val requiresReferer = false

    private fun String.normalize(): String {
        return this.filter { c -> c.isLetterOrDigit() }.lowercase()
    }

    private val codeRegex = Regex("""[a-z]+-\d+""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val query = url.substringAfter("query=").let { codeRegex.find(it)?.value } ?: return
        val queryUrl = "${mainUrl}/index.php?search=$query"
        val doc = app.get(queryUrl).document
        val subs = doc.select(".sub-table a")
            .map { mainUrl + '/' + it.attr("href") }
            .take(3)
            .filter {
                it.normalize().contains(query.normalize())
            }
            .ifEmpty { return }

        CoroutineScope(Dispatchers.IO).launch {
            subs.forEach { subUrl ->
                launch {
                    val subPageDoc = app.get(subUrl).document
                    val href =
                        subPageDoc.getElementById("download_en")?.attr("href") ?: return@launch

                    subtitleCallback(newSubtitleFile("English", mainUrl + href))
                }
            }
        }
    }
}