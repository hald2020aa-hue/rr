#!/usr/bin/env python3
"""Patch pinned Aniyomi source to offer Chrome Custom Tabs and WebView diagnostics.

Executed by aniyomi-custom/apply_customizations.py after its existing changes.
Intentionally does NOT attempt to export Chrome cookies into extension requests.
"""
from pathlib import Path


def apply_chrome_tabs_patch(root: Path, replace_once):
    """Apply deterministic, single-anchor changes to the pinned Aniyomi tree."""
    replace_once(
        "app/build.gradle.kts",
        '    implementation(androidx.corektx)\n',
        '    implementation(androidx.corektx)\n'
        '    implementation("androidx.browser:browser:1.10.0") // Chrome Custom Tabs\n',
    )

    # Both WebView entry points must provide the Chrome Custom Tab callback.
    replace_once(
        "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewScreen.kt",
        'import eu.kanade.presentation.webview.WebViewScreenContent\n',
        'import eu.kanade.presentation.webview.WebViewScreenContent\n'
        'import eu.kanade.tachiyomi.util.system.openInChromeCustomTab\n',
    )
    replace_once(
        "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewScreen.kt",
        '            onOpenInBrowser = { screenModel.openInBrowser(context, it) },\n',
        '            onOpenInBrowser = { screenModel.openInBrowser(context, it) },\n'
        '            onOpenInChromeTab = { context.openInChromeCustomTab(it) },\n',
    )
    replace_once(
        "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt",
        'import eu.kanade.tachiyomi.util.system.WebViewUtil\n',
        'import eu.kanade.tachiyomi.util.system.WebViewUtil\n'
        'import eu.kanade.tachiyomi.util.system.openInChromeCustomTab\n',
    )
    replace_once(
        "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt",
        '                onOpenInBrowser = this::openInBrowser,\n',
        '                onOpenInBrowser = this::openInBrowser,\n'
        '                onOpenInChromeTab = { openInChromeCustomTab(it) },\n',
    )

    ui = "app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt"
    replace_once(
        ui,
        'import android.webkit.WebResourceRequest\n',
        'import android.webkit.WebResourceRequest\n'
        'import android.webkit.WebResourceError\n'
        'import android.webkit.WebResourceResponse\n',
    )
    replace_once(
        ui,
        'import androidx.compose.material3.Surface\n',
        'import androidx.compose.material3.Surface\n'
        'import androidx.compose.material3.Text\n',
    )
    replace_once(
        ui,
        '    onOpenInBrowser: (String) -> Unit,\n',
        '    onOpenInBrowser: (String) -> Unit,\n'
        '    onOpenInChromeTab: (String) -> Unit,\n',
    )
    replace_once(
        ui,
        '    var showCloudflareHelp by remember { mutableStateOf(false) }\n',
        '    var showCloudflareHelp by remember { mutableStateOf(false) }\n'
        '    var webViewConnectionError by remember { mutableStateOf<String?>(null) }\n',
    )
    replace_once(
        ui,
        '                super.onPageStarted(view, url, favicon)\n',
        '                super.onPageStarted(view, url, favicon)\n'
        '                webViewConnectionError = null\n',
    )
    # Must be inserted before navigation handling, with one deterministic anchor.
    replace_once(
        ui,
        '''            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {''',
        '''            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) {
                    webViewConnectionError = "HTTP ${errorResponse.statusCode}: تعذّر فتح الصفحة داخل WebView. جرّب فتحها عبر Chrome."
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    webViewConnectionError = "خطأ اتصال ${error?.errorCode}: ${error?.description}"
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {''',
    )
    replace_once(
        ui,
        '''                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_in_browser),
                                        onClick = { onOpenInBrowser(currentUrl) },
                                    ),''',
        '''                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_in_browser),
                                        onClick = { onOpenInBrowser(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = "فتح عبر Chrome (نافذة داخل التطبيق)",
                                        onClick = { onOpenInChromeTab(currentUrl) },
                                    ),''',
    )
    replace_once(
        ui,
        '''                    if (showCloudflareHelp) {
                        Surface(''',
        '''                    webViewConnectionError?.let { message ->
                        Surface(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (showCloudflareHelp) {
                        Surface(''',
    )

    # New helper is placed into generated upstream app sources, not the rr repo.
    helper = root / "app/src/main/java/eu/kanade/tachiyomi/util/system/ChromeCustomTabs.kt"
    if helper.exists():
        raise RuntimeError(f"Refusing to overwrite upstream helper: {helper}")
    helper.parent.mkdir(parents=True, exist_ok=True)
    helper.write_text('''package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens a site using the browser's own cookies; this does not share cookies with OkHttp. */
fun Context.openInChromeCustomTab(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return

    fun newCustomTab() = CustomTabsIntent.Builder().setShowTitle(true).build().apply {
        if (this@openInChromeCustomTab !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    try {
        newCustomTab().apply { intent.setPackage("com.android.chrome") }.launchUrl(this, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            newCustomTab().launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            openInBrowser(url, forceDefaultBrowser = true)
        }
    }
}
''', encoding="utf-8")
    print(" + Qari Chrome Custom Tabs + WebView HTTP diagnostics")
