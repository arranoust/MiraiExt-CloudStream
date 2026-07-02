@file:OptIn(Prerelease::class)
package com.bstation

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        private const val API     = "https://api.bilibili.tv/intl/gateway/web/v2"
        private const val PLAYURL = "https://api.bilibili.tv/intl/gateway/web/playurl"
        private const val UA      = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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
    private fun String.toHttps() = this.replace("http://", "https://").let { if (it.startsWith("//")) "https:$it" else it }

    override val mainPage = mainPageOf(
        "popular"                   to "Populer",
        "timeline"                  to "Update Terbaru",
        "browse:1:0:0"              to "Anime",
        "browse:2:0:0"              to "Film",
        "browse:3:0:0"              to "Drama",
        "browse:1:1:0"              to "Anime Jepang",
        "browse:1:2:0"              to "Anime China"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when {
            request.data == "popular" -> fetchPopular()
            request.data == "timeline" -> fetchTimeline()
            request.data.startsWith("search:") -> fetchSearch(request.data.removePrefix("search:"), page)
            request.data.startsWith("browse:") -> {
                val (type, area, style) = request.data.removePrefix("browse:").split(":").map { it.toIntOrNull() ?: 0 }
                fetchBrowse(type, area, style, page)
            }
            else -> emptyList()
        }
        return newHomePageResponse(listOf(HomePageList(request.name, items, isHorizontalImages = true)), hasNext = items.isNotEmpty())
    }

    private suspend fun fetchPopular(): List<SearchResponse> {
        val url = "$API/home/trending?platform=web&s_locale=id_ID"
        return runCatching {
            parseJson<BiliResponse<BiliTrendingData>>(app.get(url, headers = headers).text)
                .data?.let { it.list ?: it.items }
                ?.mapNotNull { it.toSearchResponse(if (it.aid != null) "ugc" else null) }
        }.getOrNull() ?: emptyList()
    }

    private suspend fun fetchBrowse(type: Int, area: Int, style: Int, page: Int): List<SearchResponse> {
        val url = "$API/ogv/home/filter/season?season_type=$type${if (area != 0) "&area=$area" else ""}${if (style != 0) "&style_id=$style" else ""}&sort=0&pn=$page&ps=20&platform=web&s_locale=id_ID"
        return runCatching {
            parseJson<BiliResponse<BiliBrowseData>>(app.get(url, headers = headers).text)
                .data?.list?.mapNotNull { it.toSearchResponse() }
        }.getOrNull() ?: emptyList()
    }

    private suspend fun fetchTimeline(): List<SearchResponse> {
        val url = "$API/ogv/timeline?platform=web&s_locale=id_ID"
        return runCatching {
            parseJson<BiliResponse<BiliTimelineData>>(app.get(url, headers = headers).text)
                .data?.items?.flatMap { it.cards ?: emptyList() }
                ?.mapNotNull { newAnimeSearchResponse(it.title ?: "", "$mainUrl/en/play/${it.seasonId}", TvType.Anime) { posterUrl = it.cover?.toHttps() } }
        }.getOrNull() ?: emptyList()
    }

    private suspend fun fetchSearch(keyword: String, page: Int = 1): List<SearchResponse> {
        val url = "$API/search_v2?keyword=${URLEncoder.encode(keyword, "UTF-8")}&platform=web&pn=$page&ps=30&s_locale=id_ID"
        return runCatching {
            parseJson<BiliResponse<BiliSearchData>>(app.get(url, headers = headers).text)
                .data?.modules?.flatMap { m -> m.items?.mapNotNull { it.toSearchResponse(m.type) } ?: emptyList() }
        }.getOrNull() ?: emptyList()
    }

    private fun BiliBrowseItem.toSearchResponse() = title?.let { t ->
        seasonId?.let { s -> newAnimeSearchResponse(t, "$mainUrl/en/play/$s", TvType.Anime) { posterUrl = (verticalCover ?: cover)?.toHttps() } }
    }

    private fun BiliSearchItem.toSearchResponse(type: String?) = title?.let { t ->
        when {
            seasonId != null -> newAnimeSearchResponse(t, "$mainUrl/en/play/$seasonId", TvType.Anime) { posterUrl = cover?.toHttps() }
            aid != null && type == "ugc" -> newMovieSearchResponse(t, "$mainUrl/en/video/$aid", TvType.Movie) { posterUrl = cover?.toHttps() }
            else -> null
        }
    }

    override suspend fun quickSearch(query: String) = fetchSearch(query)
    override suspend fun search(query: String) = fetchSearch(query)

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("/(play|video)/(\\d+)").find(url)?.groupValues?.get(2) ?: return null
        return if (url.contains("/play/")) loadSeason(id, url) else loadVideo(id, url)
    }

    private suspend fun loadSeason(seasonId: String, url: String): LoadResponse? {
        val season = runCatching { parseJson<BiliResponse<BiliSeasonInfoData>>(app.get("$API/ogv/play/season_info?season_id=$seasonId&platform=web&s_locale=id_ID", headers = headers).text).data?.season }.getOrNull() ?: return null
        val epsJson = runCatching { parseJson<BiliResponse<BiliEpisodesData>>(app.get("$API/ogv/play/episodes?season_id=$seasonId&platform=web&s_locale=id_ID", headers = headers).text).data }.getOrNull()

        val episodes = epsJson?.sections?.flatMap { it.episodes ?: emptyList() }?.filter { it.shortTitleDisplay?.contains("PV", true) != true }?.mapIndexed { i, ep ->
            newEpisode(EpisodeData(epId = ep.episodeId, seasonId = seasonId).toJson()) {
                name = ep.longTitleDisplay ?: ep.titleDisplay ?: "Episode ${i + 1}"
                episode = ep.shortTitleDisplay?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: (i + 1)
                posterUrl = ep.cover?.toHttps()
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(season.title ?: "Unknown", url, TvType.Anime, episodes) {
            posterUrl = (season.verticalCover ?: season.horizontalCover)?.toHttps()
            plot = season.description
            year = season.playerDate?.take(4)?.toIntOrNull()
            tags = season.styles?.mapNotNull { it.title }
        }
    }

    private suspend fun loadVideo(aid: String, url: String): LoadResponse? {
        val page = app.get(url, headers = headers).text
        val title = Regex("""<title>([^<]+)</title>""").find(page)?.groupValues?.get(1)?.replace(" - Bilibili", "")?.trim() ?: "Video"
        return newMovieLoadResponse(title, url, TvType.Movie, EpisodeData(aid = aid).toJson()) {
            posterUrl = Regex("""<meta property="og:image" content="([^"]+)"""").find(page)?.groupValues?.get(1)?.toHttps()
            plot = Regex("""<meta property="og:description" content="([^"]+)"""").find(page)?.groupValues?.get(1)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val ep = parseJson<EpisodeData>(data)
        val url = ep.epId?.let { "$PLAYURL?ep_id=$it&device=wap&platform=web&qn=64&tf=0&type=0" }
            ?: ep.aid?.let { "$PLAYURL?s_locale=id_ID&platform=web&aid=$it&qn=120" } ?: return false

        val json = runCatching { parseJson<BiliResponse<BiliPlayurlData>>(app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text) }.getOrNull()

        if (json?.code != 0) {
            val msg = if (json?.code == 10015001) "⚠️ GEO-LOCKED" else "⚠️ PREMIUM CONTENT"
            callback(newExtractorLink(name, msg, mainUrl, ExtractorLinkType.VIDEO, Qualities.Unknown.value))
            return true
        }

        val playurl = json.data?.playurl ?: return false
        val streamHdr = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
        val audioUrl = playurl.audioResource?.firstOrNull()?.url?.takeIf { it.isNotEmpty() }
        val audio = audioUrl?.let { listOf(newAudioFile(it) { headers = streamHdr }) } ?: emptyList()

        playurl.video?.forEach { v ->
            v.videoResource?.url?.takeIf { it.isNotBlank() }?.let { vUrl ->
                callback(newExtractorLink(name, "$name ${v.streamInfo?.quality ?: "Auto"}p", vUrl, INFER_TYPE, getQuality(v.streamInfo?.quality ?: 0)) {
                    headers = streamHdr; audioTracks = audio
                })
            }
        }

        ep.epId?.let { epId ->
            runCatching {
                parseJson<BiliResponse<BiliSubtitleData>>(app.get("$API/subtitle?ep_id=$epId&platform=web&s_locale=id_ID", headers = headers).text)
                    .data?.subtitles?.forEach { sub -> sub.url?.toHttps()?.let { subtitleCallback(newSubtitleFile(sub.langDoc ?: sub.lang ?: "Unknown", it)) } }
            }
        }
        return true
    }

    private fun getQuality(qn: Int) = when (qn) { 112, 80 -> Qualities.P1080.value; 64 -> Qualities.P720.value; 32 -> Qualities.P480.value; else -> Qualities.Unknown.value }

    // --- Data Models ---
    data class EpisodeData(@JsonProperty("epId") val epId: String? = null, @JsonProperty("seasonId") val seasonId: String? = null, @JsonProperty("aid") val aid: String? = null)
    data class BiliResponse<T>(@JsonProperty("code") val code: Int? = null, @JsonProperty("data") val data: T? = null)
    data class BiliTrendingData(@JsonProperty("list") val list: List<BiliSearchItem>? = null, @JsonProperty("items") val items: List<BiliSearchItem>? = null)
    data class BiliBrowseData(@JsonProperty("list") val list: List<BiliBrowseItem>? = null)
    data class BiliBrowseItem(@JsonProperty("title") val title: String? = null, @JsonProperty("season_id") val seasonId: String? = null, @JsonProperty("cover") val cover: String? = null, @JsonProperty("vertical_cover") val verticalCover: String? = null)
    data class BiliTimelineData(@JsonProperty("items") val items: List<BiliTimelineDay>? = null)
    data class BiliTimelineDay(@JsonProperty("cards") val cards: List<BiliBrowseItem>? = null)
    data class BiliSearchData(@JsonProperty("modules") val modules: List<BiliSearchModule>? = null)
    data class BiliSearchModule(@JsonProperty("type") val type: String? = null, @JsonProperty("items") val items: List<BiliSearchItem>? = null)
    data class BiliSearchItem(@JsonProperty("title") val title: String? = null, @JsonProperty("season_id") val seasonId: String? = null, @JsonProperty("cover") val cover: String? = null, @JsonProperty("aid") val aid: String? = null)
    data class BiliSeasonInfoData(@JsonProperty("season") val season: BiliSeason? = null)
    data class BiliSeason(@JsonProperty("title") val title: String? = null, @JsonProperty("description") val description: String? = null, @JsonProperty("vertical_cover") val verticalCover: String? = null, @JsonProperty("horizontal_cover") val horizontalCover: String? = null, @JsonProperty("player_date") val playerDate: String? = null, @JsonProperty("styles") val styles: List<BiliStyle>? = null)
    data class BiliStyle(@JsonProperty("title") val title: String? = null)
    data class BiliEpisodesData(@JsonProperty("sections") val sections: List<BiliSection>? = null)
    data class BiliSection(@JsonProperty("episodes") val episodes: List<BiliEpisode>? = null)
    data class BiliEpisode(@JsonProperty("episode_id") val episodeId: String? = null, @JsonProperty("cover") val cover: String? = null, @JsonProperty("title_display") val titleDisplay: String? = null, @JsonProperty("short_title_display") val shortTitleDisplay: String? = null, @JsonProperty("long_title_display") val longTitleDisplay: String? = null)
    data class BiliPlayurlData(@JsonProperty("playurl") val playurl: BiliPlayurlInfo? = null)
    data class BiliPlayurlInfo(@JsonProperty("video") val video: List<BiliVideoStream>? = null, @JsonProperty("audio_resource") val audioResource: List<BiliAudioResource>? = null)
    data class BiliVideoStream(@JsonProperty("video_resource") val videoResource: BiliVideoResource? = null, @JsonProperty("stream_info") val streamInfo: BiliStreamInfo? = null)
    data class BiliVideoResource(@JsonProperty("url") val url: String? = null)
    data class BiliStreamInfo(@JsonProperty("quality") val quality: Int? = null)
    data class BiliAudioResource(@JsonProperty("url") val url: String? = null)
    data class BiliSubtitleData(@JsonProperty("subtitles") val subtitles: List<BiliSubtitle>? = null)
    data class BiliSubtitle(@JsonProperty("url") val url: String? = null, @JsonProperty("lang") val lang: String? = null, @JsonProperty("lang_doc") val langDoc: String? = null)
}
