package eu.kanade.tachiyomi.animeextension.ar.arabshentai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Experimental source for ArabsHentai.
 * Works with ordinary public HTML and direct video URLs only.
 * No authentication, DRM, captchas, Cloudflare bypass, or hidden player decryption.
 * Site HTML may change; live episode and player behavior is NOT verified.
 */
@Source
abstract class ArabsHentai : AnimeHttpLegacySource() {
    override val supportsLatest = true
    // QARI_LIVE_MENU_FILTERS_V2: navigation is read from real HTML, not guessed.

    private val listingPath = "/anime/"
    private val itemSelector = "article a[href], .video-item a[href], .thumb a[href], .post a[href]"
    private val isSeriesSite = false
    private val sourceHost = "arabshentai.com"

    private val qariPrefs by getPreferencesLazy()
    private val qariNav by lazy {
        QariSiteNavigation(
            baseUrl, listingPath, qariPrefs,
            defaultLatest = null,
            defaultPopular = null,
        )
    }

    private class SiteSectionFilter(val links: List<QariNavEntry>) : AnimeFilter.Select<String>(
        "أقسام الموقع", links.map { it.label }.toTypedArray(),
    ) {
        fun selectedUrl(): String? = links.getOrNull(state)?.url?.takeIf { it.isNotBlank() }
    }

    private class SiteCategoryFilter(val links: List<QariNavEntry>) : AnimeFilter.Select<String>(
        "التصنيفات والفئات الشائعة", links.map { it.label }.toTypedArray(),
    ) {
        fun selectedUrl(): String? = links.getOrNull(state)?.url?.takeIf { it.isNotBlank() }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("افتح قائمة الأنمي مرة لاكتشاف روابط الموقع وتحديث الفلاتر"),
        SiteSectionFilter(qariNav.sections()),
        SiteCategoryFilter(qariNav.categories()),
    )

    private val episodePattern = Regex("(?i)(?:episode|watch|الحلقة|حلقة|الحلقات)")
    private val mediaPattern = Regex("(?i)\\.(?:mp4|m3u8|mpd)(?:[?#]|$)")
    private val qualityPattern = Regex("(?i)(2160|1440|1080|720|480|360|240)\\s*p?")

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int) = GET(listingUrl(page, "popular"), headers)
    override fun latestUpdatesRequest(page: Int) = GET(listingUrl(page, "latest"), headers)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            return GET(qariNav.pageUrl("$baseUrl/?s=$encoded", page), headers)
        }
        val section = filters.filterIsInstance<SiteSectionFilter>().firstOrNull()?.selectedUrl()
        val category = filters.filterIsInstance<SiteCategoryFilter>().firstOrNull()?.selectedUrl()
        val route = category ?: section ?: qariNav.route("popular")
        return GET(qariNav.pageUrl(route, page), headers)
    }

    private fun listingUrl(page: Int, kind: String): String =
        qariNav.pageUrl(qariNav.route(kind), page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // Discover site sections, category URLs and pagination on normal public pages.
        qariNav.discover(document)
        val candidates = document.select(
            "article a[href], .video-item a[href], .thumb a[href], .video-card a[href], " +
                ".post a[href], .item a[href], .card a[href], .poster a[href], " +
                ".series a[href], .movie a[href], a[href]",
        )
        val result = linkedMapOf<String, SAnime>()
        for (link in candidates) {
            val href = link.absUrl("href")
            if (!qariNav.isEntry(href) || link.closest("nav,footer,aside,.menu,.pagination,.site-header,.main-header") != null) continue
            val card = link.closest(".video-item,.video-card,.post,.item,.card,.poster,.thumb,.series,.movie,article") ?: link
            val img = link.selectFirst("img") ?: card.selectFirst("img") ?: continue
            val cover = listOf("data-src", "data-original", "data-lazy-src", "src")
                .firstNotNullOfOrNull { attr -> img.absUrl(attr).takeIf(String::isNotBlank) }
            if (cover.isNullOrBlank()) continue
            val label = card.selectFirst(".title,.name,h2,h3,h4")?.text().orEmpty()
                .ifBlank { link.attr("title") }
                .ifBlank { img.attr("alt") }
                .ifBlank { link.text() }
                .trim()
            if (label.length !in 3..240) continue
            result.putIfAbsent(href, SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = label
                thumbnail_url = cover
            })
            if (result.size >= 100) break
        }
        val hasNext = qariNav.recordNext(document, response.request.url.toString())
        return AnimesPage(result.values.toList(), hasNext)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val titleText = document.selectFirst("h1, .entry-title, .post-title")?.text().orEmpty()
        val descriptionText = document.selectFirst(".description, .synopsis, .story, .entry-content p, .post-content p")?.text().orEmpty()
        val img = document.selectFirst(".poster img, .thumb img, .entry-content img, meta[property=og:image]")
        val cover = if (img?.tagName() == "meta") img.attr("content") else img?.absUrl("src")
        return SAnime.create().apply {
            title = titleText.ifBlank { document.title() }
            description = descriptionText
            thumbnail_url = cover
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val results = linkedMapOf<String, SEpisode>()
        val links = document.select(".episodes a[href], .episode-list a[href], .seasons a[href], a[href*='/episode/'], a[href*='/watch/'], a[href]")
        for (element in links) {
            val url = element.absUrl("href")
            val label = element.text().ifBlank { element.attr("title") }.trim()
            if (!url.startsWith(baseUrl)) continue
            if (!episodePattern.containsMatchIn(url) && !episodePattern.containsMatchIn(label)) continue
            if (url == response.request.url.toString()) continue
            results.putIfAbsent(url, SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = label.ifBlank { "Episode" }
                episode_number = Regex("\\d+").find(name)?.value?.toFloatOrNull() ?: -1f
            })
        }
        if (results.isNotEmpty()) return results.values.toList().reversed()
        // Single-video articles are represented as a one-episode anime.
        return listOf(SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = document.selectFirst("h1")?.text()?.takeIf(String::isNotBlank) ?: "Episode 1"
            episode_number = 1f
        })
    }

    override fun videoListParse(response: Response): List<Video> =
        QariPlaybackExtractor.videos(
            response.asJsoup(), response.request.url.toString(), client, headers, name,
        )
}
