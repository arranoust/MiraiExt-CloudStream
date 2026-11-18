package com.sokuja

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class SokujaProvider : MainAPI() {
    override var mainUrl            = "https://x6.sokuja.uk"
    override var name               = "Sokuja"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null
        private const val UA         = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val mapper           = ObjectMapper()
        private val episodeNumRegex  = Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val yearRegex        = Regex("""\b(19|20)\d{2}\b""")
        private val nextImageRegex   = Regex("""[?&]url=([^&]+)""")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/"                                             to "Terbaru",
        "$mainUrl/anime/?status=ongoing&order=update&page=%d"   to "Ongoing Anime",
        "$mainUrl/anime/?status=completed&order=update&page=%d" to "Completed Anime",
        "$mainUrl/anime/?type=Movie&order=update&page=%d"       to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val url = if ("%d" in request.data) request.data.replace("%d", page.toString()) else request.data
        if (request.data == "$mainUrl/" && page > 1) {
            return newHomePageResponse(request.name, emptyList())
        }
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val items = if (request.data == "$mainUrl/") {
            parseHomeItems(doc)
        } else {
            doc.select("a.group.block").mapNotNull { it.toSearchResult() }
                .filter { it.url.contains("/anime/") }
                .distinctBy { it.url }
        }
        return newHomePageResponse(request.name, items)
    }

    private fun parseHomeItems(doc: Document): List<SearchResponse> {
        val items = LinkedHashMap<String, SearchResponse>()

        fun addAnimeResult(a: Element) {
            val href = a.attr("href").takeIf { it.contains("/anime/") } ?: return
            val title = a.selectFirst("h3, h4, .title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: return
            val fixed = fixUrl(href)
            if (items.containsKey(fixed)) return
            val typeText = a.selectFirst("span.absolute.left-2, .type")?.text() ?: ""
            items[fixed] = newAnimeSearchResponse(title, fixed, getType(typeText)) {
                this.posterUrl = a.selectFirst("img")?.extractNextImageUrl()
            }
        }

        doc.select("a[href*='/anime/']").forEach { addAnimeResult(it) }
        return items.values.toList()
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("h3")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val typeText = selectFirst("span.absolute.left-2")?.text() ?: ""
        return newAnimeSearchResponse(title, fixUrl(href), getType(typeText)) {
            this.posterUrl = selectFirst("img")?.extractNextImageUrl()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/", params = mapOf("s" to query), headers = mapOf("User-Agent" to UA))
            .document.select("a.group.block, a[href*='/anime/']").mapNotNull {
                val href = it.attr("href")
                if (!href.contains("/anime/")) null else it.toSearchResult()
            }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val headers = mapOf("User-Agent" to UA)
        val doc = app.get(url, headers = headers).document
        val rscText = runCatching { app.get(url, headers = headers + ("RSC" to "1")).text }.getOrDefault("")

        val jsonLd = findTvSeriesJsonLd(doc)
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: jsonLd?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()?.substringBefore(" -")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val title = rawTitle
            .replace(Regex("(?i)\\s*Sub(?:title)?\\s*Indo(?:nesia)?"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { rawTitle.trim() }

        val infoMap = buildInfoMap(doc)
        val typeText = infoMap["Tipe"] ?: infoMap["Type"] ?: jsonLd?.optString("@type") ?: ""
        val detectedType = getType(typeText)
        val year = yearRegex.find(infoMap["Tahun"] ?: infoMap["Year"] ?: infoMap["Rilis"] ?: infoMap["Musim"] ?: "")
            ?.value?.toIntOrNull()
            ?: jsonLd?.optString("datePublished")?.let { yearRegex.find(it)?.value?.toIntOrNull() }

        val tracker = APIHolder.getTracker(
            listOf(title, rawTitle, title.replace(Regex("(?i)\\s*Season\\s*\\d+:?"), "").trim()),
            TrackerType.getTypes(detectedType), year, true
        )
        val meta = fetchAniZipMeta(tracker?.malId)
        val logoUrl = fetchTmdbLogoUrl(detectedType, meta?.tmdbId, "en")

        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .ifEmpty {
                jsonLd?.optJSONArray("genre")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                } ?: emptyList()
            }
            .distinct()

        val synopsis = doc.selectFirst("div.prose")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: jsonLd?.optString("description")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: ""

        val posterEl: Element? = doc.selectFirst("img.object-cover, .thumb img, div[class*=poster] img, meta[property=og:image]")
        val poster = tracker?.image
            ?: posterEl?.let { el: Element ->
                if (el.tagName() == "meta") el.attr("content").takeIf { it.isNotBlank() }?.let(::fixUrlNull)
                else el.extractNextImageUrl()
            }
            ?: jsonLd?.optString("image")?.takeIf { it.isNotBlank() }?.let(::fixUrlNull)

        val siteEpisodes = parseEpisodesFromDom(doc)
        val rscEpisodes = parseEpisodesFromRsc(rscText)
        val mergedSiteEpisodes = if (rscEpisodes.isNotEmpty()) mergeEpisodes(siteEpisodes, rscEpisodes) else siteEpisodes
        val episodes = buildEpisodes(mergedSiteEpisodes, detectedType, meta, title)

        return newAnimeLoadResponse(title, url, detectedType) {
            this.engName = meta?.data?.titles?.get("en") ?: title
            this.japName = meta?.data?.titles?.get("ja") ?: meta?.data?.titles?.get("x-jat")
            this.posterUrl = poster
            this.backgroundPosterUrl = meta?.data?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover
            runCatching { this.logoUrl = logoUrl }
            this.year = year
            this.plot = meta?.data?.description?.replace(Regex("<.*?>"), "")?.takeIf { it.isNotBlank() }
                ?: meta?.data?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
                ?: synopsis
            this.tags = genres
            this.showStatus = getStatus(infoMap["Status"] ?: infoMap["status"] ?: "")
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            runCatching { addKitsuId(meta?.kitsuId) }
        }
    }

    private data class SiteEpisode(val url: String, val name: String?, val number: Int?)

    private fun parseEpisodesFromDom(doc: Document): List<SiteEpisode> {
        val seen = linkedSetOf<String>()
        val episodes = mutableListOf<SiteEpisode>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            if (!href.contains("-episode-", true) || !href.contains("subtitle-indonesia", true)) return@forEach
            val fixed = fixUrl(href.substringBefore("?"))
            if (!seen.add(fixed)) return@forEach
            val name = a.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
            val num = name?.let { episodeNumRegex.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt() }
                ?: episodeNumRegex.find(href)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
                ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            episodes.add(SiteEpisode(fixed, name, num))
        }
        return episodes
    }

    private fun parseEpisodesFromRsc(rscText: String): List<SiteEpisode> {
        if (rscText.isBlank()) return emptyList()
        val start = rscText.indexOf("\"episodes\":[")
        if (start == -1) return emptyList()

        var depth = 0
        var end = start + 12
        for (i in (start + 11) until rscText.length) {
            when (rscText[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i + 1
                        break
                    }
                }
            }
        }
        val arrayJson = rscText.substring(start + 11, end)
        return runCatching {
            mapper.readTree(arrayJson).mapNotNull { node ->
                val slug = node.get("slug")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = node.get("id")?.asInt()
                val num = node.get("episodeNumber")?.asInt()?.takeIf { it > 0 }
                val name = node.get("title")?.asText()?.takeIf { it.isNotBlank() }
                val epUrl = if (id != null) "$mainUrl/$slug/?eid=$id" else "$mainUrl/$slug/"
                SiteEpisode(epUrl, name, num)
            }
        }.getOrDefault(emptyList())
    }

    private fun mergeEpisodes(primary: List<SiteEpisode>, secondary: List<SiteEpisode>): List<SiteEpisode> {
        val byUrl = LinkedHashMap<String, SiteEpisode>()
        fun key(url: String) = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        primary.forEach { byUrl[key(it.url)] = it }
        secondary.forEach { sec ->
            val k = key(sec.url)
            val cur = byUrl[k]
            if (cur == null) {
                byUrl[k] = sec
            } else {
                byUrl[k] = SiteEpisode(
                    url = if (cur.url.contains("eid=")) cur.url else sec.url,
                    name = cur.name ?: sec.name,
                    number = cur.number ?: sec.number
                )
            }
        }
        return byUrl.values.toList()
    }

    private fun buildEpisodes(siteEpisodes: List<SiteEpisode>, type: TvType, meta: AniZipMeta?, animeTitle: String): List<Episode> {
        val aniEpisodes = meta?.data?.episodes ?: emptyMap()
        return siteEpisodes.mapIndexedNotNull { index, ep ->
            val epNum = ep.number
                ?: episodeNumRegex.find(ep.name ?: "")?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
                ?: episodeNumRegex.find(ep.url)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
                ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE).find(ep.url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val aniEp = matchAniEpisode(epNum, ep.name, index, aniEpisodes)
            val fallbackName = ep.name ?: epNum?.let { "Episode $it" } ?: "Episode ${index + 1}"
            val displayName = when (type) {
                TvType.AnimeMovie -> siteEpisodes.singleOrNull()?.let { animeTitle } ?: (ep.name ?: animeTitle)
                else -> fallbackName
            }
            newEpisode(ep.url) {
                this.name = displayName
                this.episode = epNum ?: aniEp?.key?.toIntOrNull()
                this.posterUrl = aniEp?.value?.image ?: meta?.data?.images?.firstOrNull()?.url
                this.description = aniEp?.value?.overview?.takeIf { it.isNotBlank() } ?: "Synopsis not yet available."
                this.runTime = aniEp?.value?.runtime
                this.addDate(aniEp?.value?.airDateUtc)
            }
        }.let { list -> if (list.size > 1) list.reversed() else list }
    }

    private fun matchAniEpisode(
        epNum: Int?,
        name: String?,
        index: Int,
        episodes: Map<String, AniZipEpisode>
    ): Map.Entry<String, AniZipEpisode>? {
        if (episodes.isEmpty()) return null
        epNum?.toString()?.let { key -> episodes[key]?.let { return java.util.AbstractMap.SimpleEntry(key, it) } }
        name?.let { n ->
            episodes.entries.firstOrNull { (_, v) -> v.title?.values?.any { it.equals(n, true) } == true }?.let { return it }
        }
        val byOrder = episodes.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
        return byOrder.getOrNull(index)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to UA)
        val episodeId = Regex("""[?&]eid=(\d+)""").find(data)?.groupValues?.get(1)
            ?: runCatching {
                val baseUrl = data.substringBefore("?")
                val slug = baseUrl.trimEnd('/').substringAfterLast("/")
                val rsc = app.get(baseUrl, headers = headers + ("RSC" to "1")).text
                Regex(""""id"\s*:\s*(\d+)\s*,\s*"slug"\s*:\s*"${Regex.escape(slug)}"""")
                    .find(rsc)?.groupValues?.get(1)
                    ?: Regex(""""slug"\s*:\s*"${Regex.escape(slug)}"\s*,\s*"id"\s*:\s*(\d+)""")
                        .find(rsc)?.groupValues?.get(1)
            }.getOrNull()

        if (episodeId != null) {
            val mirrors = runCatching {
                mapper.readValue(
                    app.get(
                        "$mainUrl/api/video-mirrors",
                        params = mapOf("e" to episodeId),
                        headers = headers + ("Referer" to data)
                    ).text,
                    VideoMirrorsResponse::class.java
                ).mirrors
            }.getOrDefault(emptyList())
            if (mirrors.isNotEmpty()) {
                mirrors.forEach { mirror ->
                    val url = mirror.embedUrl.takeIf { it.isNotBlank() } ?: return@forEach
                    callback(newExtractorLink(
                        mirror.serverName.ifBlank { name }, mirror.serverName.ifBlank { name }, url,
                        if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = parseQuality(mirror.quality)
                    })
                }
                return true
            }
        }

        val doc = app.get(data, headers = headers + ("Referer" to mainUrl)).document
        val embedUrls = linkedSetOf<String>()
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.startsWith("http")) embedUrls.add(src)
        }
        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.startsWith("http") && (href.contains("embed", true) || href.contains("player", true))) {
                embedUrls.add(href)
            }
        }
        embedUrls.forEach { embed ->
            loadExtractor(embed, data, subtitleCallback, callback)
        }
        return embedUrls.isNotEmpty()
    }

    private fun findTvSeriesJsonLd(doc: Document): JSONObject? {
        doc.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().trim()
            if (raw.isBlank()) return@forEach
            runCatching {
                val obj = JSONObject(raw)
                val type = obj.optString("@type")
                if (type == "TVSeries" || type == "Movie") return obj
            }
        }
        return null
    }

    private fun Element.extractNextImageUrl(): String? {
        val src = attr("abs:src").ifBlank { attr("src") }.ifBlank { attr("data-src") }.takeIf { it.isNotBlank() } ?: return null
        if (!src.contains("/_next/image/")) return if (src.startsWith("http")) src else fixUrl(src)
        val encoded = nextImageRegex.find(src)?.groupValues?.get(1) ?: return src
        val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return src
        return if (decoded.startsWith("http")) decoded else fixUrl(decoded)
    }

    private fun buildInfoMap(doc: Document): Map<String, String> {
        val map = linkedMapOf<String, String>()
        doc.select("div.flex.gap-3").forEach { row ->
            val k = row.selectFirst("dt")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val v = row.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            map[k] = v
        }
        if (map.isEmpty()) {
            doc.select("li, tr, div").forEach { row ->
                val text = row.ownText().ifBlank { row.text() }
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim()
                    if (k in setOf("Status", "Tipe", "Type", "Tahun", "Year", "Musim", "Rilis", "Studio") && v.isNotBlank()) {
                        map.putIfAbsent(k, v)
                    }
                }
            }
        }
        return map
    }

    private fun parseQuality(q: String?): Int = when {
        q == null                               -> Qualities.Unknown.value
        q.contains("2160") || q.contains("4K") -> Qualities.P2160.value
        q.contains("1080")                      -> Qualities.P1080.value
        q.contains("720")                       -> Qualities.P720.value
        q.contains("480")                       -> Qualities.P480.value
        q.contains("360")                       -> Qualities.P360.value
        else -> Regex("""(\d{3,4})""").find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getType(t: String): TvType = when {
        t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
        t.contains("Movie", true)                              -> TvType.AnimeMovie
        else                                                   -> TvType.Anime
    }

    private fun getStatus(t: String): ShowStatus = when {
        t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
        else                                                         -> ShowStatus.Ongoing
    }

    private data class AniZipMeta(val data: AniZipData, val tmdbId: Int?, val kitsuId: String?)

    private suspend fun fetchAniZipMeta(malId: Int?): AniZipMeta? {
        malId ?: return null
        return runCatching {
            val json = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
            val tree = mapper.readTree(json)
            AniZipMeta(
                data    = mapper.readValue(json, AniZipData::class.java),
                tmdbId  = tree?.get("mappings")?.get("themoviedb_id")?.asInt()?.takeIf { it != 0 },
                kitsuId = tree?.get("mappings")?.get("kitsu_id")?.asText()?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private suspend fun fetchTmdbLogoUrl(type: TvType, tmdbId: Int?, langCode: String?): String? {
        tmdbId ?: return null
        val segment = if (type == TvType.AnimeMovie) "movie" else "tv"
        val logos = runCatching {
            JSONObject(
                app.get("https://api.themoviedb.org/3/$segment/$tmdbId/images?api_key=98ae14df2b8d8f8f8136499daf79f0e0").text
            ).optJSONArray("logos")
        }.getOrNull()?.takeIf { it.length() > 0 } ?: return null

        val lang = langCode?.trim()?.lowercase()
        fun path(o: JSONObject) = o.optString("file_path")
        fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
        fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"
        fun score(o: JSONObject) = o.optDouble("vote_average", 0.0)
        fun count(o: JSONObject) = o.optInt("vote_count", 0)
        fun voted(o: JSONObject) = score(o) > 0 && count(o) > 0
        fun better(a: JSONObject?, b: JSONObject) = a == null ||
            score(b) > score(a) || (score(b) == score(a) && count(b) > count(a))

        var svgFallback: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (path(logo).isBlank() || logo.optString("iso_639_1").trim().lowercase() != lang) continue
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
        svgFallback?.let { return urlOf(it) }

        var best: JSONObject? = null
        var bestSvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (!voted(logo)) continue
            if (isSvg(logo)) {
                if (better(bestSvg, logo)) bestSvg = logo
            } else {
                if (better(best, logo)) best = logo
            }
        }
        return best?.let { urlOf(it) } ?: bestSvg?.let { urlOf(it) }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoMirror(
        @JsonProperty("id")         val id: Int?,
        @JsonProperty("serverName") val serverName: String = "",
        @JsonProperty("embedUrl")   val embedUrl: String = "",
        @JsonProperty("embedType")  val embedType: String? = null,
        @JsonProperty("quality")    val quality: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoMirrorsResponse(
        @JsonProperty("mirrors") val mirrors: List<VideoMirror> = emptyList()
    )

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
