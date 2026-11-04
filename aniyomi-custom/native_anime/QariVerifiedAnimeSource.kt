package eu.kanade.tachiyomi.source.anime

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable

/**
 * Native Aniyomi catalog backed by titles discovered in a GeckoView page after
 * normal, user-initiated browsing. Never reads Chrome cookies or defeats Cloudflare.
 * It deliberately contains ANIME ONLY; it is not a manga/manhwa reader.
 */
class QariVerifiedAnimeSource(private val appContext: Context) : AnimeHttpSource() {
    override val name = "ArabsHentai"
    override val lang = "ar"
    override val baseUrl = "https://arabshentai.com"
    override val supportsLatest = true

    override fun getHomeUrl() = "$baseUrl/anime/"

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        return QariVerifiedAnimeCatalog.page(appContext, page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        return QariVerifiedAnimeCatalog.page(appContext, page)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return QariVerifiedAnimeCatalog.page(appContext, page, query)
    }

    @Suppress("DEPRECATION")
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return Observable.just(QariVerifiedAnimeCatalog.page(appContext, page))
    }

    @Suppress("DEPRECATION")
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        return Observable.just(QariVerifiedAnimeCatalog.page(appContext, page))
    }

    @Suppress("DEPRECATION")
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> {
        return Observable.just(QariVerifiedAnimeCatalog.page(appContext, page, query))
    }

    @Suppress("DEPRECATION")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return QariVerifiedAnimeCatalog.details(appContext, anime) ?: anime
    }

    @Suppress("DEPRECATION")
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return Observable.just(QariVerifiedAnimeCatalog.details(appContext, anime) ?: anime)
    }

    @Suppress("DEPRECATION")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return QariVerifiedAnimeCatalog.episodes(appContext, anime.url)
    }

    @Suppress("DEPRECATION")
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return Observable.just(QariVerifiedAnimeCatalog.episodes(appContext, anime.url))
    }

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val details = if (fetchDetails) {
            QariVerifiedAnimeCatalog.details(appContext, anime) ?: anime
        } else {
            anime
        }
        val cachedEpisodes = if (fetchEpisodes) {
            QariVerifiedAnimeCatalog.episodes(appContext, anime.url)
        } else {
            emptyList()
        }
        return SAnimeEpisodeUpdate(
            details,
            if (fetchEpisodes && cachedEpisodes.isNotEmpty()) cachedEpisodes else episodes,
        )
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate {
        val details = if (fetchDetails) {
            QariVerifiedAnimeCatalog.details(appContext, anime) ?: anime
        } else {
            anime
        }
        return SAnimeSeasonUpdate(details, seasons)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // The in-browser catalog does not imply permission or ability to resolve
        // protected video players. Do not invent a working stream URL.
        throw UnsupportedOperationException(
            "لم يتم ربط مشغل الفيديو بعد؛ افتح صفحة الحلقة في GeckoView داخل التطبيق.",
        )
    }
}

/** Persist a bounded, user-visible catalog in the host application's own storage. */
object QariVerifiedAnimeCatalog {
    private const val PREFS = "qari_verified_anime_catalog_v1"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 350
    private const val PAGE_SIZE = 30
    private const val HOST = "arabshentai.com"

    private fun allowedHost(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" &&
            (uri.host?.lowercase() == HOST || uri.host?.lowercase() == "www.$HOST")
    }

    private fun safePath(url: String): String {
        return Uri.parse(url).path.orEmpty().lowercase()
    }

    private fun isMangaOrNavigation(url: String): Boolean {
        val path = safePath(url)
        return path.contains("/manga") || path.contains("manhwa") ||
            path.contains("مانجا") || path.contains("مانهوا") ||
            path.contains("تصنيفات") || path.contains("/category/") ||
            path.contains("/tag/") || path.contains("/page/") ||
            path.contains("/login") || path.contains("/register") ||
            path.trimEnd('/') in setOf("", "/", "/anime")
    }

    private fun validAnimeUrl(url: String): Boolean {
        return url.length in 20..1500 && allowedHost(url) && !isMangaOrNavigation(url)
    }

    private fun validCatalogPage(url: String): Boolean {
        if (!allowedHost(url)) return false
        val path = safePath(url)
        return (path.startsWith("/anime/") || path.contains("تصنيفات-الأنمي")) &&
            !path.contains("/manga") && !path.contains("manhwa")
    }

    private fun normalized(url: String): String {
        return Uri.parse(url).buildUpon().authority(HOST).fragment(null).build().toString().trimEnd('/')
    }

    private fun relative(url: String): String {
        val uri = Uri.parse(url)
        return uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun storage(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): LinkedHashMap<String, JSONObject> {
        val map = linkedMapOf<String, JSONObject>()
        val stored = storage(context).getString(KEY_ITEMS, "[]").orEmpty()
        val json = try { JSONArray(stored) } catch (_: Exception) { JSONArray() }
        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (validAnimeUrl(url)) map[normalized(url)] = obj
        }
        return map
    }

