package com.arranoust

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v2.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null

        // Cached singletons
        private val mapper = ObjectMapper()
        private val episodeNumRegex = Regex("Episode\\s?(\\d+)")
        private val yearRegex       = Regex("\\d, (\\d*)")

        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing"   -> ShowStatus.Ongoing
            else        -> ShowStatus.Completed
        }
    }

    // ================== Homepage ==================
    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d/" to "Episode Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val homeList = document
            .select("li[itemtype='http://schema.org/CreativeWork']")
            .mapNotNull { it.toLatestAnimeResult() }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList, isHorizontalImages = true)),
            hasNext = homeList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a          = selectFirst("div.animepost a, div.animpost a") ?: return null
        val title      = a.selectFirst("div.title h2, div.tt h4")?.text()?.trim()
                      ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href       = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl  = fixUrlNull(selectFirst("div.content-thumb img, div.limit img")?.attr("src"))
        val statusText = a.selectFirst("div.data > div.type, div.type")?.text()?.trim() ?: ""

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(statusText)
        }
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a         = selectFirst("div.thumb a") ?: return null
        val title     = selectFirst("h2.entry-title a")?.text()?.trim()
                     ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
        val epNum     = selectFirst("div.dtla author")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    // ================== Search ==================
    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document
            .select("div.animepost, article.animpost")
            .mapNotNull { it.toSearchResult() }
    }

    // ================== Load Anime ==================
    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) url
        else app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href") ?: url

        val document = app.get(fixUrl).document

        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: return null
        val title    = rawTitle
            .replace(Regex("(?i)(Nonton|Anime|Subtitle\\s+Indonesia|Sub\\s+Indo|Lengkap|Batch)"), "")
            .trim()

        val poster     = document.selectFirst("div.thumb > img")?.attr("src")
        val tags       = document.select("div.genre-info > a").map { it.text() }
        val year       = yearRegex.find(document.select("div.spe > span:contains(Rilis)").text())
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val statusStr  = document.selectFirst("div.spe > span:contains(Status)")
                            ?.ownText()?.replace(":", "")?.trim() ?: "Completed"
        val typeStr    = document.selectFirst("div.spe > span:contains(Type)")
                            ?.ownText()?.replace(":", "")?.trim()?.lowercase() ?: "tv"
        val type       = getType(typeStr)
        val rating     = document.selectFirst("span.ratingValue, div.rating strong")
                            ?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()
        val description = document.select("div.desc p, div.entry-content p").text().trim()
        val trailer     = document.selectFirst("div.trailer-anime iframe")?.attr("src")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId   = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbId:  Int?    = null
        var kitsuId: String? = null

        if (malId != null) {
            runCatching {
                val json  = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = parseAnimeData(json)
                tmdbId  = animeMetaData?.mappings?.themoviedbId
                kitsuId = animeMetaData?.mappings?.kitsuId
            }
        }

        val logoUrl         = fetchTmdbLogoUrl(type, tmdbId, "en")
        val backgroundPoster = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url
                             ?: tracker?.cover

        val episodes = document.select("div.lstepsiode.listeps ul li").amap { element ->
            val header     = element.selectFirst("span.lchx > a") ?: return@amap null
            val name       = header.text()
            var episodeNum = episodeNumRegex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (type == TvType.AnimeMovie && episodeNum == null) episodeNum = 1

            val link      = fixUrl(header.attr("href"))
            val metaEp    = episodeNum?.let { animeMetaData?.episodes?.get(it.toString()) }
            val epOverview = metaEp?.overview?.takeIf { it.isNotBlank() }
                          ?: "Synopsis not yet available."

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: name
                }
                this.episode     = episodeNum
                this.score       = Score.from10(metaEp?.rating)
                this.posterUrl   = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = epOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime     = metaEp?.runtime
            }
        }.filterNotNull().reversed()

        val recommendations = document
            .select("aside#sidebar ul li, div.relat animepost")
            .mapNotNull { it.toSearchResult() }

        val finalPlot = animeMetaData?.description?.replace(Regex("<.*?>"), "")
                     ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
                     ?: description

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName            = animeMetaData?.titles?.get("en") ?: title
            this.japName            = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl          = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundPoster
            runCatching { this.logoUrl = logoUrl }
            this.year               = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus         = getStatus(statusStr)
            this.score              = rating?.let { Score.from10(it) }
                                   ?: Score.from10(animeMetaData?.episodes?.get("1")?.rating)
            this.plot               = finalPlot
            addTrailer(trailer)
            this.tags               = tags
            this.recommendations    = recommendations
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            runCatching { addKitsuId(kitsuId) }
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

        document.select("div#downloadb li").amap { el ->
            val quality = el.select("strong").text()
            el.select("a").amap {
                loadExtractor(fixUrl(it.attr("href")), "$mainUrl/", subtitleCallback) { link ->
                    callback(newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer       = link.referer
                        this.quality       = quality.fixQuality()
                        this.headers       = link.headers
                        this.extractorData = link.extractorData
                    })
                }
            }
        }
        return true
    }

    // ================== Utils ==================
    private fun String.fixQuality(): Int = when (uppercase()) {
        "4K"     -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD"  -> Qualities.P720.value
        else     -> filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
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
        fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
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
            else             { if (better(best,    logo)) best    = logo }
        }
        return best?.let { urlOf(it) } ?: bestSvg?.let { urlOf(it) }
    }

    private fun parseAnimeData(jsonString: String): MetaAnimeData? =
        runCatching { mapper.readValue(jsonString, MetaAnimeData::class.java) }.getOrNull()

    // ================== Data Classes ==================
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url")       val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode")     val episode:     String?,
        @JsonProperty("airDateUtc")  val airDateUtc:  String?,
        @JsonProperty("runtime")     val runtime:     Int?,
        @JsonProperty("image")       val image:       String?,
        @JsonProperty("title")       val title:       Map<String, String>?,
        @JsonProperty("overview")    val overview:    String?,
        @JsonProperty("rating")      val rating:      String?,
        @JsonProperty("finaleType")  val finaleType:  String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles")      val titles:      Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images")      val images:      List<MetaImage>?,
        @JsonProperty("episodes")    val episodes:    Map<String, MetaEpisode>?,
        @JsonProperty("mappings")    val mappings:    MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int?    = null,
        @JsonProperty("kitsu_id")      val kitsuId:      String? = null
    )
}