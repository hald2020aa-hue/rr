#!/usr/bin/env python3
"""Add section navigation inside the already embedded GeckoView source screen.

Does not bypass Cloudflare or claim to connect Aniyomi native source managers.
"""
from pathlib import Path

SOURCE = 'app/src/main/java/eu/kanade/presentation/webview/QariGeckoInlineBrowser.kt'
SCREEN = 'app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt'


def replace_exact(path, old, new):
    body = path.read_text(encoding='utf-8')
    found = body.count(old)
    if found != 1:
        raise RuntimeError(f'Expected one anchor in {path}; got {found}: {old[:75]!r}')
    path.write_text(body.replace(old, new, 1), encoding='utf-8')


def apply_catalog_routes_patch(root: Path) -> None:
    src = root / SOURCE
    screen = root / SCREEN
    if not src.is_file() or not screen.is_file():
        raise RuntimeError('Missing generated Gecko files; check patch execution order')
    for old,new in [
        ('import androidx.compose.foundation.layout.Column\n',
         'import androidx.compose.foundation.layout.Arrangement\n'
         'import androidx.compose.foundation.layout.Column\n'
         'import androidx.compose.foundation.layout.PaddingValues\n'),
        ('import androidx.compose.foundation.lazy.LazyColumn\n',
         'import androidx.compose.foundation.lazy.LazyColumn\n'
         'import androidx.compose.foundation.lazy.LazyRow\n'),
        ('import androidx.compose.material3.MaterialTheme\n',
         'import androidx.compose.material3.MaterialTheme\n'
         'import androidx.compose.material3.OutlinedButton\n'),
        ('import androidx.compose.ui.unit.dp\n', 'import androidx.compose.ui.unit.dp\n'),
    ]:
        if old == new:
            continue
        replace_exact(src, old, new)

    old = '''    AndroidView(
        modifier = modifier,
        factory = { c ->
            GeckoView(c).apply {
                viewRef[0] = this
                setSession(session)
                session.loadUri(initialUrl)
            }
        },
    )'''
    new = '''    AndroidView(
        modifier = modifier,
        factory = { c ->
            GeckoView(c).apply {
                viewRef[0] = this
                setSession(session)
                // The former extension opened the site's homepage instead of its anime list.
                // Start from the supplied /anime/ directory only for a homepage URL.
                val location = Uri.parse(initialUrl)
                val startUrl = if (
                    isTrustedPage(initialUrl) &&
                    (location.path.isNullOrEmpty() || location.path == "/")
                ) {
                    "https://arabshentai.com/anime/"
                } else {
                    initialUrl
                }
                session.loadUri(startUrl)
            }
        },
    )'''
    replace_exact(src, old, new)
    insert_anchor='''@Composable
fun QariGeckoSourceLinks('''
    prefix = '''// Section links come from the site owner-provided navigation paths.
// They navigate in the existing GeckoSession; NO new Activity or Chrome tab.
private val qariCatalogRoutes = listOf(
    "الأنمي" to "https://arabshentai.com/anime/",
    "تصنيفات الأنمي" to "https://arabshentai.com/%D8%AA%D8%B5%D9%86%D9%8A%D9%81%D8%A7%D8%AA-%D8%A7%D9%84%D8%A3%D9%86%D9%85%D9%8A/",
    "المانجا" to "https://arabshentai.com/manga/",
    "المانهوا" to "https://arabshentai.com/manga/?type=manhwa",
    "تصنيفات المانجا" to "https://arabshentai.com/%D8%AA%D8%B5%D9%86%D9%8A%D9%81%D8%A7%D8%AA/",
)

@Composable
fun QariGeckoCatalogTabs(
    onOpenSection: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(qariCatalogRoutes) { (label, page) ->
            OutlinedButton(onClick = { onOpenSection(page) }) {
                Text(text = label)
            }
        }
    }
}

'''
    replace_exact(src,insert_anchor,prefix+insert_anchor)

    before='''            ) {
                QariGeckoInlineBrowser(
                    initialUrl = currentUrl,'''
    after='''            ) {
                // All five categories use the same verified Gecko session.
                QariGeckoCatalogTabs(onOpenSection = qariGeckoController::navigate)
                QariGeckoInlineBrowser(
                    initialUrl = currentUrl,'''
    replace_exact(screen,before,after)

    print(' + Five site sections are reachable from within the existing source screen')
    print(' + Defaults to /anime/ instead of site homepage')
    print(' + This is a Gecko site catalogue, NOT yet the Aniyomi native library')
