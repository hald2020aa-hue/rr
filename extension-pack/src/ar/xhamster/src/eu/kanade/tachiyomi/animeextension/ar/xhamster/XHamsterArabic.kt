package eu.kanade.tachiyomi.animeextension.ar.xhamster

import android.net.Uri
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

/** Reads public XHamster pages; it does not bypass account, age, CAPTCHA, DRM or private-content checks. */
@Source
abstract class XHamsterArabic : AnimeHttpLegacySource() {
    override val supportsLatest = true
    private val prefs by getPreferencesLazy()

    private data class NavEntry(val label: String, val url: String)
    // RR_XHAMSTER_PAGING_SEARCH_V3
    private enum class ListingKind { VIDEOS, CATEGORIES, CHANNELS, GROUPS, CREATORS, PORNSTARS }
    private val dedupeLock = Any()

    private val sections = listOf(
        NavEntry("الأحدث", "$baseUrl/newest"),
        NavEntry("الفئات", "$baseUrl/categories"),
        NavEntry("القنوات", "$baseUrl/channels"),
        NavEntry("المجموعات", "$baseUrl/groups"),
        NavEntry("الناشرون", "$baseUrl/creators"),
        NavEntry("النجمات", "$baseUrl/pornstars"),
        NavEntry("فيديوهات NAOC", "$baseUrl/creators/videos/naoc"),
        NavEntry("مسابقات NAOC", "$baseUrl/creators/contest/naoc"),
    )

