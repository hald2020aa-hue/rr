package eu.kanade.tachiyomi.animeextension.ar.zebzob

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Qari V5: source-specific HTML playback adapters for the seven configured sources.
 * Media must be exposed by ordinary public pages or supported player endpoints.
 * No fabricated video entries, DRM removal, CAPTCHA bypass or Chrome cookie export.
 */
internal object QariPlaybackExtractor {
    private enum class Family { DOOPLAY, KVS, WORDPRESS, GENERIC }

    private data class Profile(
        val family: Family,
        val playerSelectors: String,
        val ajaxEnabled: Boolean,
        val videoAttributes: List<String>,
    )

    // These are parser strategies, not claims that the sites use a particular backend.
    // AJAX is requested only if DooPlay player data is actually observed in HTML.
    private val profiles = mapOf(
        "nxxhentai.net" to Profile(
            Family.DOOPLAY,
            "#playeroptionsul iframe, .dooplay_player iframe, .player iframe, #player iframe",
            true, listOf("data-video", "data-player", "data-src", "data-hls"),
        ),
        "hentaixplanet.com" to Profile(
            Family.KVS,
            ".player-container iframe, #video-player iframe, #player iframe, .video-embed iframe",
            false, listOf("data-video-url", "data-file", "data-src", "data-video"),
        ),
        "zebzob.net" to Profile(
            Family.GENERIC,
            ".video-container iframe, .player iframe, #player iframe, .embed-responsive iframe",
            false, listOf("data-file", "data-embed", "data-src"),
        ),
        "saudihentai.com" to Profile(
            Family.WORDPRESS,
            ".video-player iframe, #player iframe, .player-area iframe, .entry-content iframe",
            true, listOf("data-player-url", "data-src", "data-file"),
        ),
        "hentai-time.com" to Profile(
            Family.KVS,
            "#video-player iframe, .video-player iframe, #player iframe, .video-container iframe",
            false, listOf("data-video-url", "data-file", "data-src"),
        ),
        "ristohentai.com" to Profile(
            Family.WORDPRESS,
            ".post-video iframe, #player iframe, .player iframe, .watch-player iframe",
            true, listOf("data-video-url", "data-player-url", "data-src"),
        ),
        "arabshentai.com" to Profile(
            Family.DOOPLAY,
            "#playeroptionsul iframe, .dooplay_player iframe, #player iframe, .entry-content iframe",
            true, listOf("data-player-url", "data-iframe", "data-src"),
        ),
    )

    private data class Candidate(val url: String, val referer: String, val label: String = "")
    private val mediaPath = Regex("""\.(?:mp4|m3u8|mpd)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
    private val sourceQuality = Regex("""(?i)(2160|1440|1080|720|480|360|240)\s*p?""")
    private val absoluteUrls = Regex("""https?://[^\s\"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val playerProperties = Regex(
        """(?i)(?:file|src|source|url|hls|video_url|video_url_alt|videoUrl|videoSrc|contentUrl|embedUrl)\s*[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']"""
    )
    private val inlineIframe = Regex("""(?is)<iframe[^>]+(?:src|data-src)\s*=\s*[\"']([^\"']+)[\"']""")
    private val srcProperty = Regex("""(?i)(?:src|file|video_url|video_url_alt)\s*=\s*[\"']([^\"']+)[\"']""")
    private val kvsProperty = Regex("""(?i)(?:video_url|video_url_alt|video_url_text|video_url_alt_text)\s*[:=]\s*[\"']([^\"']+)[\"']""")
    private val dooplaySelector =
        "#playeroptionsul [data-post][data-nume][data-type], " +
            ".dooplay_player_option[data-post][data-nume][data-type], " +
            "[data-post][data-nume][data-type]"

    private fun host(value: String): String = runCatching {
        URI(value).host.orEmpty().lowercase().removePrefix("www.")
    }.getOrDefault("")

