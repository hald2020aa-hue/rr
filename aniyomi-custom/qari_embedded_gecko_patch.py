#!/usr/bin/env python3
"""Move GeckoView from a separate Activity into Aniyomi's actual WebView source screen.

This runs AFTER existing qari_gecko_browser_patch.py in the pinned Aniyomi tree.
It installs a read-only bridge for links visible on arabshentai.com after user navigation.
No Cloudflare bypass, OkHttp cookie injection, or episode-download promises.
"""
from pathlib import Path
from shutil import copy2


SOURCE = "app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt"
SCREEN = "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewScreen.kt"
ACTIVITY = "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt"
MANIFEST = "app/src/main/AndroidManifest.xml"


def apply_embedded_gecko_patch(root: Path, replace_once) -> None:
    # The previous experiment added GeckoBrowserActivity, which is an independent
    # window, not a source engine. Do not register or compile it any longer.
    replace_once(
        MANIFEST,
        '''        <activity
            android:name=".ui.webview.GeckoBrowserActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
''',
        "",
    )
    for path, callback in (
        (SCREEN, '            onOpenInGecko = { context.openGeckoBrowser(it) },\n'),
        (ACTIVITY, '                onOpenInGecko = { openGeckoBrowser(it) },\n'),
    ):
        replace_once(
            path,
            'import eu.kanade.tachiyomi.ui.webview.GeckoBrowserActivity.Companion.openGeckoBrowser\n',
            '',
        )
        replace_once(path, callback, '')

    generated_activity = root / 'app/src/main/java/eu/kanade/tachiyomi/ui/webview/GeckoBrowserActivity.kt'
    if not generated_activity.is_file():
        raise RuntimeError('Old GeckoBrowserActivity not found; wrong build source / order')
    generated_activity.unlink()

    replace_once(SOURCE, 'import android.graphics.Bitmap\n', 'import android.graphics.Bitmap\nimport android.net.Uri\n')
    # Modifier.weight is resolved in the ColumnScope of the existing screen.
    replace_once(
        SOURCE,
        '    onOpenInGecko: (String) -> Unit,\n',
        '',
    )
    replace_once(
        SOURCE,
        '''    var currentUrl by remember { mutableStateOf(url) }
''',
        '''    var currentUrl by remember { mutableStateOf(url) }
    // Protected source uses Gecko INSIDE this screen, not GeckoBrowserActivity.
    var embeddedGeckoEnabled by remember(url) {
        val host = Uri.parse(url).host?.lowercase()
        mutableStateOf(host == "arabshentai.com" || host == "www.arabshentai.com")
    }
    val qariGeckoController = remember { QariGeckoController() }
    var geckoCanGoBack by remember { mutableStateOf(false) }
    var geckoCanGoForward by remember { mutableStateOf(false) }
    var geckoTitle by remember { mutableStateOf<String?>(null) }
    var geckoStatus by remember { mutableStateOf<String?>(null) }
    var geckoSourceLinks by remember { mutableStateOf<List<QariSourceLink>>(emptyList()) }
''',
    )
    replace_once(
        SOURCE,
        '                        title = state.pageTitle ?: initialTitle,\n',
        '                        title = if (embeddedGeckoEnabled) geckoTitle ?: initialTitle else state.pageTitle ?: initialTitle,\n',
    )
    replace_once(
        SOURCE,
        '''                                            if (navigator.canGoBack) {
                                                navigator.navigateBack()
                                            }
''',
        '''                                            if (embeddedGeckoEnabled) {
                                                qariGeckoController.goBack()
                                            } else if (navigator.canGoBack) {
                                                navigator.navigateBack()
                                            }
''',
    )
    replace_once(SOURCE, '                                        enabled = navigator.canGoBack,\n',
                 '                                        enabled = if (embeddedGeckoEnabled) geckoCanGoBack else navigator.canGoBack,\n')
    replace_once(
        SOURCE,
        '''                                            if (navigator.canGoForward) {
                                                navigator.navigateForward()
                                            }
''',
        '''                                            if (embeddedGeckoEnabled) {
                                                qariGeckoController.goForward()
                                            } else if (navigator.canGoForward) {
                                                navigator.navigateForward()
                                            }
''',
    )
    replace_once(SOURCE, '                                        enabled = navigator.canGoForward,\n',
                 '                                        enabled = if (embeddedGeckoEnabled) geckoCanGoForward else navigator.canGoForward,\n')
    replace_once(SOURCE, '                                        onClick = { navigator.reload() },\n',
                 '                                        onClick = { if (embeddedGeckoEnabled) qariGeckoController.reload() else navigator.reload() },\n')
    replace_once(
        SOURCE,
        '''                                    AppBar.OverflowAction(
                                        title = "متصفح داخل التطبيق (GeckoView) - تجريبي",
                                        onClick = { onOpenInGecko(currentUrl) },
                                    ),''',
        '''                                    AppBar.OverflowAction(
                                        title = if (embeddedGeckoEnabled) "العودة إلى WebView" else "المصدر داخل التطبيق (GeckoView)",
                                        onClick = {
                                            embeddedGeckoEnabled = !embeddedGeckoEnabled
                                            geckoSourceLinks = emptyList()
                                            geckoStatus = null
                                        },
                                    ),''',
    )
    replace_once(SOURCE, '                    if (showCloudflareHelp) {\n',
                 '                    if (showCloudflareHelp && !embeddedGeckoEnabled) {\n')

    # The old WebView remains available for other sources. For this one source,
    # Gecko is mounted in the very same Compose screen and shares its top bar.
    replace_once(
        SOURCE,
        '''    ) { contentPadding ->
        WebView(
            state = state,''',
        '''    ) { contentPadding ->
        if (embeddedGeckoEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                QariGeckoInlineBrowser(
                    initialUrl = currentUrl,
                    controller = qariGeckoController,
                    onUrlChange = {
                        currentUrl = it
                        onUrlChange(it)
                    },
                    onTitleChange = { geckoTitle = it },
                    onCanGoBack = { geckoCanGoBack = it },
                    onCanGoForward = { geckoCanGoForward = it },
                    onSourceLinks = { geckoSourceLinks = it },
                    onStatus = { geckoStatus = it },
                    modifier = Modifier.weight(1f),
                )
                QariGeckoSourceLinks(geckoSourceLinks) { qariGeckoController.navigate(it) }
                geckoStatus?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
        WebView(
            state = state,''',
    )
    replace_once(
        SOURCE,
        '''            client = webClient,
        )
    }
}
''',
        '''            client = webClient,
        )
        }
    }
}
''',
    )

    # The old toolbar shows a WebView initialization progress bar even in Gecko
    # mode. Hide it while Gecko is running.
    replace_once(
        SOURCE,
        '                when (val loadingState = state.loadingState) {',
        '                if (!embeddedGeckoEnabled) when (val loadingState = state.loadingState) {',
    )

    assets = Path(__file__).resolve().parent / 'embedded_gecko'
    content = assets / 'QariGeckoInlineBrowser.kt'
    if not content.is_file():
        raise RuntimeError('Missing QariGeckoInlineBrowser.kt in rr patch files')
    targets = [
        (content, root / 'app/src/main/java/eu/kanade/presentation/webview/QariGeckoInlineBrowser.kt'),
        (assets / 'manifest.json', root / 'app/src/main/assets/qari_source_bridge/manifest.json'),
        (assets / 'content.js', root / 'app/src/main/assets/qari_source_bridge/content.js'),
    ]
    for src, dst in targets:
        if not src.is_file() or dst.exists():
            raise RuntimeError(f'Unexpected source or destination for {src} -> {dst}')
        dst.parent.mkdir(parents=True, exist_ok=True)
        copy2(src, dst)
    print(' + GeckoView embedded in source screen + read-only DOM links bridge')
    print(' + No GeckoBrowserActivity; no automatic cookie transfer or Cloudflare bypass')
