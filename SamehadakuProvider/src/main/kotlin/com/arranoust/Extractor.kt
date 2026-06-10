package com.arranoust

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// ─── Blogger ──────────────────────────────────────────────────────────────────
class BloggerExtractor : ExtractorApi() {
    override val name            = "Blogger"
    override val mainUrl         = "https://www.blogger.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).text

        val rawUrl = Regex(""""play_url"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
            ?: Regex(""""iurl"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
            ?: return

        val videoUrl = rawUrl
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        if (videoUrl.contains(".m3u8")) {
            M3u8Helper.generateM3u8(name, videoUrl, url).forEach(callback)
        } else {
            callback(newExtractorLink(name, name, videoUrl) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            })
        }
    }
}