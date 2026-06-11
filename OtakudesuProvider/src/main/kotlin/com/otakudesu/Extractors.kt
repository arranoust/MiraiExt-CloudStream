package com.otakudesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

internal const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val HTML_HEADERS = mapOf(
    "User-Agent" to UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
)

// ── Shared utils ──────────────────────────────────────────────────────────────

internal fun String.unescapeHtml(): String = replace("&quot;", "\"")
    .replace("&amp;", "&").replace("&#039;", "'")
    .replace("&lt;", "<").replace("&gt;", ">")

internal fun String.unescapeUnicode(): String = replace("\\u003d", "=")
    .replace("\\u0026", "&").replace("\\/", "/")

internal fun isPlayableUrl(url: String): Boolean {
    val l = url.lowercase()
    return l.contains(".mp4") || l.contains(".mkv") || l.contains(".m3u8")
        || l.contains(".r2.") || l.contains("uqni.net")
}

internal fun detectLinkType(url: String): ExtractorLinkType =
    if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
    else ExtractorLinkType.VIDEO

internal fun parseQuality(vararg sources: String): Int {
    for (s in sources) {
        val v = Regex("""(\d{3,4})[pP]""").find(s)?.groupValues?.get(1)?.toIntOrNull()
        if (v != null) return v
    }
    return Qualities.Unknown.value
}

/** Best-effort p,a,c,k,e,d unpacker */
internal fun tryUnpack(source: String): String? {
    if (!source.contains("eval(function(p,a,c,k,e")) return null
    return runCatching { getAndUnpack(source) }.getOrNull()
}

// ── OdstreamExtractor (resolveWithUnpack) ─────────────────────────────────────
class OdstreamExtractor : ExtractorApi() {
    override val name            = "Odstream"
    override val mainUrl         = "https://odstream.xyz"
    override val requiresReferer = true

    private val videoPatterns = listOf(
        Regex("""["'](https?://[^"']+\.m3u8[^"']{0,300}?)["']""", RegexOption.IGNORE_CASE),
        Regex("""file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""<source[^>]+src=["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"']{15,}\.mp4[^"']{0,200}?)["']""", RegexOption.IGNORE_CASE),
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = app.get(url, headers = HTML_HEADERS + mapOf("Referer" to (referer ?: "$mainUrl/"))).text
        val src  = tryUnpack(body) ?: body

        val videoUrl = videoPatterns.firstNotNullOfOrNull { it.find(src)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() } ?: return

        val origin = runCatching { java.net.URL(url).let { "${it.protocol}://${it.host}/" } }
            .getOrDefault(referer ?: "$mainUrl/")

        callback(
            newExtractorLink(name, name, videoUrl, detectLinkType(videoUrl)) {
                this.referer = origin
                this.quality = parseQuality(videoUrl, url)
            }
        )
    }
}

// ── FiledonExtractor (resolveFiledon) ─────────────────────────────────────────
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
        val body = app.get(url, headers = HTML_HEADERS + mapOf("Referer" to (referer ?: "$mainUrl/"))).text

        val raw      = Regex("""data-page="([^"]+)"""").find(body)?.groupValues?.get(1) ?: return
        val videoUrl = runCatching {
            JSONObject(raw.unescapeHtml()).getJSONObject("props").getString("url")
        }.getOrNull()?.takeIf { it.isNotBlank() && isPlayableUrl(it) } ?: return

        callback(
            newExtractorLink(name, name, videoUrl, detectLinkType(videoUrl)) {
                this.referer = "$mainUrl/"
                this.quality = parseQuality(videoUrl, url)
            }
        )
    }
}

// ── OndesuExtractor (resolveOndesu → resolveBlogger) ─────────────────────────
open class OndesuExtractor : ExtractorApi() {
    override val name            = "Ondesu"
    override val mainUrl         = "https://ondesu.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = app.get(url, headers = HTML_HEADERS + mapOf("Referer" to (referer ?: mainUrl))).text

        val bloggerUrl = Regex(
            """<iframe[^>]+src=["']([^"']*draft\.blogger\.com[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)
            ?: Regex(
                """<iframe[^>]+src=["'](https?://[^"']*blogger\.com/video[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)
            ?: return

        // Direct googlevideo link inside iframe src
        if (bloggerUrl.contains("googlevideo.com")) {
            callback(
                newExtractorLink(name, name, bloggerUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "https://www.blogger.com/"
                    this.quality = parseQuality(url, referer ?: "")
                }
            )
            return
        }

        // resolveBlogger
        val bBody    = app.get(bloggerUrl, headers = mapOf("User-Agent" to UA)).text
        val videoUrl = Regex(""""play_url"\s*:\s*"([^"]+)"""").find(bBody)?.groupValues?.get(1)
            ?: Regex(""""iurl"\s*:\s*"([^"]+)"""").find(bBody)?.groupValues?.get(1)
            ?: return

        callback(
            newExtractorLink(name, name, videoUrl.unescapeUnicode(), detectLinkType(videoUrl)) {
                this.referer = "https://www.blogger.com/"
                this.quality = parseQuality(url, referer ?: "")
            }
        )
    }
}

// ── OndesuhExtractor (alternate domain) ──────────────────────────────────────
class OndesuhExtractor : OndesuExtractor() {
    override val name    = "Ondesuh"
    override val mainUrl = "https://ondesuh.cc"
}