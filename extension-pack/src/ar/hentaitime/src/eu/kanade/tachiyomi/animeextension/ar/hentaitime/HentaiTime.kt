package eu.kanade.tachiyomi.animeextension.ar.hentaitime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Experimental source for HentaiTime.
 * Works with ordinary public HTML and direct video URLs only.
 * No authentication, DRM, captchas, Cloudflare bypass, or hidden player decryption.
 * Site HTML may change; live episode and player behavior is NOT verified.
 */
@Source
abstract class HentaiTime : AnimeHttpLegacySource() {
    override val supportsLatest = true

    private val listingPath = "/?filter=latest"
    private val itemSelector = "article a[href], .video-item a[href], .thumb a[href], .video-card a[href], .post a[href]"
    private val isSeriesSite = false
    private val sourceHost = "hentai-time.com"
    private val episodePattern = Regex("(?i)(?:episode|watch|الحلقة|حلقة|الحلقات)")
    private val mediaPattern = Regex("(?i)\\.(?:mp4|m3u8|mpd)(?:[?#]|$)")
    private val qualityPattern = Regex("(?i)(2160|1440|1080|720|480|360|240)\\s*p?")

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int) = GET(listingUrl(page), headers)
    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/?s=$encoded" + if (page > 1) "&paged=$page" else ""
        return GET(url, headers)
    }

    private fun listingUrl(page: Int): String {
        val root = "$baseUrl$listingPath"
        if (page == 1) return root
        return if (root.contains('?')) "$root&page=$page" else "$root" +
            (if (root.endsWith('/')) "" else "/") + "page/$page/"
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.asJsoup()
        val entries = linkedMapOf<String, SAnime>()
        val candidates = page.select(itemSelector).ifEmpty { page.select("article a[href], .card a[href], a[href]") }
        for (link in candidates) {
            val href = link.absUrl("href")
            if (!isValidEntry(href)) continue
            val label = link.selectFirst(".title, .name, h2, h3, h4")?.text().orEmpty()
                .ifBlank { link.attr("title") }
                .ifBlank { link.selectFirst("img")?.let { it.attr("alt").ifBlank { it.attr("title") } }.orEmpty() }
                .ifBlank { link.text() }
                .trim()
            if (label.length !in 3..240) continue
            val img = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
            val cover = img?.let {
                listOf("data-src", "data-original", "data-lazy-src", "src")
                    .firstNotNullOfOrNull { attr -> it.absUrl(attr).takeIf(String::isNotBlank) }
            }
            entries.putIfAbsent(href, SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = label
                thumbnail_url = cover
            })
        }
        val more = page.selectFirst("a[rel=next], .pagination a.next, a.next, .wp-pagenavi a.nextpostslink") != null
        return AnimesPage(entries.values.toList(), more)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    private fun isValidEntry(url: String): Boolean {
        if (!url.startsWith(baseUrl) || url == baseUrl || url.endsWith("#")) return false
        val path = url.removePrefix(baseUrl).substringBefore('?').trimEnd('/')
        if (path.isBlank() || path in setOf("/", "/anime", "/series", "/login", "/register")) return false
        if (path.contains("/tag/") || path.contains("/category/") || path.contains("/page/")) return false
        return if (isSeriesSite) {
            val segment = listingPath.trimEnd('/')
            path.startsWith("$segment/") && path.length > segment.length + 3
        } else {
            path.split('/').filter(String::isNotBlank).size >= 1 && !path.contains("wp-")
        }
    }

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

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val found = linkedSetOf<String>()
        fun accept(raw: String) {
            val url = raw.replace("\\/", "/").replace("&amp;", "&").trim()
            if (url.startsWith("https://") && mediaPattern.containsMatchIn(url)) found += url
        }
        document.select("video[src], video source[src], source[src], a[href], [data-src]").forEach { element ->
            accept(element.absUrl("src"))
            accept(element.absUrl("href"))
            accept(element.attr("data-src"))
        }
        // Decode directly exposed URLs only; never attempt to defeat access protection.
        val scriptUrl = Regex("https?://[^\\s\\\"'<>]+?\\.(?:mp4|m3u8|mpd)(?:\\?[^\\s\\\"'<>]*)?", RegexOption.IGNORE_CASE)
        document.select("script").forEach { script ->
            scriptUrl.findAll(script.data().replace("\\/", "/")).forEach { accept(it.value) }
        }
        return found.map { url ->
            val quality = qualityPattern.find(url)?.value ?: "Auto"
            Video(url, "$quality - HentaiTime", url, headers)
        }.sortedByDescending { qualityPattern.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    }
}