    private fun save(context: Context, map: Map<String, JSONObject>) {
        val array = JSONArray()
        map.values.take(MAX_ITEMS).forEach { array.put(it) }
        storage(context).edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    @Synchronized
    fun mergeListing(context: Context, pageUrl: String, items: JSONArray): Int {
        if (!validCatalogPage(pageUrl)) return 0
        val incoming = linkedMapOf<String, JSONObject>()
        for (i in 0 until minOf(items.length(), 90)) {
            val item = items.optJSONObject(i) ?: continue
            val url = item.optString("url")
            val title = item.optString("title").trim().take(220)
            if (!validAnimeUrl(url) || title.length < 3) continue
            if (title.contains("مانجا") || title.contains("مانهوا") ||
                title.contains("manga", ignoreCase = true) ||
                title.contains("manhwa", ignoreCase = true)
            ) continue
            val cover = item.optString("cover").take(1500)
            val entry = JSONObject()
            entry.put("url", normalized(url))
            entry.put("title", title)
            if (cover.startsWith("https://")) entry.put("cover", cover)
            incoming[normalized(url)] = entry
        }
        if (incoming.isEmpty()) return 0
        val old = load(context)
        val merged = linkedMapOf<String, JSONObject>()
        incoming.forEach { (key, value) ->
            val prior = old[key]
            if (prior != null) {
                if (value.optString("cover").isBlank() && prior.optString("cover").isNotBlank()) {
                    value.put("cover", prior.optString("cover"))
                }
                if (prior.has("description")) value.put("description", prior.optString("description"))
                if (prior.has("episodes")) value.put("episodes", prior.optJSONArray("episodes"))
            }
            merged[key] = value
        }
        old.forEach { (key, value) -> if (key !in merged) merged[key] = value }
        save(context, merged)
        return incoming.size
    }

    @Synchronized
    fun mergeDetails(context: Context, pageUrl: String, data: JSONObject): Int {
        if (!validAnimeUrl(pageUrl)) return 0
        val old = load(context)
        val key = normalized(pageUrl)
        val entry = old[key] ?: return 0 // Only enrich titles already discovered in /anime/.
        val description = data.optString("description").take(3000)
        if (description.isNotBlank()) entry.put("description", description)
        val episodes = data.optJSONArray("episodes") ?: JSONArray()
        val cleaned = JSONArray()
        for (i in 0 until minOf(episodes.length(), 200)) {
            val item = episodes.optJSONObject(i) ?: continue
            val url = item.optString("url")
            val title = item.optString("title").trim().take(200)
            if (!allowedHost(url) || isMangaOrNavigation(url) || title.length < 2) continue
            cleaned.put(JSONObject().put("url", normalized(url)).put("title", title))
        }
        if (cleaned.length() > 0) entry.put("episodes", cleaned)
        save(context, old)
        return cleaned.length()
    }

    @Synchronized
    fun page(context: Context, page: Int, query: String = ""): AnimesPage {
        val filtered = load(context).values.filter {
            query.isBlank() || it.optString("title").contains(query, ignoreCase = true)
        }
        val start = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtLeast(0)
        val list = filtered.drop(start).take(PAGE_SIZE).map { entry ->
            SAnime.create().apply {
                url = relative(entry.optString("url"))
                title = entry.optString("title")
                thumbnail_url = entry.optString("cover").takeIf(String::isNotBlank)
            }
        }
        return AnimesPage(list, start + PAGE_SIZE < filtered.size)
    }

    @Synchronized
    fun details(context: Context, anime: SAnime): SAnime? {
        val stored = load(context)[normalized("https://$HOST" + anime.url)] ?: return null
        return anime.copy().apply {
            title = stored.optString("title").ifBlank { anime.title }
            thumbnail_url = stored.optString("cover").takeIf(String::isNotBlank) ?: anime.thumbnail_url
            description = stored.optString("description").ifBlank {
                "العنوان مستورد من صفحة الأنمي بعد التحقق. افتح صفحة الأنمي داخل GeckoView لجلب الحلقات المتاحة."
            }
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    @Synchronized
    fun episodes(context: Context, animeUrl: String): List<SEpisode> {
        val key = normalized("https://$HOST" + animeUrl)
        val list = load(context)[key]?.optJSONArray("episodes") ?: return emptyList()
        val out = arrayListOf<SEpisode>()
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            val address = obj.optString("url")
            if (!allowedHost(address)) continue
            out.add(SEpisode.create().apply {
                url = relative(address)
                name = obj.optString("title").ifBlank { "حلقة" }
                episode_number = Regex("\\d+").find(name)?.value?.toFloatOrNull() ?: -1f
            })
        }
        return out
    }
}
