#!/usr/bin/env python3
"""Show real video durations on Aniyomi anime-source cards."""
from pathlib import Path

RESOLVER_PATH = Path(
    "app/src/main/java/eu/kanade/tachiyomi/ui/browse/anime/source/browse/"
    "AnimeVideoDurationResolver.kt"
)
BADGE_PATH = Path(
    "app/src/main/java/eu/kanade/presentation/browse/anime/components/"
    "AnimeVideoDurationBadge.kt"
)

RESOLVER = r'''package eu.kanade.tachiyomi.ui.browse.anime.source.browse

import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Request
import tachiyomi.domain.entries.anime.model.Anime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object AnimeVideoDurationResolver {
    private data class CacheEntry(val seconds: Long?, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val requests = Semaphore(3)
    private const val SUCCESS_CACHE_MS = 24L * 60 * 60 * 1000
    private const val MISS_CACHE_MS = 10L * 60 * 1000

    suspend fun resolve(source: AnimeHttpSource?, anime: Anime): Long? {
        memoDuration(anime)?.let { return it }
        source ?: return null

        val rawUrl = runCatching { source.getAnimeUrl(anime.toSAnime()) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: anime.url
        val pageUrl = safePageUrl(source, rawUrl) ?: return null
        val key = "${source.id}|$pageUrl"
        val now = System.currentTimeMillis()

        cache[key]?.let { cached ->
            if (cached.expiresAt > now) return cached.seconds
            cache.remove(key, cached)
        }

        return requests.withPermit {
            val secondCheck = cache[key]
            if (secondCheck != null && secondCheck.expiresAt > System.currentTimeMillis()) {
                return@withPermit secondCheck.seconds
            }
            val seconds = withContext(Dispatchers.IO) { fetchDuration(source, pageUrl) }
            val ttl = if (seconds != null) SUCCESS_CACHE_MS else MISS_CACHE_MS
            cache[key] = CacheEntry(seconds, System.currentTimeMillis() + ttl)
            seconds
        }
    }

    private fun memoDuration(anime: Anime): Long? {
        return listOf(
            "aniyomi.video.duration_seconds",
            "duration_seconds",
            "duration",
        ).firstNotNullOfOrNull { key ->
            val raw = runCatching {
                anime.memo[key]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            parseDuration(raw)
        }
    }

    private fun safePageUrl(source: AnimeHttpSource, rawUrl: String): String? {
        val base = runCatching { URI(source.baseUrl.trimEnd('/') + "/") }.getOrNull() ?: return null
        val target = runCatching { base.resolve(rawUrl) }.getOrNull() ?: return null
        val scheme = target.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (target.rawUserInfo != null) return null

        val sourceHost = base.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val targetHost = target.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val sameSite = targetHost == sourceHost ||
            targetHost.endsWith(".$sourceHost") ||
            sourceHost.endsWith(".$targetHost")
        if (!sameSite) return null
        return target.toASCIIString()
    }

    private fun fetchDuration(source: AnimeHttpSource, url: String): Long? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .headers(source.headers)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
            source.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseHtmlDuration(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun parseHtmlDuration(html: String): Long? {
        val jsonDuration = Regex(
            """(?is)["']duration["']\s*:\s*["']([^"']+)["']""",
        ).findAll(html).mapNotNull { parseDuration(it.groupValues[1]) }.firstOrNull()
        if (jsonDuration != null) return jsonDuration

        val numericJsonDuration = Regex(
            """(?is)["']duration["']\s*:\s*(\d{1,6})(?:\.\d+)?\s*[,}]""",
        ).findAll(html).mapNotNull { parseDuration(it.groupValues[1]) }.firstOrNull()
        if (numericJsonDuration != null) return numericJsonDuration

        val attrPattern = Regex("""([a-zA-Z_:][a-zA-Z0-9_:.-]*)\s*=\s*["']([^"']*)["']""")
        for (tag in Regex("""(?is)<meta\b[^>]*>""").findAll(html)) {
            val attrs = attrPattern.findAll(tag.value).associate {
                it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2]
            }
            val key = (attrs["property"] ?: attrs["itemprop"] ?: attrs["name"]).orEmpty()
                .lowercase(Locale.ROOT)
            if (key in setOf("og:video:duration", "video:duration", "duration", "duration_seconds")) {
                parseDuration(attrs["content"] ?: attrs["value"])?.let { return it }
            }
        }

        for (tag in Regex("""(?is)<time\b[^>]*>.*?</time>""").findAll(html)) {
            val datetime = Regex("""(?is)\bdatetime\s*=\s*["']([^"']+)["']""")
                .find(tag.value)?.groupValues?.get(1)
            parseDuration(datetime)?.let { return it }
            val text = tag.value.replace(Regex("""(?is)<[^>]+>"""), " ").trim()
            parseDuration(text)?.let { return it }
        }

        val durationAttributes = Regex(
            """(?is)\b(?:data-video-duration|data-duration|duration)\s*=\s*["']([^"']+)["']""",
        )
        for (match in durationAttributes.findAll(html)) {
            parseDuration(match.groupValues[1])?.let { return it }
        }

        val durationElements = Regex(
            """(?is)<([a-z][a-z0-9]*)\b(?=[^>]*\bclass\s*=\s*["'][^"']*(?:duration|video[-_]?time|time[-_]?duration)[^"']*["'])[^>]*>(.*?)</\1>""",
        )
        for (match in durationElements.findAll(html)) {
            val text = match.groupValues[2].replace(Regex("""(?is)<[^>]+>"""), " ").trim()
            parseDuration(text)?.let { return it }
        }
        return null
    }

    private fun parseDuration(raw: String?): Long? {
        val value = raw
            ?.replace("&nbsp;", " ", ignoreCase = true)
            ?.replace("&#160;", " ")
            ?.replace(Regex("""(?is)<[^>]+>"""), " ")
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val iso = Regex(
            """(?i)^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$""",
        ).matchEntire(value)
        if (iso != null) {
            val hours = iso.groupValues[1].toDoubleOrNull() ?: 0.0
            val minutes = iso.groupValues[2].toDoubleOrNull() ?: 0.0
            val seconds = iso.groupValues[3].toDoubleOrNull() ?: 0.0
            return (hours * 3600 + minutes * 60 + seconds).toLong().takeIf { it in 1..86400 }
        }

        val parts = value.split(':')
        if (parts.size in 2..3 && parts.all { it.toLongOrNull() != null }) {
            val numbers = parts.map { it.toLong() }
            val seconds = if (numbers.size == 2) {
                numbers[0] * 60 + numbers[1]
            } else {
                numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
            }
            return seconds.takeIf { it in 1..86400 }
        }

        val units = Regex(
            """(?i)^\s*(?:(\d+)\s*h)?\s*(?:(\d+)\s*m)?\s*(?:(\d+)\s*s)?\s*$""",
        ).matchEntire(value)
        if (units != null && units.groupValues.drop(1).any { it.isNotBlank() }) {
            val hours = units.groupValues[1].toLongOrNull() ?: 0
            val minutes = units.groupValues[2].toLongOrNull() ?: 0
            val seconds = units.groupValues[3].toLongOrNull() ?: 0
            return (hours * 3600 + minutes * 60 + seconds).takeIf { it in 1..86400 }
        }
        return value.toLongOrNull()?.takeIf { it in 1..86400 }
    }
}
'''

