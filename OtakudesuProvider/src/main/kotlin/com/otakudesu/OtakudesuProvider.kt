package com.otakudesu

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element

class OtakudesuProvider : MainAPI() {
    override var mainUrl            = "https://otakudesu.blog"
    override var name               = "Otakudesu"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private val mapper          = ObjectMapper()
        private val episodeNumRegex = Regex("""Episode\s?(\d+)""", RegexOption.IGNORE_CASE)
        private val yearRegex       = Regex("""\d{4}""")

        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true)                              -> TvType.AnimeMovie
            else                                                   -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when {
            t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
            else                                                         -> ShowStatus.Ongoing
        }
    }

    // ================== Homepage ==================
    override val mainPage = mainPageOf(
        "ongoing-anime/page/%d/"  to "Ongoing Anime",
        "complete-anime/page/%d/" to "Completed Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items    = document.select(".venz li .detpost").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a         = selectFirst(".thumb a") ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val title     = selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(selectFirst(".thumbz img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    // ================== Search ==================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.encodeUrl()}&post_type=anime").document
        val items    = document.select(".chivsrc li").mapNotNull { li ->
            val a      = li.selectFirst("h2 a") ?: return@mapNotNull null
            val href   = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title  = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = fixUrlNull(li.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
        if (items.isNotEmpty()) return items
        return document.select(".venz li .detpost").mapNotNull { it.toSearchResult() }
    }

    // ================== Load Anime ==================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val infoMap  = mutableMapOf<String, String>()
        document.select(".infozingle p span").forEach { span ->
            val text  = span.text()
            val colon = text.indexOf(':')
            if (colon == -1) return@forEach
            val label = text.substring(0, colon).trim().lowercase()
            val value = text.substring(colon + 1).trim()
            if (label.isNotBlank() && value.isNotBlank()) infoMap[label] = value
        }

        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: return null
        val title    = infoMap["judul"]?.trim() ?: rawTitle.trim()
        val engTitle = infoMap["english"]?.trim() ?: infoMap["judul inggris"]?.trim()
        val japTitle = infoMap["japanese"]?.trim() ?: infoMap["judul jepang"]?.trim()

        val poster    = document.selectFirst(".fotoanime img")?.attr("src")
        val synopsis  = document.select(".sinopc p").text().trim()
        val tags      = document.select(".infozingle p:contains(Genre) a").map { it.text() }
        val statusStr = infoMap["status"] ?: ""
        val typeStr   = infoMap["type"]   ?: ""
        val type      = getType(typeStr)
        val year      = yearRegex.find(infoMap["tanggal rilis"] ?: infoMap["rilis"] ?: "")
                            ?.value?.toIntOrNull()
        val trailer   = document.selectFirst("div.trailer-anime iframe, #trailer iframe")?.attr("src")

        val searchTitles = listOfNotNull(engTitle, title, japTitle).filter { it.isNotBlank() }
        val tracker      = APIHolder.getTracker(searchTitles, TrackerType.getTypes(type), year, true)
        val malId        = tracker?.malId

        var aniZip: AniZipData? = null
        if (malId != null) {
            runCatching {
                val json = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                aniZip   = mapper.readValue(json, AniZipData::class.java)
            }
        }

        val episodes = document.select(".episodelist ul li").amap { el ->
            val a    = el.selectFirst("span.eps a") ?: return@amap null
            val href = fixUrl(a.attr("href"))
            val name = a.text().trim()

            var epNum = episodeNumRegex.find(name)?.groupValues?.get(1)?.toIntOrNull()
            if (type == TvType.AnimeMovie && epNum == null) epNum = 1

            val aniEp = epNum?.let { aniZip?.episodes?.get(it.toString()) }

            newEpisode(href) {
                this.name        = aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: name
                this.episode     = epNum
                this.posterUrl   = aniEp?.image ?: aniZip?.images?.firstOrNull()?.url ?: ""
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() }
                this.runTime     = aniEp?.runtime
                this.addDate(aniEp?.airDateUtc)
            }
        }.filterNotNull().reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.engName             = engTitle ?: aniZip?.titles?.get("en") ?: title
            this.japName             = japTitle ?: aniZip?.titles?.get("ja")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = tracker?.cover
            this.year                = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus          = getStatus(statusStr)
            this.plot                = aniZip?.description?.replace(Regex("<.*?>"), "") ?: synopsis
            this.tags                = tags
            addTrailer(trailer)
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    // ================== Load Links ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // ── Stream mirrors (top) ──────────────────────────────────────────────
        // Each button has data-content = base64({"id":..,"i":..,"q":"360p"})
        // POST fields sent directly as form map (not encoded manually)
        val nonce = fetchNonce(data)
        if (nonce != null) {
            val ajaxUrl     = "$mainUrl/wp-admin/admin-ajax.php"
            val ajaxHeaders = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "User-Agent"       to UA,
                "Referer"          to data,
                "X-Requested-With" to "XMLHttpRequest"
            )

            document.select(".mirrorstream ul li a[data-content]").amap { btn ->
                val b64 = btn.attr("data-content").trim()
                    .takeIf { it.isNotBlank() && it != "#" } ?: return@amap

                val token = runCatching {
                    mapper.readTree(
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            .toString(Charsets.UTF_8)
                    )
                }.getOrNull() ?: return@amap

                val id          = token.get("id")?.asText() ?: return@amap
                val i           = token.get("i")?.asText() ?: ""
                val q           = token.get("q")?.asText() ?: ""
                val serverLabel = btn.text().trim()

                // POST form fields directly — matches JS source behavior
                val embedRes = runCatching {
                    app.post(
                        ajaxUrl,
                        headers = ajaxHeaders,
                        data    = mapOf(
                            "id"     to id,
                            "i"      to i,
                            "q"      to q,
                            "nonce"  to nonce,
                            "action" to "2a3505c93b0035d3f455df82bf976b84"
                        )
                    ).text
                }.getOrNull() ?: return@amap

                val embedB64   = runCatching { mapper.readTree(embedRes)?.get("data")?.asText() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: return@amap

                val iframeHtml = runCatching {
                    android.util.Base64.decode(embedB64, android.util.Base64.DEFAULT)
                        .toString(Charsets.UTF_8)
                }.getOrNull() ?: return@amap

                val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(iframeHtml)?.groupValues?.get(1)
                    ?: iframeHtml.trim().takeIf { it.startsWith("http") }
                    ?: return@amap

                // Custom extractors handle stream mirrors; built-in as fallback
                loadFixedExtractor(embedUrl, q, data, subtitleCallback, callback)
            }
        }

        // ── Download mirrors (bottom) ─────────────────────────────────────────
        // .download ul li → <strong>quality</strong> <a href="link.desustream.com/...">Server</a>
        // All servers via built-in loadExtractor (Pdrain, Mega, GoFile etc. all supported)
        document.select(".download ul li").amap { li ->
            val quality = li.selectFirst("strong")?.text()?.trim() ?: ""
            li.select("a[href]").amap { a ->
                val href = a.attr("href").trim().takeIf { it.startsWith("http") } ?: return@amap
                loadFixedExtractor(href, quality, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ── Fetch nonce from AJAX endpoint ────────────────────────────────────────
    private suspend fun fetchNonce(referer: String): String? = runCatching {
        val res = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            headers = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "User-Agent"       to UA,
                "Referer"          to referer,
                "X-Requested-With" to "XMLHttpRequest"
            ),
            data = mapOf("action" to "aa1208d27f29ca340c92c66d1926f13f")
        ).text
        mapper.readTree(res)?.get("data")?.asText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ── Wrap loadExtractor result to fix quality hint ─────────────────────────
    private suspend fun loadFixedExtractor(
        url: String,
        qualityHint: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer       = link.referer
                        this.quality       = link.quality
                            .takeIf { it != Qualities.Unknown.value }
                            ?: parseQuality(qualityHint, url)
                        this.headers       = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // ================== Data Classes ==================
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url")       val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipEpisode(
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime")    val runtime: Int?,
        @JsonProperty("image")      val image: String?,
        @JsonProperty("title")      val title: Map<String, String>?,
        @JsonProperty("overview")   val overview: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipData(
        @JsonProperty("titles")      val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images")      val images: List<AniZipImage>?,
        @JsonProperty("episodes")    val episodes: Map<String, AniZipEpisode>?,
    )
}