    private class SectionFilter(private val entries: List<NavEntry>) : AnimeFilter.Select<String>(
        "أقسام XHamster", entries.map { it.label }.toTypedArray(),
    ) {
        fun selectedUrl(): String? = entries.getOrNull(state)?.url
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("بحث الناشر: @username | القناة: channel:name | المجموعة: group:name"),
        SectionFilter(sections),
    )

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "ar,en-US;q=0.8,en;q=0.6")

    override fun popularAnimeRequest(page: Int): Request =
        listingRequest("$baseUrl/newest", page, "popular")

    override fun latestUpdatesRequest(page: Int): Request =
        listingRequest("$baseUrl/newest", page, "latest")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleaned = query.trim()
        val author = commandValue(cleaned, "@", "author:", "الناشر:")
        val channel = commandValue(cleaned, "channel:", "القناة:")
        val group = commandValue(cleaned, "group:", "المجموعة:")
        val creator = commandValue(cleaned, "creator:", "المنشئ:")
        val route = when {
            !author.isNullOrBlank() -> "$baseUrl/users/${encodeSegment(author)}/videos"
            !channel.isNullOrBlank() -> "$baseUrl/channels/${encodeSegment(channel)}"
            !group.isNullOrBlank() -> "$baseUrl/groups/${encodeSegment(group)}"
            !creator.isNullOrBlank() -> "$baseUrl/creators/videos/${encodeSegment(creator)}"
            cleaned.isNotBlank() -> "$baseUrl/search/" + URLEncoder.encode(cleaned, "UTF-8").replace("+", "%20")
            else -> filters.filterIsInstance<SectionFilter>().firstOrNull()?.selectedUrl() ?: "$baseUrl/newest"
        }
        return listingRequest(route, page, "search:$route")
    }

    private fun commandValue(query: String, vararg prefixes: String): String? {
        val prefix = prefixes.firstOrNull { query.startsWith(it, ignoreCase = true) } ?: return null
        return query.substring(prefix.length).trim().trimStart('@').takeIf { it.isNotBlank() }
    }

    private fun encodeSegment(value: String): String = Uri.encode(value.trim())

    private fun listingRequest(start: String, page: Int, feed: String): Request {
        val token = feedToken(feed)
        if (page <= 1) clearFeedState(token)
        return GET(pageUrl(start, page, token), headers).newBuilder()
            .header("X-RR-Feed-Key", token)
            .build()
    }

    private fun feedToken(feed: String): String = feed.lowercase().hashCode().toUInt().toString(16)

    private fun clearFeedState(token: String) {
        val editor = prefs.edit().remove("xhamster_seen_$token")
        prefs.all.keys.filter { it.startsWith("xhamster_next_${token}_") }.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)
    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)
    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()
        val path = Uri.parse(currentUrl).path.orEmpty().lowercase().trimEnd('/')
        val kind = when (path) {
            "/categories" -> ListingKind.CATEGORIES
            "/channels" -> ListingKind.CHANNELS
            "/groups" -> ListingKind.GROUPS
            "/creators" -> ListingKind.CREATORS
            "/pornstars" -> ListingKind.PORNSTARS
            else -> ListingKind.VIDEOS
        }
        val jsonItems = if (kind == ListingKind.VIDEOS) parseVideoThumbs(document) else emptyList()
        val items = if (jsonItems.isNotEmpty()) {
            jsonItems.mapNotNull { item ->
                val url = safeUrl(item.url) ?: return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(url)
                    title = item.title
                    thumbnail_url = item.thumbnail
                }
            }
        } else {
            parseHtmlCards(document, kind)
        }
        val feed = response.request.header("X-RR-Feed-Key") ?: feedToken("path:$path")
        val hasNext = recordNext(document, currentUrl, feed)
        if (items.isEmpty()) {
            val challenge = document.text().contains("Just a moment", true) ||
                document.html().contains("cf-challenge", true)
            throw IllegalStateException(
                if (challenge) "الموقع طلب تحققاً بشرياً؛ أكمله يدوياً في المتصفح ثم أعد المحاولة."
                else "لم أجد عناصر في الصفحة؛ ربما تغير تصميم الموقع أو لم تُحمّل قائمته."
            )
        }
        return AnimesPage(dedupeAcrossFeed(items, feed), hasNext)
    }

    private fun dedupeAcrossFeed(items: List<SAnime>, feed: String): List<SAnime> = synchronized(dedupeLock) {
        val key = "xhamster_seen_$feed"
        val seen = (prefs.getStringSet(key, emptySet<String>()) ?: emptySet()).toMutableSet()
        val fresh = items.filter { seen.add(canonicalItemKey(it.url)) }
        val bounded = if (seen.size > 6000) seen.toList().takeLast(6000).toSet() else seen
        prefs.edit().putStringSet(key, bounded).apply()
        fresh
    }

    private fun canonicalItemKey(raw: String): String {
        val absolute = safeUrl(raw) ?: raw
        val uri = Uri.parse(absolute)
        val path = uri.path.orEmpty().trimEnd('/').lowercase()
        val segments = uri.pathSegments
        if (segments.size >= 2 && (segments.first().equals("videos", true) || segments.first().equals("movies", true))) {
            val id = segments.last().substringAfterLast('-')
            if (id.length >= 6) return "video:${id.lowercase()}"
        }
        return "url:$path"
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val model = parseInitials(document)?.optJSONObject("videoModel")
        val title = model?.optString("title").orEmpty()
            .ifBlank { document.selectFirst("h1")?.text().orEmpty() }
            .ifBlank { document.title() }
        val description = model?.optString("description").orEmpty()
            .ifBlank { document.selectFirst("meta[name=description]")?.attr("content").orEmpty() }
        val thumbnail = model?.optString("thumbURL").orEmpty()
            .ifBlank { model?.optString("imageURL").orEmpty() }
            .ifBlank { document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
            .ifBlank { document.selectFirst("video[poster]")?.absUrl("poster").orEmpty() }
        val categories = model?.optJSONArray("categories")
        val genres = categories?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val value = entry.optString("name").trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }.orEmpty().toMutableList()

        val authorObject = model?.optJSONObject("author")
        val authorPage = authorObject?.optString("pageURL").orEmpty().ifBlank { authorObject?.optString("url").orEmpty() }
        val authorName = authorObject?.optString("username").orEmpty()
            .ifBlank { authorObject?.optString("name").orEmpty() }
            .ifBlank { model?.optString("authorName").orEmpty() }
        val authorHandle = profileSlug(authorPage) ?: authorName
        document.select("a[href]").forEach { anchor ->
            val url = safeUrl(anchor.absUrl("href")) ?: return@forEach
            val segments = Uri.parse(url).pathSegments
            val slug = segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            when (segments.firstOrNull()?.lowercase()) {
                "channels" -> genres.add("channel:$slug")
                "groups" -> genres.add("group:$slug")
            }
        }
        return SAnime.create().apply {
            this.title = title
            this.description = description
            author = authorHandle.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" }
            thumbnail_url = thumbnail.ifBlank { null }
            genre = genres.distinct().joinToString(", ").ifBlank { null }
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()
        if (isVideoUrl(currentUrl)) {
            val model = parseInitials(document)?.optJSONObject("videoModel")
            val title = model?.optString("title").orEmpty()
                .ifBlank { document.selectFirst("h1")?.text().orEmpty() }
                .ifBlank { "Video" }
            return listOf(SEpisode.create().apply {
                setUrlWithoutDomain(currentUrl)
                name = title
                episode_number = 1f
            })
        }
        val jsonEpisodes = parseVideoThumbs(document).mapIndexed { index, item ->
            SEpisode.create().apply {
                setUrlWithoutDomain(item.url)
                name = item.title
                episode_number = (index + 1).toFloat()
                preview_url = item.thumbnail
            }
        }
        if (jsonEpisodes.isNotEmpty()) return jsonEpisodes
        return parseHtmlCards(document, ListingKind.VIDEOS).mapIndexed { index, item ->
            SEpisode.create().apply {
                setUrlWithoutDomain(item.url)
                name = item.title
                episode_number = (index + 1).toFloat()
                preview_url = item.thumbnail_url
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> = XHamsterVideoExtractor.videos(
        response.asJsoup(), response.request.url.toString(), headers, name,
    )

    private fun profileSlug(raw: String): String? {
        if (raw.isBlank()) return null
        val url = safeUrl(raw) ?: return raw.trim().trim('/').substringAfterLast('/').takeIf { it.isNotBlank() }
        val segments = Uri.parse(url).pathSegments
        val usersIndex = segments.indexOfFirst { it.equals("users", true) }
        if (usersIndex >= 0) return segments.getOrNull(usersIndex + 1)?.takeIf { it.isNotBlank() }
        return segments.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    private data class VideoThumb(val url: String, val title: String, val thumbnail: String?)

    private fun parseVideoThumbs(document: Document): List<VideoThumb> {
        val initials = parseInitials(document) ?: return emptyList()
        val array = findArray(initials, "videoThumbProps") ?: return emptyList()
        val result = linkedMapOf<String, VideoThumb>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = safeUrl(item.optString("pageURL")) ?: continue
            val title = item.optString("title").trim()
            if (title.isBlank()) continue
            val image = absoluteAsset(item.optString("imageURL").ifBlank { item.optString("thumbURL") })
            result.putIfAbsent(canonicalItemKey(url), VideoThumb(url, title, image))
        }
        return result.values.toList()
    }

    private fun parseInitials(document: Document): JSONObject? {
        val script = document.selectFirst("#initials-script")
            ?: document.select("script").firstOrNull { it.data().contains("window.initials") }
            ?: return null
        val raw = script.data().ifBlank { script.html() }
        val marker = raw.indexOf("window.initials")
        val start = if (marker >= 0) raw.indexOf('{', marker) else raw.indexOf('{')
        if (start < 0) return null
        val end = matchingJsonObjectEnd(raw, start) ?: return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    private fun matchingJsonObjectEnd(raw: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return null
    }

    private fun findArray(value: JSONObject, key: String): JSONArray? {
        val keys = value.keys()
        while (keys.hasNext()) {
            val currentKey = keys.next()
            val child = value.opt(currentKey)
            if (currentKey == key && child is JSONArray) return child
            if (child is JSONObject) findArray(child, key)?.let { return it }
            if (child is JSONArray) {
                for (i in 0 until child.length()) {
                    val nested = child.opt(i)
                    if (nested is JSONObject) findArray(nested, key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseHtmlCards(document: Document, kind: ListingKind): List<SAnime> {
        val result = linkedMapOf<String, SAnime>()
        for (anchor in document.select("a[href]")) {
            val url = safeUrl(anchor.absUrl("href")) ?: continue
            val path = Uri.parse(url).path.orEmpty().lowercase()
            val wanted = when (kind) {
                ListingKind.VIDEOS -> isVideoPath(path)
                ListingKind.CATEGORIES -> path.startsWith("/categories/") && path != "/categories/"
                ListingKind.CHANNELS -> path.startsWith("/channels/") && path != "/channels/"
                ListingKind.GROUPS -> path.startsWith("/groups/") && path != "/groups/"
                ListingKind.CREATORS -> path.startsWith("/creators/videos/") ||
                    path.startsWith("/creators/contest/")
                ListingKind.PORNSTARS -> path.startsWith("/pornstars/") ||
                    (path.startsWith("/creators/") && !path.startsWith("/creators/videos/") &&
                        !path.startsWith("/creators/contest/"))
            }
            if (!wanted || anchor.closest("nav,header,footer,aside,.pagination,.page-button") != null) continue
            val card = anchor.closest(
                ".thumb-list__item,.thumb-list__item-container,.video-thumb,.video-item," +
                    ".video-card,.pornstar-item,.channel-item,.category-item,.thumb,article,li",
            ) ?: anchor
            val image = card.selectFirst("img")
            val thumbnail = image?.let { img ->
                listOf("data-src", "data-original", "data-lazy-src", "src")
                    .firstNotNullOfOrNull { attr -> absoluteAsset(img.absUrl(attr).ifBlank { img.attr(attr) }) }
            }
            val title = card.selectFirst(".thumb-title,.video-thumb__title,.title,.name,.video-title,h2,h3,h4")
                ?.text().orEmpty()
                .ifBlank { anchor.attr("title") }
                .ifBlank { image?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }
                .trim()
            if (title.length !in 2..240) continue
            result.putIfAbsent(canonicalItemKey(url), SAnime.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = thumbnail
            })
            if (result.size >= 100) break
        }
        return result.values.toList()
    }

    private fun isVideoUrl(url: String): Boolean = isVideoPath(Uri.parse(url).path.orEmpty())

    private fun isVideoPath(path: String): Boolean =
        path.lowercase().startsWith("/videos/") || path.lowercase().startsWith("/movies/")

    private fun absoluteAsset(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val candidate = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> baseUrl + value
            else -> value
        }
        return candidate.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun safeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.length > 1900) return null
        val candidate = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> baseUrl + value
            else -> value
        }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host != "xhamster.com" && !host.endsWith(".xhamster.com")) return null
        if (uri.scheme !in setOf("http", "https")) return null
        return uri.buildUpon().fragment(null).build().toString()
    }

    private fun pageKey(feed: String, url: String): String {
        val uri = Uri.parse(url)
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }.lowercase()
        val query = uri.queryParameterNames.sorted().joinToString("&") { name -> "$name=${uri.getQueryParameter(name).orEmpty()}" }
        val canonical = if (query.isBlank()) path else "$path?$query"
        return "xhamster_next_${feed}_${canonical.hashCode().toUInt().toString(16)}"
    }

    private fun pageUrl(start: String, page: Int, feed: String): String {
        var current = safeUrl(start) ?: "$baseUrl/newest"
        for (index in 2..page.coerceIn(1, 300)) {
            current = prefs.getString(pageKey(feed, current), null)?.let(::safeUrl) ?: return current
        }
        return current
    }

    private fun recordNext(document: Document, currentUrl: String, feed: String): Boolean {
        val choices = document.select(
            "a.page-button-link[href],a[rel=next][href],.pagination a[href]," +
                ".pagination-next a[href],a.next[href],li.next a[href]",
        )
        val currentPage = Regex("(?i)(?:/page/|/)(\\d+)(?:/?$|[?])")
            .find(Uri.parse(currentUrl).path.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Uri.parse(currentUrl).getQueryParameter("page")?.toIntOrNull() ?: 1
        val next = choices.mapNotNull { anchor ->
            val url = safeUrl(anchor.absUrl("href")) ?: return@mapNotNull null
            if (url == currentUrl) return@mapNotNull null
            val label = anchor.text().trim()
            val isNext = anchor.attr("rel").equals("next", true) || anchor.hasClass("next") ||
                Regex("(?i)^(next|التالي|التالية|→|›|»)$").containsMatchIn(label)
            val number = Regex("(?i)(?:/page/|/)(\\d+)(?:/?$|[?])")
                .find(Uri.parse(url).path.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Uri.parse(url).getQueryParameter("page")?.toIntOrNull()
            if (!isNext && (number == null || number <= currentPage)) return@mapNotNull null
            (number ?: currentPage + 1) to url
        }.sortedBy { it.first }.firstOrNull { it.first > currentPage }?.second ?: return false
        if (next == currentUrl) return false
        prefs.edit().putString(pageKey(feed, currentUrl), next).apply()
        return true
    }
}
