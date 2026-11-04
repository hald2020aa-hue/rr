#!/usr/bin/env python3
"""Wire foreground, resumable archive + episode sync into inline GeckoView.

Run after qari_native_anime_patch. Preserve the existing native source ID,
verified browser flow, extension trust checks and single-arm64 output.
"""
from pathlib import Path
from shutil import copy2

GECKO = 'app/src/main/java/eu/kanade/presentation/webview/QariGeckoInlineBrowser.kt'
SYNC = 'app/src/main/java/eu/kanade/presentation/webview/QariAnimeSync.kt'


def one(path: Path, old: str, new: str):
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one anchor; found {count}: {old[:120]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


def apply_auto_sync_patch(root: Path) -> None:
    path = root / GECKO
    source = Path(__file__).resolve().parent / 'auto_sync' / 'QariAnimeSync.kt'
    dest = root / SYNC
    if not path.exists() or not source.exists() or dest.exists():
        raise RuntimeError('Auto-sync dependencies missing or already patched')
    dest.parent.mkdir(parents=True, exist_ok=True)
    copy2(source, dest)

    one(path,
        'import androidx.compose.foundation.layout.Column\n',
        'import androidx.compose.foundation.layout.Column\n'
        'import androidx.compose.foundation.layout.Row\n')
    one(path,
        '''    val viewRef = remember(session) { arrayOfNulls<GeckoView>(1) }
''',
        '''    val viewRef = remember(session) { arrayOfNulls<GeckoView>(1) }
    val autoSync = remember(context.applicationContext, controller) {
        QariAnimeSync(context.applicationContext, controller)
    }
''')
    one(path,
        '''                    statusChange.value(null)
                }
                override fun onPageStop''',
        '''                    if (!autoSync.isRunning) statusChange.value(null)
                }
                override fun onPageStop''')
    # This delegate is inside session=remember(runtime) and would capture a
    # Composable state declared afterwards. Make it refer to a stable ref set
    # before session construction instead.
    one(path,
        '''    val runtime = remember(context.applicationContext) { QariGeckoRuntime.get(context.applicationContext) }
    val session = remember(runtime) {''',
        '''    val runtime = remember(context.applicationContext) { QariGeckoRuntime.get(context.applicationContext) }
    val syncRef = remember { arrayOfNulls<QariAnimeSync>(1) }
    val session = remember(runtime) {''')
    one(path,
        'if (!autoSync.isRunning) statusChange.value(null)',
        'if (syncRef[0]?.isRunning != true) statusChange.value(null)')
    one(path,
        '''    val autoSync = remember(context.applicationContext, controller) {
        QariAnimeSync(context.applicationContext, controller)
    }
''',
        '''    val autoSync = remember(context.applicationContext, controller) {
        QariAnimeSync(context.applicationContext, controller)
    }
    syncRef[0] = autoSync
''')
    one(path,
        '''        controller.attach(session)
        val bridge = object : WebExtension.MessageDelegate {''',
        '''        controller.attach(session)
        statusChange.value(QariVerifiedAnimeCatalog.progress(context))
        val bridge = object : WebExtension.MessageDelegate {''')
    one(path,
        '''                        if (count > 0) {
                            statusChange.value("تم حفظ $count عنوان أنمي. ارجع إلى المصدر وحدّث القائمة.")
                        }
                        return null''',
        '''                        if (count > 0) {
                            if (autoSync.isRunning) {
                                autoSync.onArchive(page, message) { statusChange.value(it) }
                            } else {
                                statusChange.value(
                                    "تم حفظ $count عنوان أنمي. " +
                                        QariVerifiedAnimeCatalog.progress(context),
                                )
                            }
                        }
                        return null''')
    one(path,
        '''                        QariVerifiedAnimeCatalog.mergeDetails(context, page, detail)
                        return null''',
        '''                        QariVerifiedAnimeCatalog.mergeDetails(context, page, detail)
                        if (autoSync.isRunning) {
                            autoSync.onDetails(page) { statusChange.value(it) }
                        }
                        return null''')
    one(path,
        '''        onDispose {
            controller.detach(session)''',
        '''        onDispose {
            autoSync.pause()
            syncRef[0] = null
            controller.detach(session)''')

    # The webview is still the same GeckoView in the same Compose screen.
    # Buttons initiate a foreground crawl. The native catalog reads from disk
    # without ever needing the browser to be reopened.
    one(path,
        '''    AndroidView(
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
    )''',
        '''    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { autoSync.start { statusChange.value(it) } },
                modifier = Modifier.weight(1f),
            ) {
                Text("مزامنة جميع الصفحات والحلقات")
            }
            OutlinedButton(
                onClick = { autoSync.pause { statusChange.value(it) } },
            ) {
                Text("إيقاف")
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { c ->
                GeckoView(c).apply {
                    viewRef[0] = this
                    setSession(session)
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
        )
    }''')
    # Keep generated Kotlin within the ktlint 120-character constraint.
    # The prior source carries one long URL literal, which Spotless may reject.
    one(path,
        '    "تصنيفات الأنمي" to "https://arabshentai.com/%D8%AA%D8%B5%D9%86%D9%8A%D9%81%D8%A7%D8%AA-%D8%A7%D9%84%D8%A3%D9%86%D9%85%D9%8A/",',
        '    "تصنيفات الأنمي" to (\n'
        '        "https://arabshentai.com/%D8%AA%D8%B5%D9%86%D9%8A%D9%81%D8%A7%D8%AA-" +\n'
        '            "%D8%A7%D9%84%D8%A3%D9%86%D9%85%D9%8A/"\n'
        '    ),',
    )

    manifest = root / 'app/src/main/assets/qari_source_bridge/manifest.json'
    one(manifest, '"version": "1.0.0"', '"version": "1.1.0"')
    print(' + Qari archive pagination and episode indexing toolbar (foreground)')
    print(' + No changes to app variants; single ARM64 remains in workflow')
