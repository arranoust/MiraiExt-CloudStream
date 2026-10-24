package com.samehadaku

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl            = "https://v2.samehadaku.how"
    override var name               = "Samehadaku"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null

        private val mapper          = ObjectMapper()
        private val episodeNumRegex = Regex("""Episode\s?(\d+)""")
        private val yearRegex       = Regex("""\b(19|20)\d{2}\b""")

        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true)                              -> TvType.AnimeMovie
            else                                                   -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing"   -> ShowStatus.Ongoing
            else        -> ShowStatus.Completed
        }
    }

    // ================== Homepage ==================

    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d/"                                          to "New Episodes",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest"   to "Ongoing Anime",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=latest"    to "Completed Anime",
        "daftar-anime-2/page/%d/?type=Movie&order=latest"                 to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val doc      = app.get("$mainUrl/${request.data.format(page)}").document
        val isLatest = request.data.contains("anime-terbaru")
        val homeList = if (isLatest) {
            doc.select("li[itemtype='http://schema.org/CreativeWork'], li[itemtype='https://schema.org/CreativeWork']")
                .mapNotNull { it.toLatestAnimeResult() }
                .ifEmpty { doc.select("ul > li").mapNotNull { it.toLatestAnimeResult() } }
        } else {
            doc.select("div.animposx, article.animepost div.animposx, .relat .animposx")
                .mapNotNull { it.toSearchResult() }
                .ifEmpty { doc.select("div.animposx").mapNotNull { it.toSearchResult() } }
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList, isHorizontalImages = isLatest)),
            hasNext = homeList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a         = selectFirst("a") ?: return null
        val title     = selectFirst("div.data div.title h2")?.text()?.trim()
                     ?: selectFirst("h2")?.text()?.trim()
                     ?: selectFirst(".title h2")?.text()?.trim()
                     ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            selectFirst("div.content-thumb img")?.attr("src")
                ?: selectFirst("img.anmsa")?.attr("src")
                ?: selectFirst("img[itemprop=image]")?.attr("src")
                ?: a.selectFirst("img")?.attr("src")
        )
        val statusText = selectFirst("div.data > div.type")?.text()?.trim()
            ?: selectFirst(".data .type")?.text()?.trim()
            ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (statusText.isNotBlank()) addDubStatus(statusText)
        }
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val thumb = selectFirst("div.thumb") ?: this
        val a     = thumb.selectFirst("a") ?: selectFirst("h2.entry-title a") ?: selectFirst("a[itemprop=url]") ?: return null
        val title = selectFirst("h2.entry-title a")?.text()?.trim()
                  ?: selectFirst(".entry-title")?.text()?.trim()
                  ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href  = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            thumb.selectFirst("img")?.attr("src")
                ?: selectFirst("img")?.attr("src")
        )
        var epNum: Int? = null
        select("span").forEach { span ->
            val text = span.text()
            if (text.contains("Episode", true)) {
                epNum = episodeNumRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
        if (epNum == null) {
            epNum = selectFirst("div.dtla span author[itemprop=name]")?.text()?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    // ================== Search ==================

    override suspend fun search(query: String): List<SearchResponse> {
        return runCatching {
            val doc = app.get("$mainUrl/?s=$query").document
            val results = doc.select("div.animposx, article.animepost div.animposx, .animepost div.animposx")
                .mapNotNull { it.toSearchResult() }
            if (results.isNotEmpty()) results
            else doc.select("li[itemtype='http://schema.org/CreativeWork'], li[itemtype='https://schema.org/CreativeWork']")
                .mapNotNull { it.toLatestAnimeResult() }
        }.getOrDefault(emptyList())
    }

    // ================== Load ==================

    override suspend fun load(url: String): LoadResponse? {
        var fixUrl = url
        if (!url.contains("/anime/")) {
            runCatching {
                val d = app.get(url).document
                val animeLink = d.selectFirst("a[href*='/anime/']")?.attr("href")
                val nvsLink   = d.selectFirst("div.nvs.nvsc a")?.attr("href")
                fixUrl = fixUrlNull(animeLink).takeIf { !it.isNullOrBlank() }
                    ?: fixUrlNull(nvsLink).takeIf { !it.isNullOrBlank() }
                    ?: url
            }
        }
        val doc = app.get(fixUrl).document

        val rawTitle = doc.selectFirst("h1[itemprop=headline]")?.text()
            ?: doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst(".card h1")?.text()
            ?: return null
        val title = rawTitle
            .replace(Regex("(?i)(Nonton\\s+Streaming\\s+Anime\\s+|Download\\s+|Nonton\\s+|Anime\\s+|Subtitle\\s+Indonesia|Sub\\s+Indo|Lengkap|Batch)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val poster = doc.selectFirst(".w-48 img")?.attr("src")
            ?: doc.selectFirst("div.thumb > img")?.attr("src")
            ?: doc.selectFirst("img.object-cover")?.attr("src")
            ?: doc.selectFirst(".infoanime .thumb img")?.attr("src")

        val tags = doc.select("div.genre-info > a, .flex.items-center.gap-1\\.5.flex-wrap a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        var year: Int? = null
        var statusStr = "Completed"
        var typeStr = "tv"
        var rating: Double? = null
        var japName: String? = null
        var engName: String? = null

        doc.select(".card .grid > div, .grid.grid-cols-1 > div").forEach { row ->
            val label = row.selectFirst("span")?.text()?.lowercase() ?: return@forEach
            val value = row.select("span").lastOrNull()?.text()?.trim() ?: return@forEach
            when {
                label.contains("japanese") -> japName = value
                label.contains("english")  -> engName = value
                label.contains("status")   -> statusStr = value
                label.contains("type")     -> typeStr = value.lowercase()
                label.contains("rilis") || label.contains("released") -> year = yearRegex.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                label.contains("tahun")    -> year = year ?: value.filter { it.isDigit() }.take(4).toIntOrNull()
            }
        }
        if (year == null) {
            year = yearRegex.find(doc.select("div.spe > span:contains(Rilis), .infox").text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        if (statusStr == "Completed") {
            doc.selectFirst("div.spe > span:contains(Status)")?.ownText()?.replace(":", "")?.trim()?.let { if (it.isNotBlank()) statusStr = it }
        }
        if (typeStr == "tv") {
            doc.selectFirst("div.spe > span:contains(Type)")?.ownText()?.replace(":", "")?.trim()?.lowercase()?.let { if (it.isNotBlank()) typeStr = it }
        }

        rating = doc.selectFirst("span.ratingValue, div.rating strong")?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()
            ?: doc.select(".score").firstOrNull()?.text()?.replace("*", "")?.trim()?.toDoubleOrNull()

        val description = doc.select("div.entry-content.entry-content-single p, div.desc p, div.entry-content p")
            .joinToString("\n") { it.text().trim() }
            .trim()

        val trailer = doc.selectFirst("div.trailer-anime iframe")?.attr("src")

        val type = getType(typeStr)
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val meta    = fetchAniZipMeta(tracker?.malId)
        val logoUrl = fetchTmdbLogoUrl(type, meta?.tmdbId, "en")

        val backgroundPoster = meta?.data?.images?.find { it.coverType == "Fanart" }?.url
                            ?: tracker?.cover

        val episodeElements = mutableListOf<Element>()
        val seenEpisodeLinks = mutableSetOf<String>()
        doc.select(".max-h-\\[520px\\] .flex.items-center.gap-3, .listeps ul li, div.lstepsiode.listeps ul li, .episode-lainnya ul li").forEach { el ->
            val link = el.selectFirst("a[href*='-episode-'], a[href*='/episode']")?.attr("href")
                ?: el.selectFirst("h4 a")?.attr("href")
                ?: el.selectFirst(".lchx a")?.attr("href")
            if (!link.isNullOrBlank()) {
                val fixed = fixUrl(link)
                if (seenEpisodeLinks.add(fixed)) {
                    episodeElements.add(el)
                }
            }
        }
        if (episodeElements.isEmpty()) {
            doc.select("a[href*='-episode-']").forEach { a ->
                val fixed = fixUrl(a.attr("href"))
                if (seenEpisodeLinks.add(fixed)) {
                    a.parents().firstOrNull { it.tagName() == "div" || it.tagName() == "li" }?.let { episodeElements.add(it) }
                }
            }
        }

        val episodes = episodeElements.amap { el ->
            val header = el.selectFirst("a[href*='-episode-'], h4 a, .lchx a, a") ?: return@amap null
            val link   = fixUrl(header.attr("href"))
            val name   = header.text().ifBlank { el.selectFirst("h4")?.text() ?: el.text() }
            var epNum  = episodeNumRegex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (type == TvType.AnimeMovie && epNum == null) epNum = 1

            val aniEp = epNum?.let { meta?.data?.episodes?.get(it.toString()) }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    meta?.data?.titles?.get("en") ?: meta?.data?.titles?.get("ja") ?: title
                } else {
                    aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: name
                }
                this.episode     = epNum
                this.score       = Score.from10(aniEp?.rating)
                this.posterUrl   = aniEp?.image ?: meta?.data?.images?.firstOrNull()?.url ?: poster ?: ""
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() } ?: "Synopsis not yet available."
                this.addDate(aniEp?.airDateUtc)
                this.runTime     = aniEp?.runtime
            }
        }.filterNotNull().let { list ->
            if (list.size > 1) list.reversed() else list
        }

        val recommendations = doc.select(".recom-section .animposx, ul li a.series, .relat .animposx")
            .mapNotNull { el ->
                if (el.tagName() == "a" && el.hasClass("series")) {
                    val recTitle  = el.selectFirst("span.judul")?.text()?.trim() ?: el.text().trim()
                    val recHref   = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val recPoster = fixUrlNull(el.selectFirst("img")?.attr("src"))
                    val recEpNum  = Regex("""\d+""").find(el.selectFirst("span.episode")?.text() ?: "")?.value?.toIntOrNull()
                    if (recTitle.isBlank()) return@mapNotNull null
                    newAnimeSearchResponse(recTitle, recHref, TvType.Anime) {
                        this.posterUrl = recPoster
                        addSub(recEpNum)
                    }
                } else {
                    val res = el.toSearchResult()
                    res
                }
            }
            .filter { it.name.isNotBlank() }

        val finalPlot = meta?.data?.description?.replace(Regex("<.*?>"), "")
                     ?: meta?.data?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
                     ?: description
                     ?: ""

        return newAnimeLoadResponse(title, url, type) {
            this.engName             = engName ?: meta?.data?.titles?.get("en") ?: title
            this.japName             = japName ?: meta?.data?.titles?.get("ja") ?: meta?.data?.titles?.get("x-jat")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundPoster
            runCatching { this.logoUrl = logoUrl }
            this.year                = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus          = getStatus(statusStr)
            this.score               = rating?.let { Score.from10(it) }
                                    ?: Score.from10(meta?.data?.episodes?.get("1")?.rating)
            this.plot                = finalPlot
            addTrailer(trailer)
            this.tags                = tags
            this.recommendations     = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            runCatching { addKitsuId(meta?.kitsuId) }
        }
    }

    // ================== Load Links ==================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Downloads
        doc.select("div#downloadb li").amap { li ->
            val quality = li.selectFirst("strong")?.text()?.trim() ?: ""
            li.select("a[href]").amap { a ->
                val href = a.attr("href")
                if (href.isNotBlank()) {
                    loadFixedExtractor(fixUrl(href), quality, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        // Streams - Prefer data-embed (new theme)
        var foundStream = false
        doc.select(".east_player_option[data-embed], .player-server-btn[data-embed]").forEach { btn ->
            val embedHtml = unescapeHtml(btn.attr("data-embed"))
            if (embedHtml.isBlank()) return@forEach
            val iframeUrl = Regex("""src=["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
                ?: return@forEach
            val label = btn.selectFirst("span")?.text()?.trim().takeIf { !it.isNullOrBlank() }
                ?: btn.text().trim().takeIf { it.isNotBlank() }
                ?: "Mirror"
            foundStream = true
            if (isDirectVideoUrl(iframeUrl) || iframeUrl.contains("wibufile.com", ignoreCase = true) || iframeUrl.contains("blogger.com", ignoreCase = true)) {
                callback(
                    newExtractorLink(label, label, iframeUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                        this.quality = label.parseQualityLabel()
                    }
                )
            } else {
                loadFixedExtractor(iframeUrl, label, "$mainUrl/", subtitleCallback, callback)
            }
        }

        if (!foundStream) {
            // Streams - Legacy AJAX
            doc.select("div.east_player_option[data-post][data-nume]").amap { btn ->
                val postId = btn.attr("data-post").takeIf { it.isNotBlank() } ?: return@amap
                val nume   = btn.attr("data-nume").takeIf { it.isNotBlank() } ?: return@amap
                val type   = btn.attr("data-type").takeIf { it.isNotBlank() } ?: "schtml"
                val label  = btn.selectFirst("span")?.text()?.trim() ?: "Mirror $nume"

                val iframeUrl = fetchStreamIframe(postId, nume, type) ?: return@amap

                if (isDirectVideoUrl(iframeUrl) || iframeUrl.contains("wibufile.com", ignoreCase = true)) {
                    callback(
                        newExtractorLink(label, label, iframeUrl, ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                            this.quality = label.parseQualityLabel()
                        }
                    )
                } else {
                    loadFixedExtractor(iframeUrl, label, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    // ================== Helpers ==================

    private fun unescapeHtml(text: String): String {
        if (text.isBlank()) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
    }

    private suspend fun fetchStreamIframe(postId: String, nume: String, type: String): String? =
        runCatching {
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data    = mapOf("action" to "player_ajax", "post" to postId, "nume" to nume, "type" to type),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/")
            ).text
            val raw   = runCatching { mapper.readTree(response)?.get("embed")?.asText() }.getOrNull() ?: response
            val embed = unescapeHtml(raw)
            Regex("""src=["']([^"']+)["']""").find(embed)?.groupValues?.get(1)
        }.getOrNull()

    private suspend fun loadFixedExtractor(
        url: String,
        qualityHint: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer       = link.referer
                        this.quality       = link.quality.takeIf { it != Qualities.Unknown.value }
                                         ?: qualityHint.parseQualityLabel()
                        this.headers       = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".mp4") || l.contains(".mkv") || l.contains(".webm") || l.contains(".m3u8")
    }

    private fun String.parseQualityLabel(): Int {
        val u = uppercase()
        return when {
            u.contains("4K")     -> Qualities.P2160.value
            u.contains("FULLHD") -> Qualities.P1080.value
            u.contains("MP4HD")  -> Qualities.P720.value
            else -> Regex("""(\d{3,4})[pP]?""").findAll(this)
                        .lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
        }
    }

    // ================== Metadata Fetchers ==================

    private data class AniZipMeta(val data: MetaAnimeData, val tmdbId: Int?, val kitsuId: String?)

    private suspend fun fetchAniZipMeta(malId: Int?): AniZipMeta? {
        malId ?: return null
        return runCatching {
            val data = mapper.readValue(
                app.get("https://api.ani.zip/mappings?mal_id=$malId").text,
                MetaAnimeData::class.java
            )
            AniZipMeta(
                data    = data,
                tmdbId  = data.mappings?.themoviedbId?.takeIf { it != 0 },
                kitsuId = data.mappings?.kitsuId?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private suspend fun fetchTmdbLogoUrl(type: TvType, tmdbId: Int?, langCode: String?): String? {
        tmdbId ?: return null
        val segment = if (type == TvType.AnimeMovie) "movie" else "tv"
        val logos   = runCatching {
            JSONObject(
                app.get("https://api.themoviedb.org/3/$segment/$tmdbId/images?api_key=98ae14df2b8d8f8f8136499daf79f0e0").text
            ).optJSONArray("logos")
        }.getOrNull()?.takeIf { it.length() > 0 } ?: return null

        val lang = langCode?.trim()?.lowercase()
        fun path(o: JSONObject)  = o.optString("file_path")
        fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
        fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"
        fun score(o: JSONObject) = o.optDouble("vote_average", 0.0)
        fun count(o: JSONObject) = o.optInt("vote_count", 0)
        fun voted(o: JSONObject) = score(o) > 0 && count(o) > 0
        fun better(a: JSONObject?, b: JSONObject) = a == null || score(b) > score(a)
            || (score(b) == score(a) && count(b) > count(a))

        var svgFallback: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (path(logo).isBlank() || logo.optString("iso_639_1").trim().lowercase() != lang) continue
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
        svgFallback?.let { return urlOf(it) }

        var best: JSONObject? = null; var bestSvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (!voted(logo)) continue
            if (isSvg(logo)) { if (better(bestSvg, logo)) bestSvg = logo }
            else             { if (better(best,    logo)) best    = logo }
        }
        return best?.let { urlOf(it) } ?: bestSvg?.let { urlOf(it) }
    }

    // ================== Data Classes ==================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url")       val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode")    val episode:    String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime")    val runtime:    Int?,
        @JsonProperty("image")      val image:      String?,
        @JsonProperty("title")      val title:      Map<String, String>?,
        @JsonProperty("overview")   val overview:   String?,
        @JsonProperty("rating")     val rating:     String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int?    = null,
        @JsonProperty("kitsu_id")      val kitsuId:      String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles")      val titles:      Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images")      val images:      List<MetaImage>?,
        @JsonProperty("episodes")    val episodes:    Map<String, MetaEpisode>?,
        @JsonProperty("mappings")    val mappings:    MetaMappings? = null
    )
}
