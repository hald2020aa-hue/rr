package eu.kanade.tachiyomi.animeextension.ar.zebzob

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Resolves streams disclosed by HTML5 players, page scripts or embedded HTML players.
 * Never synthesizes fake video links or bypasses access/verification protections.
 */
internal object QariPlaybackExtractor {
    private data class Embed(val url: String, val referer: String)
    private val mediaExt = Regex("""\.(?:mp4|m3u8|mpd)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val qualityPattern = Regex("""(?i)(2160|1440|1080|720|480|360|240)\s*p?""")
    private val propertyPattern = Regex("""(?i)(?:file|src|source|url|hls|videoUrl|videoSrc)\s*["']?\s*[:=]\s*["']([^"']+)["']""")

    private fun safeUrl(value: String, origin: String): String? {
        val raw = value.trim().trim('"', '\'')
            .replace("\\/", "/").replace("\\u0026", "&")
            .replace("&amp;", "&").trimEnd(')', ']', ',', ';')
        if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("blob:")) return null
        return runCatching {
            val u = URI(origin).resolve(raw).normalize()
            val host = u.host?.lowercase().orEmpty()
            if (u.scheme != "https" || host.isBlank() || u.userInfo != null ||
                u.port !in listOf(-1, 443) || host == "localhost" ||
                host.endsWith(".local") || host.endsWith(".internal") ||
                host.contains(':') || host.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}"""))
            ) null else u.toASCIIString()
        }.getOrNull()
    }

    private fun looksLikeEmbed(value: String): Boolean = runCatching {
        val path = URI(value).path.orEmpty().lowercase()
        listOf("/embed", "/player", "/e/", "/v/").any(path::contains)
    }.getOrDefault(false)

    fun videos(
        episode: Document, episodeUrl: String, client: OkHttpClient,
        headers: Headers, sourceName: String,
    ): List<Video> {
        val direct = linkedMapOf<String, String>()
        val embeds = ArrayDeque<Embed>()
        val visited = hashSetOf<String>()
        var embedCount = 0

        fun addMedia(raw: String, ref: String, explicit: Boolean = false) {
            val url = safeUrl(raw, ref) ?: return
            if ((explicit || mediaExt.containsMatchIn(url)) && direct.size < 24)
                direct.putIfAbsent(url, ref)
        }
        fun addEmbed(raw: String, ref: String) {
            val url = safeUrl(raw, ref) ?: return
            if (mediaExt.containsMatchIn(url)) addMedia(url, ref)
            else if (url != ref && url !in visited && embeds.size < 18) {
                embeds.addLast(Embed(url, ref))
                embedCount++
            }
        }
        fun scan(doc: Document, ref: String) {
            // HTML5 video, Video.js, JWPlayer, Plyr and common lazy-loaded elements.
            for (element in doc.select(
                "video[src], video source[src], source[src], [data-file], [data-video-url], " +
                    "[data-hls], [data-mp4], [data-video], meta[property=og:video], " +
                    "meta[property=og:video:url], meta[name=twitter:player:stream]"
            )) {
                val isVideo = element.tagName() == "video" ||
                    (element.tagName() == "source" && element.parent()?.tagName() == "video")
                for (attr in listOf("src", "data-src", "data-file", "data-video-url",
                    "data-hls", "data-mp4", "data-video", "content")) {
                    if (element.hasAttr(attr)) addMedia(element.attr(attr), ref, isVideo)
                }
            }
            // Fetch actual embedded player HTML rather than passing iframe pages to MPV.
            for (element in doc.select("iframe, [data-iframe], [data-embed-url], [data-player-url]")) {
                for (attr in listOf("src", "data-src", "data-lazy-src", "data-iframe",
                    "data-embed-url", "data-player-url")) {
                    if (element.hasAttr(attr)) addEmbed(element.attr(attr), ref)
                }
            }
            for (element in doc.select("a[href*='/embed/'], a[href*='/player/'], a[href*='/e/']")) {
                addEmbed(element.attr("href"), ref)
            }
            val embeddedConfigs = doc.select("script").map { it.data() } +
                doc.select("[data-setup]").map { it.attr("data-setup") }
            for (config in embeddedConfigs) {
                val code = config.replace("\\/", "/")
                    .replace("\\u0026", "&").replace("&amp;", "&")
                for (match in urlPattern.findAll(code)) {
                    val url = match.value
                    if (mediaExt.containsMatchIn(url)) addMedia(url, ref)
                    else if (looksLikeEmbed(url)) addEmbed(url, ref)
                }
                for (match in propertyPattern.findAll(code)) {
                    val url = match.groupValues[1]
                    if (mediaExt.containsMatchIn(url)) addMedia(url, ref)
                    else if (url.startsWith("https://") && looksLikeEmbed(url)) addEmbed(url, ref)
                }
            }
        }

        scan(episode, episodeUrl)
        val boundedClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        var checked = 0
        while (embeds.isNotEmpty() && checked < 6 && direct.size < 12) {
            val embedded = embeds.removeFirst()
            if (!visited.add(embedded.url)) continue
            checked++
            runCatching {
                val requestHeaders = headers.newBuilder().set("Referer", embedded.referer).build()
                boundedClient.newCall(GET(embedded.url, requestHeaders)).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val resolvedUrl = response.request.url.toString()
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    if (type.startsWith("video/") || type.contains("mpegurl") ||
                        type.contains("dash+xml")) {
                        addMedia(resolvedUrl, embedded.referer, true)
                    } else if (type.isBlank() || type.contains("html") || type.contains("json")) {
                        val html = response.body?.string().orEmpty()
                        if (html.length <= 2_000_000 &&
                            !html.contains("cf-challenge", ignoreCase = true) &&
                            !html.contains("just a moment", ignoreCase = true)) {
                            scan(Jsoup.parse(html, resolvedUrl), resolvedUrl)
                        }
                    }
                }
            }
        }
        if (direct.isEmpty() && embedCount > 0) {
            throw IllegalStateException(
                "المشغل المدمج لا يعرض رابط فيديو مباشراً؛ قد يحتاج JavaScript أو جلسة تحقق أو تسجيل دخول"
            )
        }
        return direct.map { (url, referer) ->
            val quality = qualityPattern.find(url)?.value ?: "Auto"
            val provider = URI(url).host.orEmpty().removePrefix("www.")
            Video(url, sourceName + " • " + quality + " • " + provider, url,
                headers.newBuilder().set("Referer", referer).build())
        }.sortedByDescending {
            qualityPattern.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}
