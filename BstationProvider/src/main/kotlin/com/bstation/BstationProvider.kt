@file:OptIn(Prerelease::class)

package com.bstation

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class BstationProvider : MainAPI() {
    override var mainUrl            = "https://www.bilibili.tv"
    override var name               = "Bstation"
    override val hasMainPage        = true
    override val hasQuickSearch     = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)

    companion object {
        private const val API      = "https://api.bilibili.tv/intl/gateway/web/v2"
        private const val PLAYURL  = "https://api.bilibili.tv/intl/gateway/web/playurl"
        private const val UA       = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // season_type values from Bilibili TV OGV API
        private const val TYPE_ANIME = 1
        private const val TYPE_MOVIE = 2
        private const val TYPE_TV    = 3

        // Bilibili TV style_id (genre) values — inspect /ogv/home/section for full list
        // These are the IDs observed on bilibili.tv browse pages
        private const val STYLE_ACTION      = 1
        private const val STYLE_ROMANCE     = 2
        private const val STYLE_COMEDY      = 3
        private const val STYLE_FANTASY     = 4
        private const val STYLE_HORROR      = 5
        private const val STYLE_SCI_FI      = 6
        private const val STYLE_THRILLER    = 7
        private const val STYLE_SPORTS      = 8
        private const val STYLE_MUSIC       = 9
        private const val STYLE_SCHOOL      = 10
        private const val STYLE_ISEKAI      = 11
        private const val STYLE_MYSTERY     = 12
        private const val STYLE_SLICE       = 13
        private const val STYLE_MECHA       = 14
        private const val STYLE_SUPERNATURAL = 15
        private const val STYLE_ADVENTURE   = 16

        // area values: 1=Japan, 2=China, 3=Korea, 0=Others
        private const val AREA_JAPAN  = 1
        private const val AREA_CHINA  = 2
        private const val AREA_KOREA  = 3
        private const val AREA_OTHER  = 0
    }

    private val headers = mapOf(
        "User-Agent"      to UA,
        "Referer"         to "$mainUrl/",
        "Origin"          to mainUrl,
        "Accept"          to "application/json, text/plain, */*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
    )

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private inline fun <reified T> parseJson(text: String): T = mapper.readValue(text, T::class.java)

    // ================== Helpers ==================

    private fun String.ensureHttps() = when {
        startsWith("//")     -> "https:$this"
        startsWith("http://") -> replace("http://", "https://")
        else                 -> this
    }

    private fun qualityName(qn: Int) = when (qn) {
        112 -> "1080P+"; 80 -> "1080P"; 64 -> "720P"; 32 -> "480P"; 16 -> "360P"
        else -> "${qn}p"
    }

    private fun qualityValue(qn: Int) = when (qn) {
        112, 80 -> Qualities.P1080.value
        64      -> Qualities.P720.value
        32      -> Qualities.P480.value
        16      -> Qualities.P360.value
        else    -> Qualities.Unknown.value
    }

    // ================== Homepage ==================

    // Format: "browse:{season_type}:{area}:{style_id}" or "timeline" or "search:{keyword}"
    // area=0 means all areas; style_id=0 means all genres
    override val mainPage = mainPageOf(
        // -- Featured --
        "timeline"                  to "Update Terbaru",
        "search:anime sub indonesia" to "Rekomendasi",

        // -- By Type --
        "browse:${TYPE_ANIME}:0:0"  to "Anime",
        "browse:${TYPE_MOVIE}:0:0"  to "Film",
        "browse:${TYPE_TV}:0:0"     to "Drama",

        // -- By Region --
        "browse:${TYPE_ANIME}:${AREA_JAPAN}:0"  to "Anime Jepang",
        "browse:${TYPE_ANIME}:${AREA_CHINA}:0"  to "Anime China (Donghua)",
        "browse:${TYPE_TV}:${AREA_KOREA}:0"     to "Drama Korea",

        // -- By Genre --
        "browse:${TYPE_ANIME}:0:${STYLE_ACTION}"      to "Aksi",
        "browse:${TYPE_ANIME}:0:${STYLE_ROMANCE}"     to "Romantis",
        "browse:${TYPE_ANIME}:0:${STYLE_COMEDY}"      to "Komedi",
        "browse:${TYPE_ANIME}:0:${STYLE_FANTASY}"     to "Fantasi",
        "browse:${TYPE_ANIME}:0:${STYLE_ISEKAI}"      to "Isekai",
        "browse:${TYPE_ANIME}:0:${STYLE_ADVENTURE}"   to "Petualangan",
        "browse:${TYPE_ANIME}:0:${STYLE_SCI_FI}"      to "Fiksi Ilmiah",
        "browse:${TYPE_ANIME}:0:${STYLE_MYSTERY}"     to "Misteri",
        "browse:${TYPE_ANIME}:0:${STYLE_HORROR}"      to "Horor",
        "browse:${TYPE_ANIME}:0:${STYLE_THRILLER}"    to "Thriller",
        "browse:${TYPE_ANIME}:0:${STYLE_SLICE}"       to "Slice of Life",
        "browse:${TYPE_ANIME}:0:${STYLE_SCHOOL}"      to "Sekolah",
        "browse:${TYPE_ANIME}:0:${STYLE_SPORTS}"      to "Olahraga",
        "browse:${TYPE_ANIME}:0:${STYLE_MUSIC}"       to "Musik",
        "browse:${TYPE_ANIME}:0:${STYLE_MECHA}"       to "Mecha",
        "browse:${TYPE_ANIME}:0:${STYLE_SUPERNATURAL}" to "Supernatural",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when {
            request.data == "timeline" -> fetchTimeline()
            request.data.startsWith("search:") -> {
                val kw = request.data.removePrefix("search:")
                fetchSearch(kw, page)
            }
            request.data.startsWith("browse:") -> {
                val parts    = request.data.removePrefix("browse:").split(":")
                val type     = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val area     = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val styleId  = parts.getOrNull(2)?.toIntOrNull() ?: 0
                fetchBrowse(type, area, styleId, page)
            }
            else -> emptyList()
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = items.isNotEmpty()
        )
    }

    // ================== Browse / Timeline / Search ==================

    private suspend fun fetchBrowse(seasonType: Int, area: Int, styleId: Int, page: Int): List<SearchResponse> {
        val areaParam  = if (area  != 0) "&area=$area"    else ""
        val styleParam = if (styleId != 0) "&style_id=$styleId" else ""
        val url = "$API/ogv/home/filter/season?season_type=$seasonType$areaParam$styleParam&sort=0&pn=$page&ps=20&platform=web&s_locale=id_ID"
        return runCatching {
            val json = parseJson<BiliBrowseResponse>(app.get(url, headers = headers).text)
            json.data?.list?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchTimeline(): List<SearchResponse> {
        val url = "$API/ogv/timeline?platform=web&s_locale=id_ID"
        return runCatching {
            val json = parseJson<BiliTimelineResponse>(app.get(url, headers = headers).text)
            json.data?.items?.flatMap { day ->
                day.cards?.mapNotNull { card ->
                    val title    = card.title ?: return@mapNotNull null
                    val seasonId = card.seasonId ?: return@mapNotNull null
                    newAnimeSearchResponse(title, "$mainUrl/en/play/$seasonId", TvType.Anime) {
                        posterUrl = card.cover?.ensureHttps()
                    }
                } ?: emptyList()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchSearch(keyword: String, page: Int = 1): List<SearchResponse> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url     = "$API/search_v2?keyword=$encoded&platform=web&pn=$page&ps=30&s_locale=id_ID"
        return runCatching {
            val json = parseJson<BiliSearchResponse>(app.get(url, headers = headers).text)
            json.data?.modules?.flatMap { module ->
                module.items?.mapNotNull { it.toSearchResponse(module.type) } ?: emptyList()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun BiliBrowseItem.toSearchResponse(): AnimeSearchResponse? {
        val title    = title ?: return null
        val seasonId = seasonId ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/en/play/$seasonId", TvType.Anime) {
            posterUrl = (verticalCover ?: cover)?.ensureHttps()
        }
    }

    private fun BiliSearchItem.toSearchResponse(moduleType: String?): SearchResponse? {
        val title = title ?: return null
        return when {
            seasonId != null -> newAnimeSearchResponse(title, "$mainUrl/en/play/$seasonId", TvType.Anime) {
                posterUrl = cover?.ensureHttps()
            }
            aid != null && moduleType == "ugc" -> newMovieSearchResponse(title, "$mainUrl/en/video/$aid", TvType.Movie) {
                posterUrl = cover?.ensureHttps()
            }
            else -> null
        }
    }

    // ================== Search ==================

    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String) = fetchSearch(query)

    // ================== Load ==================

    override suspend fun load(url: String): LoadResponse? = when {
        url.contains("/play/")  -> loadSeason(url)
        url.contains("/video/") -> loadVideo(url)
        else                    -> null
    }

    private suspend fun loadSeason(url: String): LoadResponse? {
        val seasonId = Regex("/play/(\\d+)").find(url)?.groupValues?.get(1) ?: return null

        val season = runCatching {
            parseJson<BiliSeasonInfoResponse>(
                app.get("$API/ogv/play/season_info?season_id=$seasonId&platform=web&s_locale=id_ID", headers = headers).text
            ).data?.season
        }.getOrNull() ?: return null

        val episodesJson = runCatching {
            parseJson<BiliEpisodesResponse>(
                app.get("$API/ogv/play/episodes?season_id=$seasonId&platform=web&s_locale=id_ID", headers = headers).text
            )
        }.getOrNull()

        val firstEpId = episodesJson?.data?.sections?.firstOrNull()?.episodes?.firstOrNull()?.episodeId
        if (firstEpId != null) {
            val access = checkAccess(firstEpId, null)
            if (access != AccessError.NONE) throw ErrorLoadingException(access.message)
        }

        val episodes = mutableListOf<Episode>()
        var counter  = 1
        episodesJson?.data?.sections?.forEach { section ->
            section.episodes?.forEach { ep ->
                val epId = ep.episodeId ?: return@forEach
                if (ep.shortTitleDisplay?.contains("PV", ignoreCase = true) == true) return@forEach
                episodes.add(newEpisode(EpisodeData(epId = epId, seasonId = seasonId).toJson()) {
                    name    = ep.longTitleDisplay ?: ep.titleDisplay ?: "Episode $counter"
                    episode = ep.shortTitleDisplay?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: counter
                    posterUrl = ep.cover?.ensureHttps()
                })
                counter++
            }
        }

        val title  = season.title ?: return null
        val poster = (season.verticalCover ?: season.horizontalCover)?.ensureHttps()
        val tags   = season.styles?.mapNotNull { it.title }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = season.description
            this.year      = season.playerDate?.take(4)?.toIntOrNull()
            this.tags      = tags
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse? {
        val aid  = Regex("/video/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val page = runCatching { app.get(url, headers = headers).text }.getOrNull() ?: return null

        val access = checkAccess(null, aid)
        if (access != AccessError.NONE) throw ErrorLoadingException(access.message)

        val title  = Regex("""<title>([^<]+)</title>""").find(page)?.groupValues?.get(1)
            ?.replace(" - Bilibili", "")?.trim() ?: "Video $aid"
        val cover  = Regex("""<meta property="og:image" content="([^"]+)"""").find(page)?.groupValues?.get(1)?.ensureHttps()
        val plot   = Regex("""<meta property="og:description" content="([^"]+)"""").find(page)?.groupValues?.get(1)

        return newMovieLoadResponse(title, url, TvType.Movie, EpisodeData(aid = aid).toJson()) {
            posterUrl = cover
            this.plot = plot
        }
    }

    // ================== Load Links ==================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep     = parseJson<EpisodeData>(data)
        val epId   = ep.epId
        val seasonId = ep.seasonId
        val aid    = ep.aid

        var found = false

        // Primary: playurl API
        if (epId != null || aid != null) {
            found = tryPlayurl(epId, aid, callback)
        }

        // Fallback: scrape page
        if (!found) {
            val pageUrl = when {
                epId != null && seasonId != null -> "$mainUrl/en/play/$seasonId/$epId"
                aid != null                      -> "$mainUrl/en/video/$aid"
                else                             -> null
            }
            if (pageUrl != null) found = tryScrapePage(pageUrl, callback)
        }

        // Always offer web player link
        val playerUrl = when {
            epId != null && seasonId != null -> "$mainUrl/en/play/$seasonId/$epId"
            aid != null                      -> "$mainUrl/en/video/$aid"
            else                             -> null
        }
        if (playerUrl != null) {
            callback(newExtractorLink(name, "$name - Web Player", playerUrl, ExtractorLinkType.VIDEO) {
                quality = Qualities.Unknown.value
                referer = mainUrl
            })
        }

        // Subtitles
        if (epId != null) loadSubtitles(epId, subtitleCallback)

        return true
    }

    // ================== Playurl ==================

    private suspend fun tryPlayurl(epId: String?, aid: String?, callback: (ExtractorLink) -> Unit): Boolean {
        val url = when {
            epId != null -> "$PLAYURL?ep_id=$epId&device=wap&platform=web&qn=64&tf=0&type=0"
            aid  != null -> "$PLAYURL?s_locale=id_ID&platform=web&aid=$aid&qn=120"
            else         -> return false
        }
        return runCatching {
            val json = parseJson<BiliPlayurlResponse>(
                app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text
            )
            if (json.code != 0) {
                val msg = when (json.code) {
                    10015001 -> "⚠️ Konten ini terkunci wilayah (GEO-LOCKED). Gunakan VPN Asia Tenggara."
                    10004004 -> "⚠️ Konten premium — butuh langganan Bilibili TV."
                    else     -> return@runCatching false
                }
                callback(newExtractorLink(name, msg, "https://www.bilibili.tv", ExtractorLinkType.VIDEO) {
                    quality = Qualities.Unknown.value
                })
                return@runCatching true
            }

            val playurl    = json.data?.playurl ?: return@runCatching false
            val streamHdr  = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Origin" to mainUrl)

            val audioUrl   = playurl.audioResource?.firstOrNull()?.url?.trim()?.takeIf { it.isNotEmpty() }
            val extractedAudioTracks = buildList {
                audioUrl?.let { add(newAudioFile(it) { this.headers = streamHdr }) }
            }

            val videoList  = playurl.video ?: return@runCatching false
            val qualityOrder = listOf(112, 80, 64, 32, 16)
            var primaryUrl: String? = null
            var primaryQn  = 0

            outer@ for (qn in qualityOrder) {
                for (v in videoList) {
                    val vUrl = v.videoResource?.url?.trim() ?: continue
                    if (vUrl.isEmpty()) continue
                    if ((v.streamInfo?.quality ?: 0) == qn) {
                        primaryUrl = vUrl
                        primaryQn  = qn
                        break@outer
                    }
                }
            }
            if (primaryUrl == null) {
                primaryUrl = videoList.firstOrNull()?.videoResource?.url?.trim()
                primaryQn  = videoList.firstOrNull()?.streamInfo?.quality ?: 0
            }
            if (primaryUrl.isNullOrEmpty()) return@runCatching false

            callback(newExtractorLink(name, "$name ${qualityName(primaryQn)}", primaryUrl, INFER_TYPE) {
                quality     = qualityValue(primaryQn)
                referer     = mainUrl
                this.headers = streamHdr
                this.audioTracks = extractedAudioTracks
            })

            for (v in videoList) {
                val vUrl = v.videoResource?.url?.trim() ?: continue
                if (vUrl.isEmpty() || vUrl == primaryUrl) continue
                val qn   = v.streamInfo?.quality ?: 0
                callback(newExtractorLink(name, "$name ${qualityName(qn)} (Alt)", vUrl, INFER_TYPE) {
                    quality     = qualityValue(qn)
                    referer     = mainUrl
                    this.headers = streamHdr
                    this.audioTracks = extractedAudioTracks
                })
            }
            true
        }.getOrDefault(false)
    }

    // ================== Page scraper fallback ==================

    private suspend fun tryScrapePage(pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val body = runCatching { app.get(pageUrl, headers = headers).text }.getOrNull() ?: return false
        val streamHdr = mapOf("User-Agent" to UA, "Referer" to pageUrl)
        var found = false

        Regex("""window\.__playinfo__\s*=\s*(\{[\s\S]*?\});""").find(body)?.let { m ->
            runCatching {
                val info = parseJson<BiliPlayInfo>(m.groupValues[1])
                info.data?.dash?.video?.forEach { v ->
                    val vUrl = v.baseUrl ?: v.baseUrlAlt ?: return@forEach
                    callback(newExtractorLink(name, "$name - Stream", vUrl, INFER_TYPE) {
                        quality     = Qualities.Unknown.value
                        referer     = mainUrl
                        this.headers = streamHdr
                    })
                    found = true
                }
            }
        }
        if (found) return true

        val patterns = listOf(
            Regex("""(https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*)"""),
            Regex("""(https?://[^"'\s<>\\]+\.mp4[^"'\s<>\\]*)"""),
            Regex("""(https?://[^"'\s<>\\]*(?:upos-sz|bilivideo|bstar)[^"'\s<>\\]+)"""),
        )
        for (pat in patterns) {
            pat.findAll(body).distinctBy { it.groupValues[1] }.take(3).forEach { m ->
                val u = m.groupValues[1].replace("\\u002F", "/").replace("\\/", "/")
                if (u.length > 50) {
                    callback(newExtractorLink(name, "$name - CDN", u, INFER_TYPE) {
                        quality     = Qualities.Unknown.value
                        referer     = mainUrl
                        this.headers = streamHdr
                    })
                    found = true
                }
            }
            if (found) break
        }
        return found
    }

    // ================== Subtitles ==================

    private suspend fun loadSubtitles(epId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        runCatching {
            val json = parseJson<BiliSubtitleResponse>(
                app.get("$API/subtitle?ep_id=$epId&platform=web&s_locale=id_ID", headers = headers).text
            )
            json.data?.subtitles?.forEach { sub ->
                val url  = sub.url?.ensureHttps() ?: return@forEach
                val lang = sub.langDoc ?: sub.lang ?: "Unknown"
                subtitleCallback(newSubtitleFile(lang = lang, url = url))
            }
        }
    }

    // ================== Access Check ==================

    private enum class AccessError(val message: String) {
        NONE(""),
        GEO_LOCKED("⚠️ GEO-LOCKED: Konten ini tidak tersedia di wilayahmu. Gunakan VPN Asia Tenggara."),
        PREMIUM("⚠️ PREMIUM: Konten ini memerlukan langganan Bilibili TV."),
    }

    private suspend fun checkAccess(epId: String?, aid: String?): AccessError {
        val url = when {
            epId != null -> "$PLAYURL?ep_id=$epId&device=wap&platform=web&qn=64&tf=0&type=0"
            aid  != null -> "$PLAYURL?s_locale=id_ID&platform=web&aid=$aid&qn=120"
            else         -> return AccessError.NONE
        }
        return runCatching {
            val json = parseJson<BiliPlayurlResponse>(
                app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text
            )
            when (json.code) {
                10015001 -> AccessError.GEO_LOCKED
                10004004 -> AccessError.PREMIUM
                else     -> AccessError.NONE
            }
        }.getOrDefault(AccessError.NONE)
    }

    // ================== Data Classes ==================

    data class EpisodeData(
        @field:JsonProperty("epId")     val epId:     String? = null,
        @field:JsonProperty("seasonId") val seasonId: String? = null,
        @field:JsonProperty("aid")      val aid:      String? = null,
    )

    // Search
    data class BiliSearchResponse(
        @field:JsonProperty("data") val data: BiliSearchData? = null,
    )
    data class BiliSearchData(
        @field:JsonProperty("modules") val modules: List<BiliSearchModule>? = null,
    )
    data class BiliSearchModule(
        @field:JsonProperty("type")  val type:  String? = null,
        @field:JsonProperty("items") val items: List<BiliSearchItem>? = null,
    )
    data class BiliSearchItem(
        @field:JsonProperty("title")     val title:    String? = null,
        @field:JsonProperty("season_id") val seasonId: String? = null,
        @field:JsonProperty("cover")     val cover:    String? = null,
        @field:JsonProperty("aid")       val aid:      String? = null,
    )

    // Browse
    data class BiliBrowseResponse(
        @field:JsonProperty("data") val data: BiliBrowseData? = null,
    )
    data class BiliBrowseData(
        @field:JsonProperty("list") val list: List<BiliBrowseItem>? = null,
    )
    data class BiliBrowseItem(
        @field:JsonProperty("title")          val title:         String? = null,
        @field:JsonProperty("season_id")      val seasonId:      String? = null,
        @field:JsonProperty("cover")          val cover:         String? = null,
        @field:JsonProperty("vertical_cover") val verticalCover: String? = null,
    )

    // Timeline
    data class BiliTimelineResponse(
        @field:JsonProperty("data") val data: BiliTimelineData? = null,
    )
    data class BiliTimelineData(
        @field:JsonProperty("items") val items: List<BiliTimelineDay>? = null,
    )
    data class BiliTimelineDay(
        @field:JsonProperty("cards") val cards: List<BiliTimelineCard>? = null,
    )
    data class BiliTimelineCard(
        @field:JsonProperty("title")     val title:    String? = null,
        @field:JsonProperty("season_id") val seasonId: String? = null,
        @field:JsonProperty("cover")     val cover:    String? = null,
    )

    // Season Info
    data class BiliSeasonInfoResponse(
        @field:JsonProperty("data") val data: BiliSeasonInfoData? = null,
    )
    data class BiliSeasonInfoData(
        @field:JsonProperty("season") val season: BiliSeason? = null,
    )
    data class BiliSeason(
        @field:JsonProperty("title")           val title:          String? = null,
        @field:JsonProperty("description")     val description:    String? = null,
        @field:JsonProperty("vertical_cover")  val verticalCover:  String? = null,
        @field:JsonProperty("horizontal_cover") val horizontalCover: String? = null,
        @field:JsonProperty("player_date")     val playerDate:     String? = null,
        @field:JsonProperty("styles")          val styles:         List<BiliStyle>? = null,
    )
    data class BiliStyle(
        @field:JsonProperty("title") val title: String? = null,
    )

    // Episodes
    data class BiliEpisodesResponse(
        @field:JsonProperty("data") val data: BiliEpisodesData? = null,
    )
    data class BiliEpisodesData(
        @field:JsonProperty("sections") val sections: List<BiliSection>? = null,
    )
    data class BiliSection(
        @field:JsonProperty("episodes") val episodes: List<BiliEpisode>? = null,
    )
    data class BiliEpisode(
        @field:JsonProperty("episode_id")         val episodeId:         String? = null,
        @field:JsonProperty("cover")              val cover:             String? = null,
        @field:JsonProperty("title_display")      val titleDisplay:      String? = null,
        @field:JsonProperty("short_title_display") val shortTitleDisplay: String? = null,
        @field:JsonProperty("long_title_display") val longTitleDisplay:  String? = null,
    )

    // Playurl
    data class BiliPlayurlResponse(
        @field:JsonProperty("code")    val code:    Int?    = null,
        @field:JsonProperty("message") val message: String? = null,
        @field:JsonProperty("data")    val data:    BiliPlayurlData? = null,
    )
    data class BiliPlayurlData(
        @field:JsonProperty("playurl") val playurl: BiliPlayurlInfo? = null,
    )
    data class BiliPlayurlInfo(
        @field:JsonProperty("video")          val video:         List<BiliVideoStream>? = null,
        @field:JsonProperty("audio_resource") val audioResource: List<BiliAudioResource>? = null,
    )
    data class BiliVideoStream(
        @field:JsonProperty("video_resource") val videoResource: BiliVideoResource? = null,
        @field:JsonProperty("stream_info")    val streamInfo:    BiliStreamInfo? = null,
    )
    data class BiliVideoResource(
        @field:JsonProperty("url") val url: String? = null,
    )
    data class BiliStreamInfo(
        @field:JsonProperty("quality") val quality: Int? = null,
    )
    data class BiliAudioResource(
        @field:JsonProperty("url") val url: String? = null,
    )

    // Subtitle
    data class BiliSubtitleResponse(
        @field:JsonProperty("data") val data: BiliSubtitleData? = null,
    )
    data class BiliSubtitleData(
        @field:JsonProperty("subtitles") val subtitles: List<BiliSubtitle>? = null,
    )
    data class BiliSubtitle(
        @field:JsonProperty("url")      val url:     String? = null,
        @field:JsonProperty("lang")     val lang:    String? = null,
        @field:JsonProperty("lang_doc") val langDoc: String? = null,
    )

    // Page scrape
    data class BiliPlayInfo(
        @field:JsonProperty("data") val data: BiliPlayInfoData? = null,
    )
    data class BiliPlayInfoData(
        @field:JsonProperty("dash") val dash: BiliDash? = null,
    )
    data class BiliDash(
        @field:JsonProperty("video") val video: List<BiliDashVideo>? = null,
    )
    data class BiliDashVideo(
        @field:JsonProperty("baseUrl")  val baseUrl:    String? = null,
        @field:JsonProperty("base_url") val baseUrlAlt: String? = null,
    )
}
