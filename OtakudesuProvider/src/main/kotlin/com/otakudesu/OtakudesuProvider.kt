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

    override val mainPage = mainPageOf(
        "$mainUrl/complete-anime/page/" to "Complete Anime", 
        "$mainUrl/ongoing-anime/page/"  to "Ongoing Anime",
        "$mainUrl/complete-anime/page/" to "Completed Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document  = app.get(request.data + page).document
        val isBanner  = request.name == "Complete Anime"
        val items     = document.select("div.venz > ul > li").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = isBanner)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title     = selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href      = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = select("div.thumbz > img").attr("src").takeIf { it.isNotBlank() }
        val epNum     = selectFirst("div.epz")?.ownText()?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.encodeUrl()}&post_type=anime").document
        val items    = document.select(".chivsrc li").mapNotNull { li ->
            val a     = li.selectFirst("h2 a") ?: return@mapNotNull null
            val href  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = fixUrlNull(li.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
        if (items.isNotEmpty()) return items
        return document.select(".venz li .detpost").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: return null

        val poster   = document.selectFirst("div.fotoanime > img")?.attr("src")
        val synopsis = document.select("div.sinopc > p").text().trim()
        val tags     = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }

        val typeStr  = document.selectFirst("div.infozingle > p:nth-child(5) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: "tv"
        val type     = getType(typeStr)

        val statusStr = document.selectFirst("div.infozingle > p:nth-child(6) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: ""

        val year = yearRegex.find(
            document.select("div.infozingle > p:nth-child(9) > span").text()
        )?.value?.toIntOrNull()

        val trailer = document.selectFirst("div.trailer-anime iframe, #trailer iframe")?.attr("src")

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

        val episodes = document.select("div.episodelist").getOrNull(1)
            ?.select("ul > li")
            ?.amap { el ->
                val a    = el.selectFirst("a") ?: return@amap null
                val href = fixUrl(a.attr("href"))
                val name = a.text().trim()

                var epNum = episodeNumRegex.find(name)?.groupValues?.get(1)?.toIntOrNull()
                if (type == TvType.AnimeMovie && epNum == null) epNum = 1

                val aniEp = epNum?.let { aniZip?.episodes?.get(it.toString()) }

                newEpisode(href) {
                    this.name        = aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: name
                    this.episode     = epNum
                    this.posterUrl   = aniEp?.image ?: aniZip?.images?.firstOrNull()?.url ?: ""
                    this.description = aniEp?.overview?.takeIf { it.isNotBlank() } ?: "Synopsis not yet available."
                    this.runTime     = aniEp?.runtime
                    this.addDate(aniEp?.airDateUtc)
                }
            }?.filterNotNull()?.reversed() ?: emptyList()

        val recommendations = document.select("div.isi-recommend-anime-series > div.isi-konten")
            .mapNotNull { el ->
                val a      = el.selectFirst("a") ?: return@mapNotNull null
                val href   = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val rTitle = el.selectFirst("span.judul-anime > a")?.text() ?: return@mapNotNull null
                val rPoster = fixUrlNull(el.selectFirst("a > img")?.attr("src"))
                newAnimeSearchResponse(rTitle, href, TvType.Anime) { this.posterUrl = rPoster }
            }

        return newAnimeLoadResponse(title, url, type) {
            this.engName             = aniZip?.titles?.get("en") ?: title
            this.japName             = aniZip?.titles?.get("ja")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundPoster
            runCatching { this.logoUrl = logoUrl }
            this.year                = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus          = getStatus(statusStr)
            this.plot                = aniZip?.description?.replace(Regex("<.*?>"), "") ?: synopsis
            this.tags                = tags
            this.recommendations     = recommendations
            addTrailer(trailer)
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            runCatching { addKitsuId(kitsuId) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Extract nonce + embed action dynamically from inline script
        val scriptData = document.select("script")
            .firstOrNull { it.data().contains("admin-ajax") && it.data().contains("action") }
            ?.data() ?: ""

        val nonceAction = Regex("""data\s*:\s*\{action\s*:\s*["']([^"']+)["']""").find(scriptData)
            ?.groupValues?.get(1)
            ?: Regex("""action\s*:\s*["']([a-f0-9]{32})["']""").find(scriptData)
                ?.groupValues?.get(1)

        val embedAction = Regex("""nonce[^,]*,\s*action\s*:\s*["']([a-f0-9]{32})["']""").find(scriptData)
            ?.groupValues?.get(1)

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
                                data    = mapOf("id" to id, "i" to i, "q" to q,
                                                "nonce" to nonce, "action" to embedAction)
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

        document.select(".download ul li").amap { li ->
            val quality = li.selectFirst("strong")?.text()?.trim() ?: ""
            li.select("a[href]").amap { a ->
                val href = a.attr("href").trim().takeIf { it.startsWith("http") } ?: return@amap
                loadFixedExtractor(href, quality, data, subtitleCallback, callback)
            }
        }

        return true
    }

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

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

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