    private fun allowed(raw: String, referer: String): String? {
        val value = raw.trim().trim('\"', '\'')
            .replace("\\/", "/").replace("\\u0026", "&")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .trimEnd(')', ']', ';', ',', '\'')
        if (value.isBlank() || value.startsWith("data:") || value.startsWith("blob:") ||
            value.startsWith("javascript:", true)) return null
        return runCatching {
            val uri = URI(referer).resolve(value).normalize()
            val name = uri.host.orEmpty().lowercase()
            if (uri.scheme != "https" || name.isBlank() || uri.userInfo != null ||
                uri.port !in listOf(-1, 443) || name == "localhost" ||
                name.endsWith(".local") || name.endsWith(".internal") ||
                name.contains(':') || name.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}"""))) {
                null
            } else uri.toASCIIString()
        }.getOrNull()
    }

    private fun isEmbed(value: String): Boolean {
        val path = runCatching { URI(value).path.orEmpty().lowercase() }.getOrDefault("")
        return listOf("/embed", "/player", "/e/", "/v/", "/watch/").any(path::contains)
    }

    private fun requestHeaders(headers: Headers, url: String, referer: String): Headers {
        val builder = headers.newBuilder()
        // Never forward a site's auth/cookies to unrelated embedded player domains.
        if (host(url) != host(referer)) {
            builder.removeAll("Cookie")
            builder.removeAll("Authorization")
        }
        return builder.set("Referer", referer).build()
    }

    private fun unpackResponse(body: String): List<String> {
        val text = body.trim()
        if (text.isBlank() || text == "0" || text == "-1") return emptyList()
        if (!text.startsWith("{")) return listOf(text)
        return runCatching {
            val json = JSONObject(text)
            val out = mutableListOf<String>()
            for (obj in listOfNotNull(json, json.optJSONObject("data"))) {
                for (key in listOf("embed_url", "url", "src", "file", "html", "embed", "player")) {
                    val value = obj.optString(key)
                    if (value.isNotBlank()) out.add(value)
                }
            }
            out
        }.getOrElse { emptyList() }
    }

    fun videos(
        episode: Document, episodeUrl: String, client: OkHttpClient,
        headers: Headers, sourceName: String,
    ): List<Video> {
        val profile = profiles[host(episodeUrl)] ?: Profile(
            Family.GENERIC, "#player iframe, .player iframe", false, emptyList(),
        )
        val media = linkedMapOf<String, Candidate>()
        val embeds = ArrayDeque<Candidate>()
        val visited = hashSetOf<String>()
        val failures = mutableListOf<String>()

        fun addMedia(raw: String, referer: String, label: String = "", direct: Boolean = false) {
            val url = allowed(raw, referer) ?: return
            if ((direct || mediaPath.containsMatchIn(url)) && media.size < 32) {
                media.putIfAbsent(url, Candidate(url, referer, label))
            }
        }

        fun addEmbed(raw: String, referer: String, label: String = "") {
            val url = allowed(raw, referer) ?: return
            if (mediaPath.containsMatchIn(url)) addMedia(url, referer, label)
            else if (url != referer && url !in visited && embeds.size < 24) {
                embeds.addLast(Candidate(url, referer, label))
            }
        }

        fun parseText(text: String, referer: String) {
            val normalized = text.replace("\\/", "/").replace("\\u0026", "&")
                .replace("&amp;", "&")
            for (found in absoluteUrls.findAll(normalized)) {
                val value = found.value
                if (mediaPath.containsMatchIn(value)) addMedia(value, referer)
                else if (isEmbed(value)) addEmbed(value, referer)
            }
            for (found in playerProperties.findAll(normalized)) {
                val value = found.groupValues[1]
                if (mediaPath.containsMatchIn(value)) addMedia(value, referer)
                else if (isEmbed(value)) addEmbed(value, referer)
            }
            for (found in inlineIframe.findAll(normalized)) addEmbed(found.groupValues[1], referer)
            for (found in srcProperty.findAll(normalized)) {
                val value = found.groupValues[1]
                if (mediaPath.containsMatchIn(value)) addMedia(value, referer)
                else if (isEmbed(value)) addEmbed(value, referer)
            }
            // KVS/WordPress player configuration is commonly embedded in a JS dictionary.
            if (profile.family == Family.KVS) {
                for (found in kvsProperty.findAll(normalized)) {
                    val value = found.groupValues[1]
                    if (mediaPath.containsMatchIn(value)) addMedia(value, referer)
                }
            }
        }

        fun scan(doc: Document, referer: String) {
            for (element in doc.select(
                "video[src], video source[src], source[src], track[src], " +
                    "[data-file], [data-video-url], [data-hls], [data-mp4], " +
                    "meta[property=og:video], meta[property=og:video:url], " +
                    "meta[property=og:video:secure_url], meta[itemprop=contentUrl]"
            )) {
                val isVideo = element.tagName() == "video" ||
                    (element.tagName() == "source" && element.parent()?.tagName() == "video")
                for (key in listOf("src", "data-src", "data-file", "data-video-url",
                    "data-hls", "data-mp4", "content")) {
                    if (element.hasAttr(key)) addMedia(element.attr(key), referer, direct = isVideo)
                }
            }
            for (selector in listOf(
                "iframe[src], iframe[data-src], iframe[data-lazy-src], embed[src], " +
                    "[data-iframe], [data-embed-url], [data-player-url]",
                profile.playerSelectors,
            )) {
                for (element in doc.select(selector)) {
                    for (key in listOf("src", "data-src", "data-lazy-src", "data-iframe",
                        "data-embed-url", "data-player-url")) {
                        if (element.hasAttr(key)) addEmbed(element.attr(key), referer)
                    }
                }
            }
            for (element in doc.select(
                "a[href*='/embed/'], a[href*='/player/'], a[href*='/e/'], " +
                    "[data-video], [data-player], [data-embed], [data-video-url]"
            )) {
                for (key in listOf("href", "data-video", "data-player", "data-embed",
                    "data-video-url") + profile.videoAttributes) {
                    if (!element.hasAttr(key)) continue
                    val value = element.attr(key)
                    if (mediaPath.containsMatchIn(value)) addMedia(value, referer)
                    else if (isEmbed(value)) addEmbed(value, referer)
                }
            }
            for (script in doc.select("script")) {
                val code = script.data()
                if (code.length <= 1_000_000) parseText(code, referer)
            }
            for (element in doc.select("[data-setup], [data-config], script[type='application/ld+json']")) {
                val config = element.attr("data-setup").ifBlank { element.attr("data-config") }
                    .ifBlank { element.data() }
                if (config.length <= 100_000) parseText(config, referer)
            }
        }

        scan(episode, episodeUrl)

        // DooPlay only: request a declared public player option. No guessing endpoints
        // when the actual page lacks its characteristic data-post/nume/type markup.
        val dooplayOptions = if (profile.ajaxEnabled) episode.select(dooplaySelector)
            .distinctBy { "${it.attr("data-post")}:${it.attr("data-type")}:${it.attr("data-nume")}" }
            .take(6) else emptyList()
        val optionsFound = dooplayOptions.size
        if (dooplayOptions.isNotEmpty() && media.isEmpty()) {
            val endpoint = "https://${host(episodeUrl)}/wp-admin/admin-ajax.php"
            val bounded = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
            for (option in dooplayOptions) {
                val post = option.attr("data-post")
                val nume = option.attr("data-nume")
                val type = option.attr("data-type")
                if (!post.matches(Regex("[0-9]{1,12}")) ||
                    !nume.matches(Regex("[a-zA-Z0-9_.-]{1,40}")) ||
                    !type.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) continue
                val body = FormBody.Builder().add("action", "doo_player_ajax")
                    .add("post", post).add("nume", nume).add("type", type).build()
                val request = Request.Builder().url(endpoint)
                    .headers(requestHeaders(headers, endpoint, episodeUrl))
                    .post(body).build()
                runCatching {
                    bounded.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            failures.add("AJAX HTTP ${response.code}")
                            return@use
                        }
                        val content = response.body?.string().orEmpty().take(500_000)
                        for (candidate in unpackResponse(content)) {
                            val html = Jsoup.parseBodyFragment(candidate, episodeUrl)
                            scan(html, episodeUrl)
                            if (candidate.trimStart().startsWith("https://")) {
                                addEmbed(candidate.trim(), episodeUrl)
                            }
                        }
                    }
                }.onFailure { failures.add("AJAX ${it.javaClass.simpleName}") }
                if (media.isNotEmpty()) break
            }
        }

        val boundedClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        var checked = 0
        while (embeds.isNotEmpty() && checked < 8 && media.size < 16) {
            val entry = embeds.removeFirst()
            if (!visited.add(entry.url)) continue
            checked++
            runCatching {
                val sent = requestHeaders(headers, entry.url, entry.referer)
                boundedClient.newCall(GET(entry.url, sent)).execute().use { response ->
                    if (!response.isSuccessful) {
                        failures.add("player HTTP ${response.code}")
                        return@use
                    }
                    val actual = response.request.url.toString()
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    if (type.startsWith("video/") || type.contains("mpegurl") ||
                        type.contains("dash+xml")) {
                        addMedia(actual, entry.referer, direct = true)
                    } else if (type.isBlank() || type.contains("html") || type.contains("json")) {
                        val content = response.body?.string().orEmpty().take(1_500_000)
                        if (content.contains("cf-challenge", ignoreCase = true) ||
                            content.contains("just a moment", ignoreCase = true)) {
                            failures.add("player requires verification")
                        } else {
                            val fragments = unpackResponse(content)
                            for (fragment in fragments) {
                                scan(Jsoup.parse(fragment, actual), actual)
                                if (fragment.startsWith("https://")) addEmbed(fragment, actual)
                            }
                        }
                    }
                }
            }.onFailure { failures.add("player ${it.javaClass.simpleName}") }
        }

        if (media.isEmpty()) {
            val diagnostic = failures.distinct().take(3).joinToString(", ").ifBlank {
                if (checked == 0 && optionsFound == 0) "no recognized playable media in HTML"
                else "no direct stream"
            }
            throw IllegalStateException(
                "لم يتم استخراج فيديو قابل للتشغيل من $sourceName " +
                    "(servers=$optionsFound, embedded=$checked, reason=$diagnostic). " +
                    "قد يكون الخادم محمياً أو يحتاج تشغيل JavaScript في المتصفح"
            )
        }
        return media.values.map { stream ->
            val label = sourceQuality.find(stream.label.ifBlank { stream.url })?.value ?: "Auto"
            val provider = host(stream.url).ifBlank { "Video" }
            Video(stream.url, "$sourceName • $provider • $label", stream.url,
                requestHeaders(headers, stream.url, stream.referer))
        }.sortedByDescending {
            sourceQuality.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}
