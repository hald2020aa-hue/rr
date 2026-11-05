package eu.kanade.tachiyomi.extension.ar.arabshentai

import android.util.Base64
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Mihon manga extension: four independent source entries, each with its own
 * catalog and the same filter set. Uses Mihon's own network cookie jar.
 * It never extracts or fabricates anti-bot tokens.
 */
@Source
abstract class ArabsHentai : KeiSource() {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    private val sectionType: String?
        get() = when {
            name.contains("مانهوا") || name.contains("مانهو") -> "manhwa"
            name.contains("دوجينشي") -> "doujinshi"
            name.contains("كوميكس") -> "comic"
            else -> null // Preserve the upstream manga source ID and category.
        }

    private fun catalogueUrl(page: Int, order: String, state: String? = null): HttpUrl =
        ("$baseUrl/manga/page/$page/").toHttpUrl().newBuilder().apply {
            sectionType?.let { addQueryParameter("type", it) }
            addQueryParameter("orderby", order)
            state?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("state", it) }
        }.build()

    // Separate catalog routes are preserved for manga, manhwa, doujinshi and comics.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = catalogueUrl(page, "new-manga").toString()
        return parseMangaList(loadDocument(url), url)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = catalogueUrl(page, "new_chapter").toString()
        return parseMangaList(loadDocument(url), url)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "new_chapter"
        val statuses = filters.filterIsInstance<StatusFilter>().flatMap { filter ->
            filter.state.filter { it.state }.map { it.uriPart }
        }.distinct()
        val genres = filters.filterIsInstance<GenresFilter>().flatMap { filter ->
            filter.state.filter { it.state }.map { it.uriPart }
        }.toMutableList()
        filters.filterIsInstance<GenreTextFilter>().firstOrNull()?.state?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { genres.add(it) }
        val selectedGenres = genres.distinct()
        val useSearchRoute = query.isNotBlank() || selectedGenres.isNotEmpty() || statuses.size > 1

        val url = if (useSearchRoute) {
            ("$baseUrl/page/$page/").toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query)
                selectedGenres.forEach { addQueryParameter("genre[]", it) }
                statuses.forEach { addQueryParameter("status[]", it) }
                filters.filterIsInstance<GenresOpFilter>().firstOrNull()?.toUriPart()
                    ?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("op", it) }
                sectionType?.let { addQueryParameter("type", it) }
                addQueryParameter("orderby", sort)
            }.build()
        } else {
            catalogueUrl(page, sort, statuses.firstOrNull())
        }
        return parseMangaList(loadDocument(url.toString()), url.toString())
    }

    private suspend fun parseMangaList(document: Document, url: String): MangasPage {
        val first = parseCatalogPage(document)
        if (first.mangas.isNotEmpty()) return first
        // The verified site may inject cards client-side after the native HTML loads.
        val rendered = renderedPage(url)?.takeIf { !isChallenge(it) } ?: return first
        return parseCatalogPage(rendered)
    }

    private fun parseCatalogPage(document: Document): MangasPage {
        requireReadable(document)
        val cards = document.select(
            "#archive-content .wp-manga, .search-page .result-item article:not(:has(.tvshows)), " +
                ".page-item-detail, .manga-item, .manga-card, .manga-list .item",
        )
        val titles = cards.mapNotNull { it.toCatalogueManga() }.distinctBy { it.url }
        val hasNext = document.selectFirst(
            ".pagination a.arrow_pag i#nextpagination, .pagination span.current + a, " +
                ".pagination a.next, .pagination a[rel=next], a[rel=next]",
        ) != null
        return MangasPage(titles, hasNext)
    }

    private fun Element.toCatalogueManga(): SManga? {
        val anchor = selectFirst(
            ".data h3 a[href], .details .title a[href], h3 a[href], h2 a[href], " +
                ".post-title a[href], a[href][title]",
        ) ?: return null
        val url = anchor.absUrl("href")
        if (!sameHost(url) || url.substringBefore("?").trimEnd('/').endsWith("/manga")) return null
        val workTitle = anchor.text().ifBlank { anchor.attr("title") }.ifBlank {
            selectFirst("img")?.attr("alt").orEmpty()
        }.trim()
        if (workTitle.length < 2) return null
        return SManga.create().apply {
            setUrlWithoutDomain(url)
            title = workTitle
            thumbnail_url = selectFirst(".poster img, .image .thumbnail img, .item-thumb img, img")?.imgAttr()
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga)
        var document = loadDocument(url)
        var chapterList = parseChapters(document, url)
        // A browser-rendered DOM is needed for chapter lists inserted after page load.
        if (chapterList.isEmpty()) {
            renderedPage(url)?.takeIf { !isChallenge(it) }?.let { rendered ->
                val renderedChapters = parseChapters(rendered, url)
                if (renderedChapters.isNotEmpty()) {
                    document = rendered
                    chapterList = renderedChapters
                }
            }
        }

        val content = document.selectFirst(".content") ?: document
        val updated = manga.apply {
            content.selectFirst(".sheader .data h1, h1.entry-title, .post-title h1")
                ?.let { title = it.text() }
            thumbnail_url = content.selectFirst(".sheader .poster img, .summary_image img")?.imgAttr()
                ?: thumbnail_url
            val genres = mutableListOf<String>()
            content.selectFirst("#manga-info")?.let { info ->
                description = "؜" + info.select(".wp-content p").text() + "\n" +
                    "أسماء أُخرى: " + info.select("div b:contains(أسماء أُخرى) + span").text()
                status = info.select("div b:contains(حالة المانجا) + span").text().parseStatus()
                author = info.select("div b:contains(الكاتب) + span a").text()
                artist = info.select("div b:contains(الرسام) + span a").text()
                genres += info.select("div b:contains(نوع العمل) + span a").text()
            }
            genres += content.select(".data .sgeneros a, .genres a, .tags-content a").map { it.text() }
            genre = genres.filter { it.isNotBlank() }.distinct().joinToString()
        }
        // Don't silently overwrite an existing good chapter list with an empty result.
        if (chapterList.isEmpty() && chapters.isNotEmpty()) chapterList = chapters
        if (chapterList.isEmpty() && isChallenge(document)) {
            throw IllegalStateException(VERIFY_MESSAGE)
        }
        return SMangaUpdate(updated, chapterList)
    }

    private fun parseChapters(document: Document, workUrl: String): List<SChapter> {
        val workPath = runCatching { URI(workUrl).path.trimEnd('/').lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val seen = linkedSetOf<String>()
        val selectors = "#chapter-list a[href], #chapters a[href], .wp-manga-chapter a[href], " +
            ".listing-chapters_wrap a[href], .chapter-list a[href], .chapters a[href], " +
            ".main.version-chap a[href], .c-page__list a[href], .chapter-item a[href], " +
            ".episodes a[href], a[href*='chapter-'], a[href*='/chapter/'], a[href*='/read/']"
        val list = document.select(selectors).mapNotNull { anchor ->
            val url = anchor.absUrl("href").substringBefore('#')
            if (!sameHost(url) || !seen.add(url) || url == workUrl) return@mapNotNull null
            val chapterPath = runCatching { URI(url).path.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
            val linkedToWork = workPath.isNotEmpty() && chapterPath.startsWith("$workPath/")
            val explicitlyChapter = Regex("chapter|read|capitulo|capítulo|oneshot|الفصل", RegexOption.IGNORE_CASE)
                .containsMatchIn(chapterPath)
            if (!linkedToWork && !explicitlyChapter) return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(url)
                name = anchor.selectFirst(".chapternum")?.text()?.takeIf { it.isNotBlank() }
                    ?: anchor.text().trim().ifBlank { "الفصل" }
                date_upload = dateFormat.tryParseDate(anchor.parent()?.selectFirst(".chapterdate")?.text().orEmpty())
            }
        }
        if (list.isNotEmpty()) return list
        // Only a real on-page reader allows a one-shot fallback.
        if (document.selectFirst(".oneshot-reader img, .reading-content img, #readerarea img") != null) {
            return listOf(SChapter.create().apply {
                setUrlWithoutDomain(workUrl)
                name = "عمل من فصل واحد"
            })
        }
        return emptyList()
    }

    private fun String?.parseStatus() = when {
        this == null -> SManga.UNKNOWN
        contains("مستمر", ignoreCase = true) || contains("ongoing", ignoreCase = true) -> SManga.ONGOING
        contains("مكتمل", ignoreCase = true) || contains("completed", ignoreCase = true) -> SManga.COMPLETED
        contains("متوقف", ignoreCase = true) -> SManga.ON_HIATUS
        contains("ملغي", ignoreCase = true) || contains("ملغية", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = getChapterUrl(chapter)
        var document = loadDocument(url)
        var images = readImages(document)
        if (images.isEmpty()) {
            renderedPage(url)?.takeIf { !isChallenge(it) }?.let { rendered ->
                val newImages = readImages(rendered)
                if (newImages.isNotEmpty()) {
                    images = newImages
                    document = rendered
                }
            }
        }
        if (images.isEmpty()) throw IllegalStateException(
            if (isChallenge(document)) VERIFY_MESSAGE else "لم تظهر صور الفصل في المصدر؛ افتح الفصل عبر المتصفح للتحقق من الصفحة.",
        )
        return images.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    private fun readImages(document: Document): List<String> {
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val imagesMatch = Regex("""(?:const|let|var)\s+images\s*=\s*(\[[\s\S]*?])\s*;""").find(scripts)
        val scripted = imagesMatch?.groupValues?.getOrNull(1)?.let { raw ->
            runCatching { raw.parseAs<List<ImageDto>>() }.getOrNull()
        }.orEmpty().mapNotNull { image ->
            image.url.decodeImageUrl()
        }
        if (scripted.isNotEmpty()) return scripted.distinct()

        return document.select(
            ".chapter_image img, .oneshot-reader .image-item img, .reading-content img, " +
                ".page-break img, #readerarea img, .chapter-content img, " +
                ".reader img, .chapter-page img, img.wp-manga-chapter-img",
        ).mapNotNull { img ->
            val url = img.imgAttr().orEmpty()
            url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.takeUnless { it.contains("placeholder", true) || it.contains("logo", true) }
        }.distinct()
    }

    private fun String.decodeImageUrl(): String? {
        if (startsWith("https://") || startsWith("http://")) return this
        val decoded = runCatching { String(Base64.decode(this, Base64.DEFAULT)) }.getOrNull()
        return decoded?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun Element.imgAttr(): String? {
        val attributes = listOf("data-src", "data-lazy-src", "data-cfsrc", "bv-data-src", "src", "srcset")
        return attributes.firstNotNullOfOrNull { key ->
            attr("abs:$key").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?.substringBefore(',')?.substringBefore(' ')
        }
    }

    private fun sameHost(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme == "https" && uri.host?.removePrefix("www.") == "arabshentai.com" && uri.rawUserInfo == null
    }.getOrDefault(false)

    private suspend fun loadDocument(url: String): Document {
        val native = runCatching { client.get(url, Fetch.DOCUMENT).asJsoup() }.getOrNull()
        if (native != null && !isChallenge(native)) return native
        // Browser reuses the host app's WebView verification context; it does not solve challenges.
        renderedPage(url)?.takeIf { !isChallenge(it) }?.let { return it }
        throw IllegalStateException(VERIFY_MESSAGE)
    }

    private suspend fun renderedPage(url: String): Document? = runCatching {
        val html = runWebView<String> {
            javaScriptEnabled = true
            domStorageEnabled = true
            onPageFinished { finished ->
                if (finished.startsWith(baseUrl)) {
                    evaluateJs("document.documentElement.outerHTML") { rendered -> resolve(rendered) }
                }
            }
            loadUrl(url)
        }
        Jsoup.parse(html, url)
    }.getOrNull()

    private fun isChallenge(document: Document): Boolean {
        val title = document.title().lowercase(Locale.ROOT)
        return document.selectFirst("#challenge-form, #cf-challenge-running") != null ||
            "just a moment" in title || "checking your browser" in title ||
            "تحقق من أنك إنسان" in title
    }

    private fun requireReadable(document: Document) {
        if (isChallenge(document)) throw IllegalStateException(VERIFY_MESSAGE)
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val options = runCatching {
            loadDocument("$baseUrl/تصنيفات/").select(
                "#archive-content ul.genre-list li.item-genre .genre-data a, " +
                    ".genre-list a, .genres a, a[href*='/genre/']",
            ).mapNotNull { anchor ->
                anchor.text().trim().takeIf { it.isNotBlank() }?.let { it to it }
            }.distinctBy { it.second }
        }.getOrDefault(emptyList())
        return options.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            StatusFilter(),
            GenreTextFilter(),
        )
        data?.let { runCatching { it.parseAs<List<Pair<String, String>>>() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }?.let { filters.add(GenresFilter(it)) }
        filters.add(GenresOpFilter())
        return FilterList(filters)
    }

    companion object {
        private const val VERIFY_MESSAGE =
            "المصدر يتطلب التحقق عبر متصفح Mihon. افتح الموقع وأكمل التحقق مرة واحدة ثم أعد المحاولة؛ " +
                "إذا استمر الحظر، فالموقع لم يشارك جلسة صالحة مع محرك الفصول."
    }
}

@Serializable
data class ImageDto(val url: String)
