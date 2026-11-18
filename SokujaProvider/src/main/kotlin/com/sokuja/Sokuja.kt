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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class SokujaProvider : MainAPI() {
    override var mainUrl = "https://x6.sokuja.uk"
    override var name = "Sokuja"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val mapper = ObjectMapper()
        private val episodeNumRegex = Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
        private val nextImageRegex = Regex("""[?&]url=([^&]+)""")
    }

    private val headers get() = mapOf("User-Agent" to UA)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=ongoing&order=update&page=%d" to "Ongoing Anime",
        "$mainUrl/anime/?status=completed&order=update&page=%d" to "Completed Anime",
        "$mainUrl/anime/?type=Movie&order=update&page=%d" to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val doc = app.get(request.data.replace("%d", page.toString()), headers = headers).document
        val items = parseAnimeCards(doc)
        if (page == 1) {
            val homeDoc = runCatching { app.get(mainUrl, headers = headers).document }.getOrNull()
            val latest = homeDoc?.let { parseLatestEpisodeItems(it) } ?: emptyList()
            val lists = buildList {
                if (latest.isNotEmpty()) add(HomePageList("Update Terbaru", latest, isHorizontalImages = true))
                add(HomePageList(request.name, items))
            }
            return newHomePageResponse(lists, hasNext = items.isNotEmpty())
        }
        return newHomePageResponse(request.name, items)
    }

    private fun parseAnimeCards(doc: Document): List<SearchResponse> {
        return doc.select("a.group.block").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.contains("/anime/") } ?: return@mapNotNull null
            a.toAnimeSearchResult(fixUrl(href))
        }.distinctBy { it.url }
    }

    private fun parseLatestEpisodeItems(doc: Document): List<SearchResponse> {
        val items = LinkedHashMap<String, SearchResponse>()
        doc.select("a.group.block[href*='-episode-'][href*='subtitle-indonesia']").forEach { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            val fixed = fixUrl(href.substringBefore("?"))
            if (items.containsKey(fixed)) return@forEach
            val title = a.selectFirst("h3")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: return@forEach
            val poster = a.selectFirst("img")?.extractNextImageUrl()
            val typeText = a.select("span").map { it.text() }
                .firstOrNull { it.equals("TV", true) || it.equals("Movie", true) || it.contains("OVA", true) || it.contains("ONA", true) }
                ?: ""
            val epNum = a.select("span").firstNotNullOfOrNull { span ->
                Regex("""\bEP\s*(\d+)""", RegexOption.IGNORE_CASE).find(span.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            } ?: episodeNumRegex.find(a.text())?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()

            items[fixed] = newAnimeSearchResponse(title, fixed, getType(typeText)) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }
        return items.values.toList()
    }

    private fun Element.toAnimeSearchResult(fixedHref: String? = null): AnimeSearchResponse? {
        val href = fixedHref ?: attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return null
        val title = selectFirst("h3")?.text()?.trim()
            ?: selectFirst("h4")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val typeText = selectFirst("span.absolute.left-2")?.text()
            ?: select("span").map { it.text() }.firstOrNull { it in setOf("TV", "Movie", "OVA", "ONA", "Special") }
            ?: ""
        return newAnimeSearchResponse(title, href, getType(typeText)) {
            this.posterUrl = selectFirst("img")?.extractNextImageUrl()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/", params = mapOf("s" to query), headers = headers).document
        return doc.select("a.group.block, a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.contains("/anime/")) null else a.toAnimeSearchResult(fixUrl(href))
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val initial = app.get(url, headers = headers)
        var doc = initial.document
        var finalUrl = initial.url.ifBlank { url }
        var rscText = runCatching { app.get(finalUrl, headers = headers + ("RSC" to "1")).text }.getOrDefault("")
        var pageText = doc.outerHtml()

        if (isEpisodePage(finalUrl, doc)) {
            val animeUrl = findAnimeUrlFromEpisode(doc, pageText, rscText)
            if (animeUrl != null) {
                finalUrl = fixUrl(animeUrl)
                doc = app.get(finalUrl, headers = headers).document
                rscText = runCatching { app.get(finalUrl, headers = headers + ("RSC" to "1")).text }.getOrDefault("")
                pageText = doc.outerHtml()
            }
        }

        val jsonLd = findJsonLd(doc, "TVSeries") ?: findJsonLd(doc, "Movie")
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: jsonLd?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.substringBefore("-")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val title = rawTitle
            .replace(Regex("(?i)\\s*Sub(?:title)?\\s*Indo(?:nesia)?"), "")
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { rawTitle.trim() }

        val infoMap = buildInfoMap(doc)
        val typeText = infoMap["Tipe"] ?: infoMap["Type"] ?: if (jsonLd?.optString("@type") == "Movie") "Movie" else ""
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

        val domEpisodes = parseEpisodesFromDom(doc)
        val payloadEpisodes = extractEpisodesFromText(pageText) + extractEpisodesFromText(rscText)
        val mergedEpisodes = mergeEpisodes(domEpisodes, payloadEpisodes)
        val episodes = buildEpisodes(mergedEpisodes, detectedType, meta, title)

        return newAnimeLoadResponse(title, finalUrl, detectedType) {
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

    private fun isEpisodePage(url: String, doc: Document): Boolean {
        if ("/anime/" in url) return false
        if (url.contains("-episode-")) return true
        return doc.selectFirst("meta[property=og:type]")?.attr("content") == "video.episode"
    }

    private fun findAnimeUrlFromEpisode(doc: Document, pageText: String, rscText: String): String? {
        doc.select("a[href*='/anime/'][href*='subtitle-indonesia']").forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank() && !href.contains("-episode-")) return fixUrl(href)
        }
        val candidates = linkedSetOf<String>()
        Regex("""/anime/[a-zA-Z0-9\-]+-subtitle-indonesia/?""")
            .findAll(pageText + "\n" + rscText)
            .forEach { candidates.add(it.value) }
        return candidates.firstOrNull { !it.contains("-episode-") }?.let { fixUrl(it) }
    }

    private data class SiteEpisode(val url: String, val name: String?, val number: Int?, val id: Int? = null)

    private fun parseEpisodesFromDom(doc: Document): List<SiteEpisode> {
        val seen = linkedSetOf<String>()
        val episodes = mutableListOf<SiteEpisode>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            if (!href.contains("-episode-", true) || !href.contains("subtitle-indonesia", true)) return@forEach
            val fixed = fixUrl(href.substringBefore("?"))
            val key = episodeKey(fixed)
            if (!seen.add(key)) return@forEach
            val name = a.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
            episodes.add(SiteEpisode(fixed, name, parseEpisodeNumber(name, href)))
        }
        return episodes
    }

    private fun extractEpisodesFromText(text: String): List<SiteEpisode> {
        if (text.isBlank()) return emptyList()
        val results = mutableListOf<SiteEpisode>()
        Regex(""""episodes"\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(text)
            .forEach { m -> results += parseEpisodesArrayJson(unescapeFlightString(m.groupValues[1]).let { "[$it]" }) }
        Regex("""\\"episodes\\"\\s*:\\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(text)
            .forEach { m ->
                val unescaped = m.groupValues[1].replace("\\\"", "\"").replace("\\/", "/")
                results += parseEpisodesArrayJson("[$unescaped]")
            }
        Regex("""\{[^{}]*"id"\s*:\s*(\d+)[^{}]*"slug"\s*:\s*"([^"]+episode[^"]*)"[^{}]*\}""")
            .findAll(text.replace("\\\"", "\""))
            .forEach { m ->
                val id = m.groupValues[1].toIntOrNull()
                val slug = m.groupValues[2]
                val block = m.value
                val title = Regex(""""title"\s*:\s*"([^"]*)"""").find(block)?.groupValues?.getOrNull(1)
                val num = Regex(""""episodeNumber"\s*:\s*(\d+)""").find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (slug.contains("-episode-", true)) {
                    val url = if (id != null) "$mainUrl/$slug/?eid=$id" else "$mainUrl/$slug/"
                    results += SiteEpisode(url, title, num ?: parseEpisodeNumber(title, slug), id)
                }
            }
        return results.distinctBy { episodeKey(it.url) }
    }

    private fun parseEpisodesArrayJson(arrayJson: String): List<SiteEpisode> {
        return runCatching {
            val normalized = unescapeFlightString(arrayJson)
            mapper.readTree(normalized).mapNotNull { node ->
                val slug = node.get("slug")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!slug.contains("-episode-", true)) return@mapNotNull null
                val id = node.get("id")?.asInt()
                val num = node.get("episodeNumber")?.asInt()?.takeIf { it > 0 }
                    ?: node.get("episodeNumber")?.asText()?.toIntOrNull()
                val name = node.get("title")?.asText()?.takeIf { it.isNotBlank() }
                val epUrl = if (id != null) "$mainUrl/$slug/?eid=$id" else "$mainUrl/$slug/"
                SiteEpisode(epUrl, name, num, id)
            }
        }.getOrDefault(emptyList())
    }

    private fun unescapeFlightString(s: String): String {
        return s
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
    }

    private fun episodeKey(url: String): String = url.substringBefore("?").trimEnd('/').substringAfterLast("/")

    private fun mergeEpisodes(primary: List<SiteEpisode>, secondary: List<SiteEpisode>): List<SiteEpisode> {
        val byKey = LinkedHashMap<String, SiteEpisode>()
        primary.forEach { byKey[episodeKey(it.url)] = it }
        secondary.forEach { sec ->
            val k = episodeKey(sec.url)
            val cur = byKey[k]
            if (cur == null) {
                byKey[k] = sec
            } else {
                val id = cur.id ?: sec.id
                val url = when {
                    cur.url.contains("eid=") -> cur.url
                    sec.url.contains("eid=") -> sec.url
                    id != null -> "${cur.url.substringBefore("?").trimEnd('/')}/?eid=$id"
                    else -> cur.url.substringBefore("?")
                }
                byKey[k] = SiteEpisode(
                    url = url,
                    name = cur.name ?: sec.name,
                    number = cur.number ?: sec.number,
                    id = id
                )
            }
        }
        return byKey.values.toList()
    }

    private fun buildEpisodes(siteEpisodes: List<SiteEpisode>, type: TvType, meta: AniZipMeta?, animeTitle: String): List<Episode> {
        val aniEpisodes = meta?.data?.episodes ?: emptyMap()
        val episodes = siteEpisodes.mapIndexedNotNull { index, ep ->
            val epNum = ep.number ?: parseEpisodeNumber(ep.name, ep.url)
            val aniEntry = matchAniEpisode(epNum, ep.name, index, aniEpisodes)
            val aniEp = aniEntry?.value
            val aniTitle = pickAniZipTitle(aniEp?.title)
            val fallbackName = ep.name ?: epNum?.let { "Episode $it" } ?: "Episode ${index + 1}"
            val displayName = if (type == TvType.AnimeMovie && siteEpisodes.size <= 1) {
                aniTitle ?: animeTitle
            } else {
                aniTitle ?: fallbackName
            }
            newEpisode(ep.url) {
                this.name = displayName
                this.episode = epNum ?: aniEntry?.key?.toIntOrNull()
                this.posterUrl = aniEp?.image ?: meta?.data?.images?.firstOrNull()?.url
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() } ?: "Synopsis not yet available."
                this.runTime = aniEp?.runtime
                this.addDate(aniEp?.airDateUtc)
            }
        }
        return if (episodes.size > 1) episodes.reversed() else episodes
    }

    private fun pickAniZipTitle(titles: Map<String, String>?): String? {
        titles ?: return null
        val preferredKeys = listOf("en", "x-jat", "ja", "id", "romaji", "jp")
        for (k in preferredKeys) {
            val v = titles[k]?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return titles.values.firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun parseEpisodeNumber(name: String?, href: String?): Int? {
        name?.let { episodeNumRegex.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt() }?.let { return it }
        href?.let {
            episodeNumRegex.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
                ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }?.let { return it }
        return null
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
        val episodePage = runCatching { app.get(data, headers = headers + ("Referer" to mainUrl)).text }.getOrDefault("")
        val episodeDoc = if (episodePage.isNotBlank()) org.jsoup.Jsoup.parse(episodePage, data) else null
        val episodeRsc = runCatching { app.get(data, headers = headers + ("RSC" to "1")).text }.getOrDefault("")
        val slug = data.substringBefore("?").trimEnd('/').substringAfterLast('/')

        var episodeId = Regex("""[?&]eid=(\d+)""").find(data)?.groupValues?.get(1)
            ?: findEpisodeIdInText(episodePage, slug)
            ?: findEpisodeIdInText(episodeRsc, slug)

        if (episodeId == null && episodeDoc != null) {
            val animeUrl = findAnimeUrlFromEpisode(episodeDoc, episodePage, episodeRsc)
            if (animeUrl != null) {
                val animePage = runCatching { app.get(animeUrl, headers = headers).text }.getOrDefault("")
                val animeRsc = runCatching { app.get(animeUrl, headers = headers + ("RSC" to "1")).text }.getOrDefault("")
                episodeId = findEpisodeIdInText(animePage, slug) ?: findEpisodeIdInText(animeRsc, slug)
            }
        }

        var emitted = false
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
            mirrors.forEach { mirror ->
                val url = mirror.embedUrl.takeIf { it.isNotBlank() } ?: return@forEach
                callback(
                    newExtractorLink(
                        mirror.serverName.ifBlank { name },
                        mirror.serverName.ifBlank { name },
                        url,
                        if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = parseQuality(mirror.quality)
                    }
                )
                emitted = true
            }
            if (emitted) return true
        }

        val embedUrls = linkedSetOf<String>()
        episodeDoc?.select("iframe[src], iframe[data-src]")?.forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("abs:data-src") }
                .ifBlank { iframe.attr("src") }.ifBlank { iframe.attr("data-src") }
            if (src.startsWith("http")) embedUrls.add(src)
        }
        episodeDoc?.select("a[href]")?.forEach { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (isLikelyEmbedUrl(href)) embedUrls.add(href)
        }
        extractEmbedUrlsFromText(episodePage).forEach { embedUrls.add(it) }
        extractEmbedUrlsFromText(episodeRsc).forEach { embedUrls.add(it) }

        embedUrls.forEach { embed ->
            runCatching { loadExtractor(embed, data, subtitleCallback, callback) }
                .onSuccess { emitted = true }
        }
        return emitted
    }

    private fun findEpisodeIdInText(text: String, slug: String): String? {
        if (text.isBlank() || slug.isBlank()) return null
        val unescaped = text.replace("\\\"", "\"")
        val escapedSlug = Regex.escape(slug)
        val patterns = listOf(
            Regex(""""id"\s*:\s*(\d+)\s*,\s*"slug"\s*:\s*"$escapedSlug""""),
            Regex(""""slug"\s*:\s*"$escapedSlug"\s*,\s*"id"\s*:\s*(\d+)"""),
            Regex(""""id"\s*:\s*(\d+)[^}]{0,200}?"slug"\s*:\s*"$escapedSlug""""),
            Regex(""""slug"\s*:\s*"$escapedSlug"[^}]{0,200}?"id"\s*:\s*(\d+)""")
        )
        for (p in patterns) {
            p.find(unescaped)?.groupValues?.getOrNull(1)?.let { return it }
        }
        extractEpisodesFromText(text).firstOrNull { episodeKey(it.url) == slug }?.id?.let { return it.toString() }
        return null
    }

    private fun extractEmbedUrlsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val urls = linkedSetOf<String>()
        val normalized = text.replace("\\/", "/").replace("\\\"", "\"")
        Regex("""https?://[^\s"'<>\\]+""").findAll(normalized).forEach { m ->
            val u = m.value.trim().trimEnd(',', ')', ']', '}', ';')
            if (isLikelyEmbedUrl(u)) urls.add(u)
        }
        return urls.toList()
    }

    private fun isLikelyEmbedUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val u = url.lowercase()
        if (u.contains("x6.sokuja.uk") || u.contains("gbr.sokuja.uk") || u.contains("storages.sokuja.uk")) return false
        if (u.contains("google-analytics") || u.contains("googletagmanager") || u.contains("histats") || u.contains("facebook") || u.contains("twitter")) return false
        val keywords = listOf(
            "embed", "player", "/e/", "/v/", "stream", "m3u8", ".mp4", ".mkv", "video",
            "nontonanime", "desustream", "blogger", "kraken", "filemoon", "dood", "streamtape",
            "mp4upload", "ok.ru", "mega", "yourupload", "vidhide", "wish", "luluvdo"
        )
        return keywords.any { u.contains(it) }
    }

    private fun findJsonLd(doc: Document, type: String): JSONObject? {
        doc.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().trim()
            if (raw.isBlank()) return@forEach
            runCatching {
                val obj = JSONObject(raw)
                if (obj.optString("@type") == type) return obj
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
        q == null -> Qualities.Unknown.value
        q.contains("2160") || q.contains("4K") -> Qualities.P2160.value
        q.contains("1080") -> Qualities.P1080.value
        q.contains("720") -> Qualities.P720.value
        q.contains("480") -> Qualities.P480.value
        q.contains("360") -> Qualities.P360.value
        else -> Regex("""(\d{3,4})""").find(q)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun getType(t: String): TvType = when {
        t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
        t.contains("Movie", true) -> TvType.AnimeMovie
        else -> TvType.Anime
    }

    private fun getStatus(t: String): ShowStatus = when {
        t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
        else -> ShowStatus.Ongoing
    }

    private data class AniZipMeta(val data: AniZipData, val tmdbId: Int?, val kitsuId: String?)

    private suspend fun fetchAniZipMeta(malId: Int?): AniZipMeta? {
        malId ?: return null
        return runCatching {
            val json = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
            val tree = mapper.readTree(json)
            AniZipMeta(
                data = mapper.readValue(json, AniZipData::class.java),
                tmdbId = tree?.get("mappings")?.get("themoviedb_id")?.asInt()?.takeIf { it != 0 },
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
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("serverName") val serverName: String = "",
        @JsonProperty("embedUrl") val embedUrl: String = "",
        @JsonProperty("embedType") val embedType: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoMirrorsResponse(
        @JsonProperty("mirrors") val mirrors: List<VideoMirror> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipEpisode(
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images") val images: List<AniZipImage>?,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>?,
    )
}
