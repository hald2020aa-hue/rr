package eu.kanade.tachiyomi.animeextension.ar.hentaitime

import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Follows only navigation URLs actually observed on pages of the source host. */
internal data class QariNavEntry(val label: String, val url: String)

internal class QariSiteNavigation(
    private val baseUrl: String,
    private val initialPath: String,
    private val prefs: SharedPreferences,
    private val defaultLatest: String? = null,
    private val defaultPopular: String? = null,
) {
    private val host = Uri.parse(baseUrl).host.orEmpty().lowercase()
    private val key = "qari_nav_discovered_v2"
    private val childPattern = Regex("(?i)(/tag/|/tags/|/category/|/categories/|/genre/|/genres/|/type/|/types/|/فئة/|/تصنيف/)")
    private val bannedNames = Regex("(?i)(loli|shota|underage|child|minor|قاصر|أطفال|طفل)")
    private val excludedPath = Regex("(?i)(/login/?$|/register/?$|/privacy/?$|/contact/?$|/wp-admin/|/cdn-cgi/|/feed/?$|/author/)")
    private val latestPattern = Regex("(?i)(الأحدث|الحديثة|الجديد|آخر التحديثات|latest|recent|newest|new videos|last updates)")
    private val popularPattern = Regex("(?i)(الأكثر مشاهدة|الرائج|الأكثر شعبية|popular|trending|most.viewed|top.rated|most popular)")

    fun isSite(url: String): Boolean = try {
        val uri = Uri.parse(url)
        uri.scheme == "https" && uri.host?.lowercase()?.removePrefix("www.") == host.removePrefix("www.")
    } catch (_: Exception) { false }

    private fun safe(url: String): String? = url.trim()
        .takeIf { isSite(it) && it.length <= 1900 && !excludedPath.containsMatchIn(Uri.parse(it).path.orEmpty()) }
        ?.let { Uri.parse(it).buildUpon().fragment(null).build().toString() }

    private fun readLinks(): List<QariNavEntry> {
        val saved = try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in 0 until minOf(saved.length(), 350)) {
                val obj = saved.optJSONObject(i) ?: continue
                val url = safe(obj.optString("url")) ?: continue
                val label = obj.optString("label").trim().take(75)
                if (label.length < 2 || bannedNames.containsMatchIn(label)) continue
                add(QariNavEntry(label, url))
            }
        }
    }

    fun sections(): List<QariNavEntry> = listOf(QariNavEntry("اختر القسم", "")) +
        readLinks().filterNot { isCategory(it.url) }.take(100)

    fun categories(): List<QariNavEntry> = listOf(QariNavEntry("اختر التصنيف", "")) +
        readLinks().filter { isCategory(it.url) }.take(200)

    private fun isCategory(url: String): Boolean {
        val uri = Uri.parse(url)
        return childPattern.containsMatchIn(uri.encodedPath.orEmpty()) ||
            listOf("category", "cat", "tag", "genre", "type").any { uri.getQueryParameter(it) != null }
    }

    private fun rootUrl(): String = if (initialPath.startsWith("/")) "$baseUrl$initialPath" else baseUrl + "/"

    fun route(kind: String): String {
        // A known server-rendered listing is safer than a site's JS-only trending placeholder.
        val fixed = if (kind == "latest") defaultLatest else defaultPopular
        if (fixed != null && isSite(fixed)) return fixed
        val pattern = if (kind == "latest") latestPattern else popularPattern
        return readLinks().firstOrNull { pattern.containsMatchIn(it.label) }?.url ?: rootUrl()
    }

    private fun pageKey(url: String): String = "qari_nav_next_v2_" + url.hashCode().toUInt().toString(16)

    /** Never generate unsupported site-specific pagination URLs. */
    fun pageUrl(start: String, page: Int): String {
        var current = safe(start) ?: rootUrl()
        for (p in 2..page.coerceIn(1, 1500)) {
            current = prefs.getString(pageKey(current), null)?.takeIf(::isSite) ?: return current
        }
        return current
    }

    /** Collect the site's actual menu and categories; no fabricated category slugs. */
    fun discover(document: Document) {
        val found = linkedMapOf<String, QariNavEntry>()
        readLinks().forEach { found[it.url] = it }
        val selector = "nav a[href], body > header a[href], .site-header a[href], " +
            "#header a[href], .menu a[href], .navbar a[href], " +
            ".categories a[href], .tags a[href], " +
            "a[href*='/category/'], a[href*='/categories/'], a[href*='/tag/'], " +
            "a[href*='/genre/'], .dropdown-menu a[href]"
        for (anchor in document.select(selector)) {
            val url = safe(anchor.absUrl("href")) ?: continue
            val label = anchor.ownText().ifBlank { anchor.text() }.trim().replace(Regex("\\s+"), " ").take(75)
            if (label.length !in 2..75 || bannedNames.containsMatchIn(label)) continue
            if (url.trimEnd('/') == baseUrl.trimEnd('/') && label != "الرئيسية") continue
            val path = Uri.parse(url).path.orEmpty().lowercase()
            if (path == "/" || path.matches(Regex("^/page/\\d+/?$"))) continue
            if (found.size >= 350 && url !in found) break
            found[url] = QariNavEntry(label, url)
        }
        if (found.isEmpty()) return
        val arr = JSONArray()
        found.values.take(350).forEach { arr.put(JSONObject().put("label", it.label).put("url", it.url)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun isEntry(url: String): Boolean {
        val valid = safe(url) ?: return false
        val path = Uri.parse(valid).path.orEmpty().lowercase().trimEnd('/')
        if (path.isBlank() || path in setOf("/", "/anime", "/series", "/categories", "/tags", "/category")) return false
        if (childPattern.containsMatchIn(path)) return false
        if (Regex("(?i)(/page/\\d+|/login|/register|/watch-later|/my-account)").containsMatchIn(path)) return false
        return true
    }

    /** Returns true only if a real next-page anchor was observed and recorded. */
    fun recordNext(document: Document, currentUrl: String): Boolean {
        val choices = document.select(
            "a[rel=next][href], .pagination a[href], .wp-pagenavi a[href], " +
                ".page-numbers a[href], a.next[href], li.next a[href], .nav-links a[href]",
        )
        val currentPageNumber = Regex("(?i)(?:/page/|/)(\\d+)(?:/?$|[?])")
            .find(Uri.parse(currentUrl).path.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Uri.parse(currentUrl).getQueryParameter("paged")?.toIntOrNull()
            ?: Uri.parse(currentUrl).getQueryParameter("page")?.toIntOrNull() ?: 1
        val possible = choices.mapNotNull { a ->
            val url = safe(a.absUrl("href")) ?: return@mapNotNull null
            if (url == currentUrl || !isSite(url)) return@mapNotNull null
            val label = a.text().trim()
            val nextLabel = a.attr("rel") == "next" || a.hasClass("next") ||
                Regex("(?i)^(next|التالي|التالية|المزيد|→|›|»)$").containsMatchIn(label)
            val uri = Uri.parse(url)
            val n = Regex("(?i)(?:/page/|/)(\\d+)(?:/?$|[?])")
                .find(uri.path.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: uri.getQueryParameter("paged")?.toIntOrNull()
                ?: uri.getQueryParameter("page")?.toIntOrNull()
            if (!nextLabel && (n == null || n <= currentPageNumber)) return@mapNotNull null
            (n ?: currentPageNumber + 1) to url
        }.sortedBy { it.first }
        val next = possible.firstOrNull { it.first > currentPageNumber }?.second ?: return false
        prefs.edit().putString(pageKey(currentUrl), next).apply()
        return true
    }
}
