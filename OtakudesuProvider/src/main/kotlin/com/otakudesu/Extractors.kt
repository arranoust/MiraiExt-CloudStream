package com.otakudesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

// ── Odstream ──────────────────────────────────────────────────────────────────
// Fetches embed page → unpacks JS eval → extracts .m3u8 / .mp4
class OdstreamExtractor : ExtractorApi() {
    override val name            = "Odstream"
    override val mainUrl         = "https://odstream.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = app.get(
            url,
            headers = mapOf("User-Agent" to UA, "Referer" to (referer ?: "$mainUrl/"))
        ).text

        val src      = tryUnpack(body) ?: body
        val videoUrl = VIDEO_PATTERNS.firstNotNullOfOrNull { it.find(src)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() } ?: return

        callback(
            newExtractorLink(name, name, videoUrl, detectLinkType(videoUrl)) {
                this.referer = "$mainUrl/"
                this.quality = parseQuality(videoUrl, url)
            }
        )
    }
}

// ── Ondesu ────────────────────────────────────────────────────────────────────
// Proxy → wraps a Blogger/googlevideo embed
class OndesuExtractor : ExtractorApi() {
    override val name            = "Ondesu"
    override val mainUrl         = "https://ondesu.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = app.get(
            url,
            headers = mapOf("User-Agent" to UA, "Referer" to (referer ?: mainUrl))
        ).text

        val bloggerUrl = Regex("""<iframe[^>]+src=["']([^"']*blogger\.com/video[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1) ?: return

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

// ── OndesuhExtractor ─────────────────────────────────────────────────────────
// Alternate Ondesu domain
class OndesuhExtractor : ExtractorApi() {
    override val name            = "Ondesuh"
    override val mainUrl         = "https://ondesuh.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = app.get(
            url,
            headers = mapOf("User-Agent" to UA, "Referer" to (referer ?: mainUrl))
        ).text

        val bloggerUrl = Regex("""<iframe[^>]+src=["']([^"']*blogger\.com/video[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1) ?: return

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

// ── Filedon ───────────────────────────────────────────────────────────────────
// data-page JSON → props.url
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
            headers = mapOf("User-Agent" to UA, "Referer" to (referer ?: "$mainUrl/"))
        ).text

        val raw      = Regex("""data-page="([^"]+)"""").find(body)?.groupValues?.get(1) ?: return
        val videoUrl = runCatching {
            JSONObject(raw.unescapeHtml()).getJSONObject("props").getString("url")
        }.getOrNull()?.takeIf { it.isNotBlank() && isPlayableUrl(it) } ?: return

        callback(
            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = parseQuality(videoUrl, url)
            }
        )
    }
}

// ── Vidhide ───────────────────────────────────────────────────────────────────
// JS-packed page → extract sources array
class VidhideExtractor : ExtractorApi() {
    override val name            = "Vidhide"
    override val mainUrl         = "https://vidhide.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body     = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val src      = tryUnpack(body) ?: body
        val videoUrl = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(src)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.m3u8[^"']{0,200}?)["']""").find(src)?.groupValues?.get(1)
            ?: return

        callback(
            newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ── Shared Utilities ──────────────────────────────────────────────────────────

internal const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val VIDEO_PATTERNS = listOf(
    Regex("""["'](https?://[^"']+\.m3u8[^"']{0,300}?)["']""", RegexOption.IGNORE_CASE),
    Regex("""file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
    Regex("""<source[^>]+src=["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
    Regex("""["'](https?://[^"']{15,}\.mp4[^"']{0,200}?)["']""", RegexOption.IGNORE_CASE),
)

internal fun detectLinkType(url: String): ExtractorLinkType =
    if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

internal fun isPlayableUrl(url: String): Boolean {
    val l = url.lowercase()
    return l.contains(".mp4") || l.contains(".mkv") || l.contains(".m3u8")
        || l.contains(".r2.") || l.contains("uqni.net")
}

internal fun parseQuality(vararg sources: String): Int {
    for (s in sources) {
        val v = Regex("""(\d{3,4})[pP]""").find(s)?.groupValues?.get(1)?.toIntOrNull()
        if (v != null) return v
    }
    return Qualities.Unknown.value
}

internal fun String.unescapeHtml(): String = replace("&quot;", "\"").replace("&amp;", "&")
    .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")

internal fun String.unescapeUnicode(): String = replace("\\u003d", "=")
    .replace("\\u0026", "&").replace("\\/", "/")

/** Best-effort p,a,c,k,e,d unpacker — returns null if not packed */
internal fun tryUnpack(source: String): String? {
    if (!source.contains("eval(function(p,a,c,k,e")) return null
    return runCatching {
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e[^)]*\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'""", RegexOption.DOT_MATCHES_ALL)
        val match = packedRegex.find(source) ?: return null
        val payload = match.groupValues[1]
        val radix   = match.groupValues[2].toIntOrNull() ?: 36
        val words   = match.groupValues[4].split("|")

        var result = payload
        words.forEachIndexed { i, word ->
            if (word.isNotEmpty()) {
                val key = i.toString(radix)
                result = result.replace(Regex("""\b${Regex.escape(key)}\b"""), word)
            }
        }
        result
    }.getOrNull()
}