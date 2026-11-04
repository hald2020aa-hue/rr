#!/usr/bin/env python3
"""Register a native anime-only source fed by normal GeckoView browsing.

Runs AFTER qari_catalog_routes_patch.py. Does not bypass Cloudflare or claim to
make protected streams playable. No manga/manhwa routes, entries, or chapters.
"""
from pathlib import Path
from shutil import copy2

APP = 'app/src/main/java/eu/kanade/tachiyomi/'
GECKO = 'app/src/main/java/eu/kanade/presentation/webview/QariGeckoInlineBrowser.kt'
SCREEN = 'app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt'
MANAGER = APP + 'source/anime/AndroidAnimeSourceManager.kt'
NATIVE = APP + 'source/anime/QariVerifiedAnimeSource.kt'
WEBEXT = 'app/src/main/assets/qari_source_bridge/content.js'


def one(path: Path, old: str, new: str):
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one anchor, found {count}: {old[:100]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


def apply_native_anime_patch(root: Path) -> None:
    assets = Path(__file__).resolve().parent / 'native_anime'
    src = assets / 'QariVerifiedAnimeSource.kt'
    script = assets / 'content.js'
    if not src.is_file() or not script.is_file():
        raise FileNotFoundError('Missing native_anime source and content script')

    # Keep a single native source in Aniyomi's normal Browse > Anime > Sources.
    # Matching the extension's name, lang and version ID preserves source IDs.
    manager = root / MANAGER
    one(
        manager,
        '''                    sourcesMapFlow.value = mutableMap
                    _isInitialized.value = true''',
        '''                    // Built-in verified anime catalog, using the GeckoView page cache.
                    // Registered after extensions: replaces the legacy ArabsHentai
                    // network scraper when both resolve to the same source ID.
                    val verifiedAnime = QariVerifiedAnimeSource(context)
                    mutableMap[verifiedAnime.id] = verifiedAnime
                    registerStubSource(StubAnimeSource.from(verifiedAnime))
                    sourcesMapFlow.value = mutableMap
                    _isInitialized.value = true''',
    )
    target = root / NATIVE
    if target.exists():
        raise RuntimeError(f'Refusing to overwrite existing source: {target}')
    target.parent.mkdir(parents=True, exist_ok=True)
    copy2(src, target)

    # The existing Gecko bridge now persists scraped visible ANIME cards and
    # optional legitimate episode links. No session or cookie export.
    gecko = root / GECKO
    one(
        gecko,
        'import org.json.JSONObject\n',
        'import org.json.JSONObject\nimport eu.kanade.tachiyomi.source.anime.QariVerifiedAnimeCatalog\n',
    )
    one(
        gecko,
        '''                if (nativeApp != "qariSource" || message !is JSONObject) return null
                if (message.optString("type") != "qari-source-links") return null
                val page = message.optString("url")
                if (!isTrustedPage(page)) return null
                val array = message.optJSONArray("links") ?: return null''',
        '''                if (nativeApp != "qariSource" || message !is JSONObject) return null
                val page = message.optString("url")
                if (!isTrustedPage(page)) return null
                when (message.optString("type")) {
                    "qari-anime-catalog" -> {
                        val items = message.optJSONArray("items") ?: return null
                        val count = QariVerifiedAnimeCatalog.mergeListing(context, page, items)
                        if (count > 0) {
                            statusChange.value("تم حفظ $count عنوان أنمي. ارجع إلى المصدر وحدّث القائمة.")
                        }
                        return null
                    }
                    "qari-anime-detail" -> {
                        val detail = message.optJSONObject("detail") ?: return null
                        QariVerifiedAnimeCatalog.mergeDetails(context, page, detail)
                        return null
                    }
                    "qari-source-links" -> Unit
                    else -> return null
                }
                val array = message.optJSONArray("links") ?: return null''',
    )
    one(
        gecko,
        'if (isTrustedPage(url)) session?.loadUri(url)',
        'if (isTrustedPage(url) && !Uri.parse(url).path.orEmpty().contains("/manga/")) session?.loadUri(url)',
    )
    one(
        gecko,
        'if (!isTrustedPage(target) || label.length < 3) continue',
        '''if (!isTrustedPage(target) || label.length < 3 ||
                            Uri.parse(target).path.orEmpty().contains("/manga/")) continue''',
    )

    # Prior patch's five buttons were only a site navigator. Restrict it to
    # Anime and Anime Categories, without changing the main app's manga feature.
    start_token = 'private val qariCatalogRoutes = listOf('
    body = gecko.read_text(encoding='utf-8')
    if body.count(start_token) != 1:
        raise RuntimeError('Expected exactly one catalog routes declaration')
    start = body.index(start_token)
    end = body.find('\n)', start)
    if end < 0:
        raise RuntimeError('Cannot find end of routes declaration')
    previous = body[start:end+2]
    for must_exist in ('"المانجا" to', '"المانهوا" to', '"تصنيفات المانجا" to'):
        if must_exist not in previous:
            raise RuntimeError(f'Route list missing {must_exist}: project may have changed')
    new_routes = '''private val qariCatalogRoutes = listOf(
    "الأنمي" to "https://arabshentai.com/anime/",
    "تصنيفات الأنمي" to "https://arabshentai.com/%D8%AA%D8%B5%D9%86%D9%8A%D9%81%D8%A7%D8%AA-%D8%A7%D9%84%D8%A3%D9%86%D9%85%D9%8A/",
)'''
    one(gecko, previous, new_routes)

    # The existing Gecko screen's status label used error red for all messages.
    # Save-confirmation text is informational, not an error.
    screen = root / SCREEN
    one(
        screen,
        'import androidx.compose.material3.Surface\n',
        'import androidx.compose.material3.Surface\nimport androidx.compose.material3.OutlinedButton\n',
    )
    one(
        screen,
        '                QariGeckoSourceLinks(geckoSourceLinks) { qariGeckoController.navigate(it) }\n'
        '                geckoStatus?.let { message ->',
        '                QariGeckoSourceLinks(geckoSourceLinks) { qariGeckoController.navigate(it) }\n'
        '                if (geckoStatus?.startsWith("تم حفظ") == true) {\n'
        '                    OutlinedButton(\n'
        '                        onClick = onNavigateUp,\n'
        '                        modifier = Modifier.padding(horizontal = 8.dp),\n'
        '                    ) {\n'
        '                        Text("عرض الأنمي في التطبيق")\n'
        '                    }\n'
        '                }\n'
        '                geckoStatus?.let { message ->',
    )
    one(
        screen,
        '''                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {''',
        '''                        color = if (message.startsWith("تم حفظ")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {''',
    )

    # Gecko's bridge runs against only pages the user can view normally.
    dest = root / WEBEXT
    if not dest.is_file():
        raise RuntimeError('Gecko web extension content.js missing')
    copy2(script, dest)

    # Confirm we did not introduce any native manga source or old five-tab UI.
    out = gecko.read_text(encoding='utf-8')
    assert '"المانجا" to' not in out and '"المانهوا" to' not in out
    assert (root / NATIVE).is_file()
    print(' + Registered an anime-only source in native Aniyomi Browse > Anime > Sources')
    print(' + Gecko verification page now caches visible anime title cards and details')
    print(' + Removed manga/manhwa navigation for ArabsHentai; no Cloudflare bypass')