BADGE = r'''package eu.kanade.presentation.browse.anime.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.AnimeVideoDurationResolver
import tachiyomi.domain.entries.anime.model.Anime
import java.util.Locale

@Composable
internal fun AnimeVideoDurationBadge(source: AnimeSource?, anime: Anime) {
    val duration by produceState<Long?>(
        initialValue = null,
        source?.id,
        anime.id,
        anime.url,
    ) {
        value = AnimeVideoDurationResolver.resolve(source as? AnimeHttpSource, anime)
    }

    val seconds = duration ?: return
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    val label = if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, remainingSeconds)
    }

    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}
'''

def apply_video_duration_patch(root: Path, replace_once):
    root = Path(root)
    for rel, content in ((RESOLVER_PATH, RESOLVER), (BADGE_PATH, BADGE)):
        target = root / rel
        if target.exists():
            raise RuntimeError(f"Refusing to overwrite existing file: {target}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    content_path = "app/src/main/java/eu/kanade/presentation/browse/anime/BrowseAnimeSourceScreen.kt"
    replace_once(
        content_path,
        """            BrowseAnimeSourceComfortableGrid(
                animeList = animeList,
                columns = columns,""",
        """            BrowseAnimeSourceComfortableGrid(
                animeList = animeList,
                source = source,
                columns = columns,""",
    )
    replace_once(
        content_path,
        """            BrowseAnimeSourceList(
                animeList = animeList,
                entries = entries,""",
        """            BrowseAnimeSourceList(
                animeList = animeList,
                source = source,
                entries = entries,""",
    )
    replace_once(
        content_path,
        """            BrowseAnimeSourceCompactGrid(
                animeList = animeList,
                columns = columns,""",
        """            BrowseAnimeSourceCompactGrid(
                animeList = animeList,
                source = source,
                columns = columns,""",
    )

    compact = "app/src/main/java/eu/kanade/presentation/browse/anime/components/BrowseAnimeSourceCompactGrid.kt"
    replace_once(compact,
        "import eu.kanade.presentation.browse.InLibraryBadge\n",
        "import eu.kanade.presentation.browse.InLibraryBadge\nimport eu.kanade.tachiyomi.animesource.AnimeSource\n",
    )
    replace_once(compact,
        "fun BrowseAnimeSourceCompactGrid(\n    animeList: LazyPagingItems<StateFlow<Anime>>,\n",
        "fun BrowseAnimeSourceCompactGrid(\n    animeList: LazyPagingItems<StateFlow<Anime>>,\n    source: AnimeSource?,\n",
    )
    replace_once(compact,
        "            BrowseAnimeSourceCompactGridItem(\n                anime = anime,\n",
        "            BrowseAnimeSourceCompactGridItem(\n                anime = anime,\n                source = source,\n",
    )
    replace_once(compact,
        "private fun BrowseAnimeSourceCompactGridItem(\n    anime: Anime,\n",
        "private fun BrowseAnimeSourceCompactGridItem(\n    anime: Anime,\n    source: AnimeSource?,\n",
    )
    replace_once(compact,
        """        coverBadgeStart = {
            InLibraryBadge(enabled = anime.favorite)
        },
        onLongClick = onLongClick,""",
        """        coverBadgeStart = {
            InLibraryBadge(enabled = anime.favorite)
        },
        coverBadgeEnd = {
            AnimeVideoDurationBadge(source, anime)
        },
        onLongClick = onLongClick,""",
    )

    comfortable = "app/src/main/java/eu/kanade/presentation/browse/anime/components/BrowseAnimeSourceComfortableGrid.kt"
    replace_once(comfortable,
        "import eu.kanade.presentation.browse.InLibraryBadge\n",
        "import eu.kanade.presentation.browse.InLibraryBadge\nimport eu.kanade.tachiyomi.animesource.AnimeSource\n",
    )
    replace_once(comfortable,
        "fun BrowseAnimeSourceComfortableGrid(\n    animeList: LazyPagingItems<StateFlow<Anime>>,\n",
        "fun BrowseAnimeSourceComfortableGrid(\n    animeList: LazyPagingItems<StateFlow<Anime>>,\n    source: AnimeSource?,\n",
    )
    replace_once(comfortable,
        "            BrowseAnimeSourceComfortableGridItem(\n                anime = anime,\n",
        "            BrowseAnimeSourceComfortableGridItem(\n                anime = anime,\n                source = source,\n",
    )
    replace_once(comfortable,
        "private fun BrowseAnimeSourceComfortableGridItem(\n    anime: Anime,\n",
        "private fun BrowseAnimeSourceComfortableGridItem(\n    anime: Anime,\n    source: AnimeSource?,\n",
    )
    replace_once(comfortable,
        """        coverBadgeStart = {
            InLibraryBadge(enabled = anime.favorite)
        },
        onLongClick = onLongClick,""",
        """        coverBadgeStart = {
            InLibraryBadge(enabled = anime.favorite)
        },
        coverBadgeEnd = {
            AnimeVideoDurationBadge(source, anime)
        },
        onLongClick = onLongClick,""",
    )

    listing = "app/src/main/java/eu/kanade/presentation/browse/anime/components/BrowseAnimeSourceList.kt"
    replace_once(listing,
        "import eu.kanade.presentation.browse.InLibraryBadge\n",
        "import eu.kanade.presentation.browse.InLibraryBadge\nimport eu.kanade.tachiyomi.animesource.AnimeSource\n",
    )
    replace_once(listing,
        "fun BrowseAnimeSourceList(\n    animeList: LazyPagingItems<StateFlow<Anime>>,\n",
        "fun BrowseAnimeSourceList(\n    animeList: LazyPagingItems<StateFlow<Anime>>,\n    source: AnimeSource?,\n",
    )
    replace_once(listing,
        "                BrowseAnimeSourceListItem(\n                    anime = anime,\n",
        "                BrowseAnimeSourceListItem(\n                    anime = anime,\n                    source = source,\n",
    )
    replace_once(listing,
        "private fun BrowseAnimeSourceListItem(\n    anime: Anime,\n",
        "private fun BrowseAnimeSourceListItem(\n    anime: Anime,\n    source: AnimeSource?,\n",
    )
    replace_once(listing,
        """        badge = {
            InLibraryBadge(enabled = anime.favorite)
        },""",
        """        badge = {
            InLibraryBadge(enabled = anime.favorite)
            AnimeVideoDurationBadge(source, anime)
        },""",
    )

    print(" + Video duration badges on source cards (grid and list)")


def apply(root: Path):
    root = Path(root)
    def replace(path, before, after):
        full = root / path
        data = full.read_text(encoding="utf-8")
        if data.count(before) != 1:
            raise RuntimeError(f"{path}: expected exactly one patch anchor")
        full.write_text(data.replace(before, after, 1), encoding="utf-8")
    apply_video_duration_patch(root, replace)
