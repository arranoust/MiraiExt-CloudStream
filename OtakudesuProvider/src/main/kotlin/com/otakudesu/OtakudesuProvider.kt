package com.otakudesu

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
        "$mainUrl/ongoing-anime/page/"  to "Ongoing Anime",
        "$mainUrl/complete-anime/page/" to "Completed Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val items    = document.select("div.venz > ul > li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title     = selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href      = selectFirst("a")!!.attr("href")
        val posterUrl = select("div.thumbz > img").attr("src").toString()
        val epNum     = selectFirst("div.epz")?.ownText()
            ?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    // ================== Search ==================
    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type=anime").document
            .select("ul.chivsrc > li")
            .map {
                val title     = it.selectFirst("h2 > a")!!.ownText().trim()
                val href      = it.selectFirst("h2 > a")!!.attr("href")
                val posterUrl = it.selectFirst("img")!!.attr("src").toString()
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
            }
    }

    // ================== Load Anime ==================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: return null

        val poster    = document.selectFirst("div.fotoanime > img")?.attr("src")
        val synopsis  = document.select("div.sinopc > p").text()
        val tags      = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type      = getType(
            document.selectFirst("div.infozingle > p:nth-child(5) > span")
                ?.ownText()?.replace(":", "")?.trim() ?: "tv"
        )
        val statusStr = document.selectFirst("div.infozingle > p:nth-child(6) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: ""
        val year      = yearRegex.find(
            document.select("div.infozingle > p:nth-child(9) > span").text()
        )?.value?.toIntOrNull()
        val trailer   = document.selectFirst("div.trailer-anime iframe, #trailer iframe")?.attr("src")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId   = tracker?.malId

        var aniZip:  AniZipData? = null
        var tmdbId:  Int?        = null
        var kitsuId: String?     = null

        if (malId != null) {
            runCatching {
                val json = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                aniZip   = mapper.readValue(json, AniZipData::class.java)
                val tree = mapper.readTree(json)
                tmdbId   = tree?.get("mappings")?.get("themoviedb_id")?.asInt()?.takeIf { it != 0 }
                kitsuId  = tree?.get("mappings")?.get("kitsu_id")?.asText()?.takeIf { it.isNotBlank() }
            }
        }

        val logoUrl          = fetchTmdbLogoUrl(type, tmdbId, "en")
        val backgroundPoster = aniZip?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("div.episodelist")[1].select("ul > li").amap { element ->
            val name = element.selectFirst("a")?.text() ?: return@amap null
            var epNum = episodeNumRegex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val link  = fixUrl(element.selectFirst("a")!!.attr("href"))

            if (type == TvType.AnimeMovie && epNum == null) epNum = 1

            val aniEp = epNum?.let { aniZip?.episodes?.get(it.toString()) }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    aniZip?.titles?.get("en") ?: aniZip?.titles?.get("ja") ?: title
                } else {
                    aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: name
                }
                this.episode     = epNum
                this.posterUrl   = aniEp?.image ?: aniZip?.images?.firstOrNull()?.url ?: ""
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() }
                    ?: "Synopsis not yet available."
                this.runTime     = aniEp?.runtime
                this.addDate(aniEp?.airDateUtc)
            }
        }.filterNotNull().reversed()

        val recommendations = document
            .select("div.isi-recommend-anime-series > div.isi-konten")
            .map {
                val recName     = it.selectFirst("span.judul-anime > a")!!.text()
                val recHref     = it.selectFirst("a")!!.attr("href")
                val recPoster   = it.selectFirst("a > img")?.attr("src").toString()
                newAnimeSearchResponse(recName, recHref, TvType.Anime) { this.posterUrl = recPoster }
            }

        val finalPlot = aniZip?.description?.replace(Regex("<.*?>"), "")
            ?: aniZip?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: synopsis

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName             = aniZip?.titles?.get("en") ?: title
            this.japName             = aniZip?.titles?.get("ja") ?: aniZip?.titles?.get("x-jat")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundPoster
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.year                = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus          = getStatus(statusStr)
            this.plot                = finalPlot
            this.tags                = tags
            this.recommendations     = recommendations
            addTrailer(trailer)
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuId) } catch (_: Throwable) {}
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

        val scriptData = document.select("script")
            .firstOrNull { it.data().contains("admin-ajax") && it.data().contains("action") }
            ?.data() ?: ""

        val nonceAction = Regex("""data\s*:\s*\{action\s*:\s*["']([^"']+)["']""").find(scriptData)
            ?.groupValues?.get(1)
            ?: Regex("""action\s*:\s*["']([a-f0-9]{32})["']""").find(scriptData)
                ?.groupValues?.get(1)

        val embedAction = Regex("""nonce[^,]*,\s*action\s*:\s*["']([a-f0-9]{32})["']""")
            .find(scriptData)?.groupValues?.get(1)

        // Mirror stream — custom + built-in extractors via loadExtractor
        if (nonceAction != null && embedAction != null) {
            val nonce = runCatching {
                mapper.readTree(
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        headers = ajaxHeaders(data),
                        data    = mapOf("action" to nonceAction)
                    ).text
                )?.get("data")?.asText()?.takeIf { it.isNotBlank() }
            }.getOrNull()

            if (nonce != null) {
                document.select(".mirrorstream ul li a[data-content]").amap { btn ->
                    val b64 = btn.attr("data-content").trim()
                        .takeIf { it.isNotBlank() && it != "#" } ?: return@amap

                    val token = runCatching {
                        mapper.readTree(
                            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                .toString(Charsets.UTF_8)
                        )
                    }.getOrNull() ?: return@amap

                    val id = token.get("id")?.asText() ?: return@amap
                    val i  = token.get("i")?.asText()  ?: ""
                    val q  = token.get("q")?.asText()  ?: ""

                    val embedB64 = runCatching {
                        mapper.readTree(
                            app.post(
                                "$mainUrl/wp-admin/admin-ajax.php",
                                headers = ajaxHeaders(data),
                                data    = mapOf(
                                    "id"     to id,
                                    "i"      to i,
                                    "q"      to q,
                                    "nonce"  to nonce,
                                    "action" to embedAction
                                )
                            ).text
                        )?.get("data")?.asText()
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@amap

                    val iframeHtml = runCatching {
                        android.util.Base64.decode(embedB64, android.util.Base64.DEFAULT)
                            .toString(Charsets.UTF_8)
                    }.getOrNull() ?: return@amap

                    val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(iframeHtml)?.groupValues?.get(1)
                        ?: iframeHtml.trim().takeIf { it.startsWith("http") }
                        ?: return@amap

                    loadFixedExtractor(embedUrl, q, data, subtitleCallback, callback)
                }
            }
        }

        // Download — follow desustream redirect then built-in loadExtractor
        document.select(".download ul li").amap { li ->
            val quality = li.selectFirst("strong")?.text()?.trim() ?: ""
            li.select("a[href]").amap { a ->
                val href = a.attr("href").trim().takeIf { it.startsWith("http") } ?: return@amap
                val finalUrl = runCatching {
                    app.get(href, referer = "$mainUrl/").url
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: href
                loadFixedExtractor(finalUrl, quality, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ajaxHeaders(referer: String) = mapOf(
        "Content-Type"     to "application/x-www-form-urlencoded",
        "User-Agent"       to UA,
        "Referer"          to referer,
        "X-Requested-With" to "XMLHttpRequest"
    )

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

    private suspend fun fetchTmdbLogoUrl(type: TvType, tmdbId: Int?, langCode: String?): String? {
        if (tmdbId == null) return null
        val apiKey  = "98ae14df2b8d8f8f8136499daf79f0e0"
        val segment = if (type == TvType.AnimeMovie) "movie" else "tv"
        val json    = runCatching {
            JSONObject(app.get("https://api.themoviedb.org/3/$segment/$tmdbId/images?api_key=$apiKey").text)
        }.getOrNull() ?: return null

        val logos = json.optJSONArray("logos")?.takeIf { it.length() > 0 } ?: return null
        val lang  = langCode?.trim()?.lowercase()

        fun path(o: JSONObject)  = o.optString("file_path")
        fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
        fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"
        fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0
            && o.optInt("vote_count", 0) > 0
        fun better(a: JSONObject?, b: JSONObject): Boolean {
            if (a == null) return true
            return b.optDouble("vote_average", 0.0) > a.optDouble("vote_average", 0.0)
                || (b.optDouble("vote_average", 0.0) == a.optDouble("vote_average", 0.0)
                    && b.optInt("vote_count", 0) > a.optInt("vote_count", 0))
        }

        var svgFallback: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (path(logo).isBlank()) continue
            if (logo.optString("iso_639_1").trim().lowercase() != lang) continue
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
        svgFallback?.let { return urlOf(it) }

        var best: JSONObject?    = null
        var bestSvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (!voted(logo)) continue
            if (isSvg(logo)) { if (better(bestSvg, logo)) bestSvg = logo }
            else             { if (better(best, logo))    best    = logo }
        }
        return best?.let { urlOf(it) } ?: bestSvg?.let { urlOf(it) }
    }

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