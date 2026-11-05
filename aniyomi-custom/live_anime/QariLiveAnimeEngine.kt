package eu.kanade.tachiyomi.source.anime

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Direct, demand-driven Aniyomi source. Never crawls the whole archive.
 * Network requests are ordinary public GETs; when a site requires interactive
 * verification, the user's browser can collect the visible catalog into the
 * existing QariVerifiedAnimeCatalog (no cookie/token export or challenge bypass).
 */
internal class QariLiveAnimeEngine(
    private val context: Context,
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val root = "https://arabshentai.com"
    private val prefs = context.applicationContext.getSharedPreferences("qari_live_anime_v1", Context.MODE_PRIVATE)
    private val ttlMillis = 15 * 60 * 1000L

    private fun isSite(url: String): Boolean {
        val parsed = Uri.parse(url)
        return parsed.scheme == "https" &&
            parsed.host?.lowercase()?.removePrefix("www.") == "arabshentai.com"
    }

    private fun absolute(url: String): String = if (url.startsWith("https://")) url else root +
        (if (url.startsWith('/')) url else "/$url")

    private fun relative(url: String): String {
        val uri = Uri.parse(url)
        return uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun safeItem(url: String): Boolean {
        if (!isSite(url)) return false
        val path = Uri.parse(url).path.orEmpty().lowercase().trimEnd('/')
        if (path.isBlank() || path in setOf("", "/", "/anime", "/login", "/register")) return false
        return !Regex("/(?:manga|manhwa|tag|category|page|login|register)(?:/|$)").containsMatchIn(path) &&
            !path.contains("/anime/page/")
    }

    private fun request(url: String): Document {
        if (!isSite(url)) throw IOException("عنوان المصدر غير صالح")
        val response = client.newCall(GET(url, headers)).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}: افتح الموقع في المتصفح للتحقق ثم ارجع")
            val html = it.body?.string().orEmpty()
            if (html.isBlank()) throw IOException("رد فارغ من موقع الأنمي")
            val doc = Jsoup.parse(html, it.request.url.toString())
            val title = doc.title().lowercase()
            if (title.contains("just a moment") || title.contains("checking your browser") ||
                doc.selectFirst("#challenge-form, #cf-challenge-running") != null
            ) {
                throw IOException("الموقع طلب تحققًا تفاعليًا؛ افتحه في GeckoView")
            }
            return doc
        }
    }

    private fun cover(item: Element): String {
        val image = item.selectFirst("img") ?: return ""
        for (key in listOf("data-src", "data-original", "data-lazy-src", "src")) {
            val url = image.absUrl(key)
            if (url.startsWith("https://")) return url
        }
        return ""
    }

    private data class Listing(val items: JSONArray, val nextUrl: String?)

    private fun parseListing(doc: Document, pageUrl: String, page: Int): Listing {
        val map = linkedMapOf<String, JSONObject>()
        val cards = doc.select("article, .anime-item, .video-item, .poster, .thumb, .post, .item, .card, .film-item")
        val anchors = if (cards.isNotEmpty()) cards.flatMap { it.select("a[href]") } else doc.select("a[href]")
        for (a in anchors) {
            val url = a.absUrl("href").substringBefore('#')
            if (!safeItem(url)) continue
            val box = a.closest("article, .anime-item, .video-item, .poster, .thumb, .post, .item, .card, .film-item") ?: a
            val image = cover(box).ifBlank { cover(a) }
            if (image.isBlank()) continue
            val title = box.selectFirst("h2, h3, h4, .title, .name, .entry-title")?.text().orEmpty()
                .ifBlank { a.attr("title") }
                .ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { box.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { a.text() }.trim()
            if (title.length !in 3..220 || Regex("مانجا|مانهوا|manga|manhwa", RegexOption.IGNORE_CASE)
                    .containsMatchIn(title)
            ) continue
            if (!map.containsKey(url)) {
                map[url] = JSONObject().put("url", url).put("title", title).put("cover", image)
            }
            if (map.size >= 90) break
        }
        val current = page.coerceAtLeast(1)
        var nextPage: Pair<Int, String>? = null
        for (a in doc.select("a[href]")) {
            val url = a.absUrl("href")
            if (!isSite(url)) continue
            val path = Uri.parse(url).path.orEmpty()
            if (!Regex("^/anime(?:/page/[0-9]+)?/?$").matches(path)) continue
            val n = Regex("/page/([0-9]+)").find(path)?.groupValues?.get(1)?.toIntOrNull()
                ?: Uri.parse(url).getQueryParameter("paged")?.toIntOrNull()
                ?: Uri.parse(url).getQueryParameter("page")?.toIntOrNull() ?: continue
            if (n in (current + 1)..500 && (nextPage == null || n < nextPage.first)) {
                nextPage = n to url
            }
        }
        return Listing(JSONArray().also { array -> map.values.forEach(array::put) }, nextPage?.second)
    }

    private fun key(page: Int, query: String) = "p${page.coerceAtLeast(1)}:${query.trim().lowercase().hashCode()}"

    private fun cacheRead(page: Int, query: String, freshOnly: Boolean): AnimesPage? {
        val key = key(page, query)
        val json = prefs.getString("cache:$key", null) ?: return null
        val age = System.currentTimeMillis() - prefs.getLong("time:$key", 0L)
        if (freshOnly && age !in 0..ttlMillis) return null
        val data = try { JSONObject(json) } catch (_: Exception) { return null }
        val array = data.optJSONArray("items") ?: return null
        val animes = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (!safeItem(url)) continue
                add(SAnime.create().apply {
                    this.url = relative(url)
                    title = item.optString("title")
                    thumbnail_url = item.optString("cover").takeIf(String::isNotBlank)
                })
            }
        }
        return AnimesPage(animes, data.optBoolean("more", false))
    }

    private fun cacheWrite(page: Int, query: String, listing: Listing) {
        val key = key(page, query)
        val entry = JSONObject().put("items", listing.items).put("more", listing.nextUrl != null)
        val editor = prefs.edit()
            .putString("cache:$key", entry.toString())
            .putLong("time:$key", System.currentTimeMillis())
        if (query.isEmpty() && listing.nextUrl != null) {
            editor.putString("archive:${page + 1}", listing.nextUrl)
        }
        check(editor.commit())
    }

    suspend fun listing(page: Int, query: String = ""): AnimesPage = withContext(Dispatchers.IO) {
        cacheRead(page, query, freshOnly = true)?.let { return@withContext it }
        val url = if (query.isNotBlank()) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            "$root/?s=$encoded" + if (page > 1) "&paged=$page" else ""
        } else if (page <= 1) {
            "$root/anime/"
        } else {
            prefs.getString("archive:$page", null) ?: return@withContext AnimesPage(emptyList(), false)
        }
        try {
            val listing = parseListing(request(url), url, page)
            if (listing.items.length() == 0) {
                throw IOException("لم تظهر بطاقات أنمي في الصفحة؛ افتح الموقع للتحقق")
            }
            cacheWrite(page, query, listing)
            if (query.isBlank()) {
                QariVerifiedAnimeCatalog.mergeListing(context, url, listing.items)
            }
            cacheRead(page, query, freshOnly = false)!!
        } catch (e: IOException) {
            cacheRead(page, query, freshOnly = false)?.let { return@withContext it }
            val older = QariVerifiedAnimeCatalog.page(context, page, query)
            if (older.animes.isNotEmpty()) older else throw e
        }
    }

    private fun description(doc: Document): String = doc.selectFirst(
        ".description, .synopsis, .story, .entry-content p, .post-content p",
    )?.text()?.take(3000).orEmpty()

    private fun parseEpisodes(doc: Document, pageUrl: String): JSONArray {
        val episodes = linkedMapOf<String, String>()
        for (a in doc.select(".episodes a[href], .episode-list a[href], .seasons a[href], " +
            "a[href*='/episode/'], a[href*='/watch/'], a[href]")) {
            val link = a.absUrl("href")
            val label = a.text().ifBlank { a.attr("title") }.trim()
            if (!safeItem(link) || link == pageUrl || label.length !in 2..200) continue
            if (!Regex("episode|watch|الحلقة|حلقة|مشاهدة|part", RegexOption.IGNORE_CASE)
                    .containsMatchIn(link + " " + label)
            ) continue
            episodes.putIfAbsent(link, label)
            if (episodes.size >= 1000) break
        }
        return JSONArray().also { array ->
            episodes.forEach { (url, title) -> array.put(JSONObject().put("url", url).put("title", title)) }
        }
    }

    suspend fun details(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val link = absolute(anime.url)
        try {
            val doc = request(link)
            val name = doc.selectFirst("h1, .entry-title, .post-title")?.text().orEmpty()
            val detail = JSONObject()
                .put("title", name.ifBlank { anime.title })
                .put("description", description(doc))
                .put("episodes", parseEpisodes(doc, link))
            QariVerifiedAnimeCatalog.mergeDetails(context, link, detail)
            anime.copy().apply {
                title = name.ifBlank { anime.title }
                description = detail.optString("description")
                thumbnail_url = doc.selectFirst(".poster img, .thumb img")?.absUrl("src")
                    ?.takeIf(String::isNotBlank) ?: anime.thumbnail_url
                initialized = true
            }
        } catch (e: IOException) {
            QariVerifiedAnimeCatalog.details(context, anime) ?: throw e
        }
    }

    suspend fun episodes(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val link = absolute(anime.url)
        try {
            val doc = request(link)
            val list = parseEpisodes(doc, link)
            if (list.length() == 0) {
                list.put(JSONObject().put("url", link).put("title", "الحلقة 1"))
            }
            QariVerifiedAnimeCatalog.mergeDetails(
                context,
                link,
                JSONObject().put("title", anime.title).put("episodes", list),
            )
            buildList {
                for (i in 0 until list.length()) {
                    val obj = list.optJSONObject(i) ?: continue
                    add(SEpisode.create().apply {
                        url = relative(obj.optString("url"))
                        name = obj.optString("title")
                        episode_number = Regex("[0-9]+(?:\\.[0-9]+)?")
                            .find(name)?.value?.toFloatOrNull() ?: -1f
                    })
                }
            }
        } catch (e: IOException) {
            QariVerifiedAnimeCatalog.episodes(context, anime.url).takeIf { it.isNotEmpty() } ?: throw e
        }
    }

    suspend fun videos(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val doc = request(absolute(episode.url))
        val videos = linkedSetOf<String>()
        fun add(raw: String) {
            val url = raw.replace("\\/", "/").replace("&amp;", "&").trim()
            if (!url.startsWith("https://")) return
            if (!Regex("\\.(?:mp4|m3u8|mpd)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) return
            videos += url
        }
        doc.select("video[src], video source[src], source[src], a[href], [data-src]").forEach { element ->
            add(element.absUrl("src"))
            add(element.absUrl("href"))
            add(element.absUrl("data-src"))
        }
        val directUrl = Regex("""https?://[^\s\"'<>]+?\.(?:mp4|m3u8|mpd)(?:\?[^\s\"'<>]*)?""", RegexOption.IGNORE_CASE)
        for (script in doc.select("script")) {
            directUrl.findAll(script.data().replace("\\/", "/")).forEach { add(it.value) }
        }
        videos.map { url ->
            val resolution = Regex("(2160|1440|1080|720|480|360|240)p?", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)?.toIntOrNull()
            Video(videoUrl = url, videoTitle = resolution?.let { "${it}p" } ?: "Auto", headers = headers)
        }.sortedByDescending { it.videoTitle.removeSuffix("p").toIntOrNull() ?: 0 }
    }
}
