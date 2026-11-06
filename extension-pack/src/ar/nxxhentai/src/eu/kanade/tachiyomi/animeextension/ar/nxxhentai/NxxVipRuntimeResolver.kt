package eu.kanade.tachiyomi.animeextension.ar.nxxhentai

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Experimental NXX VIPServer runtime fallback.
 * Only runs after the existing static HTML/DooPlay resolver fails.
 * Reads explicit player links from the episode page/declared AJAX options,
 * then observes the public HLS requests made by the player JavaScript.
 * No guessed video IDs, no Chrome cookie access, no CAPTCHA or DRM bypass.
 * In-app WebView may still be rejected by server-side verification.
 */
internal class NxxVipRuntimeResolver {
    private val application: Application by injectLazy()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Captured(val url: String, val referer: String)

    private val playerHost = "player.nxxhentai.net"
    private val mediaHosts = setOf(
        playerHost,
        "edge.nexaflow.stream",
        "edge.nxxhentai.stream",
        "video.sacdnsedge.com",
    )

    private val playerUrlPattern = Regex(
        """(?i)(?:https?:)?//player\.nxxhentai\.net/[^\s"'<>\\]+""",
    )
    private val mediaPattern = Regex("""(?i)\.(?:m3u8|mp4)(?:[?#]|$)""")

    fun resolve(
        episode: Document,
        episodeUrl: String,
        client: OkHttpClient,
        headers: Headers,
        sourceName: String,
    ): List<Video> {
        val candidates = linkedSetOf<String>()
        discoverFromDocument(episode, episodeUrl, candidates)
        discoverFromDeclaredAjax(episode, episodeUrl, client, headers, candidates)

        // Keep the runtime bounded: two independent candidate pages, at most 11 seconds each.
        for (url in candidates.take(2)) {
            val stream = observe(url, headers, 11L) ?: continue
            val playbackHeaders = headers.newBuilder()
                .removeAll("Cookie")
                .removeAll("Authorization")
                .set("Referer", stream.referer)
                .build()
            return listOf(
                Video(stream.url, "$sourceName • VIPServer • Auto", stream.url, playbackHeaders),
            )
        }
        return emptyList()
    }

    private fun normalizedPlayer(raw: String, base: String): String? {
        val decoded = raw.trim().trim('"', '\'').replace("\\/", "/")
            .replace("&amp;", "&").replace("\\u0026", "&")
        val candidate = if (decoded.startsWith("//")) "https:$decoded" else decoded
        val resolved = runCatching { URI(base).resolve(candidate).normalize() }.getOrNull() ?: return null
        if (resolved.scheme != "https" || resolved.host?.lowercase() != playerHost ||
            resolved.userInfo != null || resolved.port !in listOf(-1, 443)) return null
        return resolved.toASCIIString()
    }

    private fun discoverFromDocument(doc: Document, base: String, out: MutableSet<String>) {
        for (el in doc.select("iframe, [data-iframe], [data-embed], [data-player], [data-src], a[href]")) {
            for (attr in listOf("src", "data-src", "data-iframe", "data-embed", "data-player", "href")) {
                if (el.hasAttr(attr)) normalizedPlayer(el.attr(attr), base)?.let(out::add)
            }
        }
        val serialized = doc.outerHtml().take(2_000_000)
        for (found in playerUrlPattern.findAll(serialized)) {
            normalizedPlayer(found.value, base)?.let(out::add)
        }
    }

    private fun discoverFromDeclaredAjax(
        doc: Document,
        episodeUrl: String,
        client: OkHttpClient,
        headers: Headers,
        out: MutableSet<String>,
    ) {
        val options = doc.select("[data-post][data-nume][data-type]")
            .distinctBy { "${it.attr("data-post")}:${it.attr("data-type")}:${it.attr("data-nume")}" }
            .take(6)
        if (options.isEmpty() || out.size >= 2) return
        val endpoint = "https://nxxhentai.net/wp-admin/admin-ajax.php"
        for (option in options) {
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")
            if (!post.matches(Regex("[0-9]{1,12}")) ||
                !nume.matches(Regex("[a-zA-Z0-9_.-]{1,40}")) ||
                !type.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) continue
            val body = FormBody.Builder()
                .add("action", "doo_player_ajax")
                .add("post", post).add("nume", nume).add("type", type).build()
            val request = Request.Builder().url(endpoint)
                .headers(headers.newBuilder().set("Referer", episodeUrl).build())
                .post(body).build()
            runCatching {
                client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val raw = response.body?.string().orEmpty().take(400_000)
                        val fragments = mutableListOf(raw)
                        if (raw.trimStart().startsWith("{")) {
                            runCatching {
                                val json = JSONObject(raw)
                                for (part in listOfNotNull(json, json.optJSONObject("data"))) {
                                    for (key in listOf("embed_url", "url", "src", "html", "player", "embed")) {
                                        part.optString(key).takeIf(String::isNotBlank)?.let(fragments::add)
                                    }
                                }
                            }
                        }
                        for (fragment in fragments) {
                            normalizedPlayer(fragment, episodeUrl)?.let(out::add)
                            discoverFromDocument(Jsoup.parse(fragment, episodeUrl), episodeUrl, out)
                        }
                    }
            }
            if (out.size >= 2) return
        }
    }

    private fun validMedia(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in mediaHosts &&
            uri.userInfo == null && uri.port in listOf(-1, 443) &&
            mediaPattern.containsMatchIn(raw)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun observe(playerUrl: String, headers: Headers, timeoutSec: Long): Captured? {
        val latch = CountDownLatch(1)
        val capture = AtomicReference<Captured?>(null)
        val viewRef = AtomicReference<WebView?>(null)
        val referrer = "https://nxxhentai.net/"

        mainHandler.post {
            val webView = WebView(application)
            viewRef.set(webView)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            headers["User-Agent"]?.let { webView.settings.userAgentString = it }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (validMedia(url)) {
                        val observedReferer = request.requestHeaders["Referer"]
                            ?.takeIf { it.startsWith("https://") }
                            ?: playerUrl
                        if (capture.compareAndSet(null, Captured(url, observedReferer))) {
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // This may start an ordinary public HTML5 player; no token construction.
                    view.evaluateJavascript(
                        """(function(){var v=document.querySelector('video'); if(v) v.play().catch(function(){});})();""",
                        null,
                    )
                }
            }
            webView.loadUrl(playerUrl, mapOf("Referer" to referrer))
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        mainHandler.post {
            viewRef.getAndSet(null)?.let { view ->
                view.stopLoading()
                view.destroy()
            }
        }
        return capture.get()
    }
}
