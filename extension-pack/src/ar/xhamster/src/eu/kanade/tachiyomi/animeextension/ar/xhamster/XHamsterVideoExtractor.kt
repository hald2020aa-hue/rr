package eu.kanade.tachiyomi.animeextension.ar.xhamster

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI

/** Extracts public MP4/HLS player URLs. No DRM, CAPTCHA, account or private-content bypass. */
internal object XHamsterVideoExtractor {
    private val hexPattern = Regex("(?i)^[0-9a-f]{12,}$")
    private val qualityPattern = Regex("(?i)(2160|1440|1080|720|480|360|240|144)\\s*p?")
    private data class Stream(val url: String, val label: String, val resolution: Int?)

    fun videos(document: Document, pageUrl: String, sourceHeaders: Headers, sourceName: String): List<Video> {
        val initials = parseInitials(document)
        val model = initials?.optJSONObject("videoModel")
        val streams = linkedMapOf<String, Stream>()
        val modelSources = model?.optJSONObject("sources")
        if (modelSources != null) {
            val keys = modelSources.keys()
            while (keys.hasNext()) {
                val kind = keys.next()
                if (kind.equals("download", true)) continue
                val qualities = modelSources.optJSONObject(kind) ?: continue
                val qualityKeys = qualities.keys()
                while (qualityKeys.hasNext()) {
                    val quality = qualityKeys.next()
                    when (val item = qualities.opt(quality)) {
                        is String -> addStream(streams, item, "$kind $quality", pageUrl)
                        is JSONObject -> {
                            addStream(streams, item.optString("url"), "$kind $quality", pageUrl)
                            addStream(streams, item.optString("link"), "$kind $quality", pageUrl)
                            addStream(streams, item.optString("fallback"), "$kind $quality", pageUrl)
                        }
                    }
                }
            }
        }
        val playerSources = initials?.optJSONObject("xplayerSettings")?.optJSONObject("sources")
        playerSources?.optJSONObject("hls")?.let { hls ->
            collectHlsStreams(streams, hls, "HLS", pageUrl)
        }
        // XHamster's standard HTTP formats have been observed to return "Wrong key"
        // even after deciphering. Keep only HLS/DASH manifests from this fallback.
        val standard = playerSources?.optJSONObject("standard")
        if (standard != null) {
            val keys = standard.keys()
            while (keys.hasNext()) {
                val kind = keys.next()
                val list = standard.optJSONArray(kind) ?: continue
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val quality = item.optString("quality").ifBlank { item.optString("label") }
                    listOf("url", "fallback").forEach { field ->
                        val candidate = item.optString(field)
                        if (candidate.contains(".m3u8", true) || candidate.contains(".mpd", true)) {
                            addStream(streams, candidate, "$kind $quality $field", pageUrl)
                        }
                    }
                }
            }
        }
        // Old public layout fallback: accept only media URLs explicitly present in HTML.
        for (element in document.select("video[src],video source[src],source[src]")) {
            addStream(streams, element.absUrl("src"), "HTML video", pageUrl)
        }
        if (streams.isEmpty()) {
            val challenge = document.html().contains("cf-challenge", true) || document.text().contains("Just a moment", true)
            throw IllegalStateException(
                if (challenge) "XHamster طلب تحققاً بشرياً؛ أكمله يدوياً في المتصفح ثم أعد المحاولة."
                else "لم يكشف مشغل XHamster رابط MP4 أو HLS عاماً في بيانات الصفحة."
            )
        }
        return streams.values.sortedByDescending { it.resolution ?: 0 }.map { stream ->
            Video(
                stream.url,
                "$sourceName • ${stream.label}",
                stream.url,
                sourceHeaders.newBuilder().set("Referer", pageUrl).build(),
            )
        }
    }

    private fun collectHlsStreams(
        streams: MutableMap<String, Stream>,
        value: JSONObject,
        label: String,
        pageUrl: String,
    ) {
        for (field in listOf("url", "fallback")) {
            val candidate = value.opt(field) as? String ?: continue
            addStream(streams, candidate, "$label $field", pageUrl)
        }
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val nested = value.optJSONObject(key) ?: continue
            collectHlsStreams(streams, nested, "$label $key", pageUrl)
        }
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

    private fun addStream(streams: MutableMap<String, Stream>, raw: String?, label: String, pageUrl: String) {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return
        val decoded = decodeFormatUrl(value, pageUrl) ?: return
        val absolute = runCatching {
            val uri = URI(decoded)
            if (uri.isAbsolute) uri.toString() else URI(pageUrl).resolve(uri).toString()
        }.getOrNull() ?: return
        if (!absolute.startsWith("https://") && !absolute.startsWith("http://")) return
        if (!absolute.contains(".m3u8", true) && !absolute.contains(".mp4", true) && !absolute.contains(".mpd", true)) return
        val resolution = qualityPattern.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: qualityPattern.find(absolute)?.groupValues?.getOrNull(1)?.toIntOrNull()
        streams.putIfAbsent(absolute, Stream(absolute, label.trim().ifBlank { "Auto" }.take(65), resolution))
    }

    private fun decodeFormatUrl(raw: String, pageUrl: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (hexPattern.matches(value)) {
            return decodeHex(value)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        val match = Regex("^/([0-9a-fA-F]{12,})([/,].+)$").find(path)
        if (match != null) {
            val decoded = decodeHex(match.groupValues[1]) ?: return null
            val newPath = "/" + decoded + match.groupValues[2]
            val serialized = uri.toASCIIString()
            val pathStart = serialized.indexOf(path)
            if (pathStart < 0) return null
            val rewritten = serialized.replaceRange(pathStart, pathStart + path.length, newPath)
            val resolved = if (uri.isAbsolute) rewritten else runCatching {
                URI(pageUrl).resolve(URI(rewritten)).toString()
            }.getOrNull() ?: return null
            return resolved.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }

        if (uri.isAbsolute) {
            return value.takeIf { uri.scheme.equals("https", true) || uri.scheme.equals("http", true) }
        }
        val absolute = runCatching { URI(pageUrl).resolve(uri).toString() }.getOrNull()
        return absolute?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun decodeHex(value: String): String? {
        if (value.length % 2 != 0) return null
        val bytes = runCatching { value.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull() ?: return null
        if (bytes.size < 6) return null
        val algorithm = bytes[0].toInt() and 0xff
        if (algorithm !in 1..7) return null
        val seed = (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8) or
            ((bytes[3].toInt() and 0xff) shl 16) or ((bytes[4].toInt() and 0xff) shl 24)
        val generator = ByteGenerator(algorithm, seed)
        val output = ByteArray(bytes.size - 5)
        for (i in output.indices) output[i] = (bytes[i + 5].toInt() xor generator.nextByte()).toByte()
        return String(output, Charsets.ISO_8859_1)
    }

    private class ByteGenerator(private val algorithm: Int, seed: Int) {
        private var state = seed
        fun nextByte(): Int {
            val value = when (algorithm) {
                1 -> { state = state * 1664525 + 1013904223; state }
                2 -> {
                    var s = state
                    s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
                    state = s; s
                }
                3 -> {
                    state += 0x9e3779b9.toInt()
                    var s = state
                    s = s xor (s ushr 16); s *= 0x85ebca77.toInt()
                    s = s xor (s ushr 13); s *= 0xc2b2ae3d.toInt()
                    s xor (s ushr 16)
                }
                4 -> {
                    state += 0x6d2b79f5.toInt()
                    var s = (state shl 7) or (state ushr 25)
                    s += 0x9e3779b9.toInt(); s = s xor (s ushr 11)
                    s * 0x27d4eb2d
                }
                5 -> {
                    var s = state xor (state shl 7); s = s xor (s ushr 9); s = s xor (s shl 8)
                    state = s + 0xa5a5a5a5.toInt(); state
                }
                6 -> {
                    state = state * 0x2c9277b5.toInt() + 0xac564b05.toInt()
                    val mixed = state xor (state ushr 18)
                    mixed ushr ((state ushr 27) and 31)
                }
                7 -> {
                    state += 0x9e3779b9.toInt()
                    var s = state xor (state shl 5); s *= 0x7feb352d
                    s = s xor (s ushr 15); s * 0x846ca68b.toInt()
                }
                else -> return 0
            }
            return value and 0xff
        }
    }
}
