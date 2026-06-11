package com.arranoust

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

// ── Filedon ───────────────────────────────────────────────────────────────────
// Embed page has data-page JSON attribute → props.url (MP4/MKV on filedon.uqni.net or r2)
class FiledonExtractor : ExtractorApi() {
    override val name            = "Filedon"
    override val mainUrl         = "https://filedon.co"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer"    to (referer ?: "$mainUrl/")
            )
        ).text

        val dataPage = Regex("""data-page="([^"]+)"""").find(body)
            ?.groupValues?.get(1) ?: return

        val json = dataPage
            .replace("&quot;", "\"")
            .replace("&amp;",  "&")
            .replace("&#039;", "'")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")

        val videoUrl = runCatching {
            JSONObject(json)
                .getJSONObject("props")
                .getString("url")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        // Accept MP4, MKV, and R2/CDN URLs (filedon.uqni.net, *.r2.*)
        val isPlayable = videoUrl.contains(".mp4", ignoreCase = true)
                      || videoUrl.contains(".mkv", ignoreCase = true)
                      || videoUrl.contains(".r2.")
                      || videoUrl.contains("uqni.net")

        if (!isPlayable) return

        callback(
            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Regex("""(\d{3,4})[pP]""").find(videoUrl)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
            }
        )
    }
}