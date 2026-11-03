#!/usr/bin/env python3
"""Experimental true in-app GeckoView browser for Aniyomi's pinned source.

Unlike Chrome Custom Tabs, GeckoView is a View inside Qari's own Activity.
GeckoView has its own cookie store and cannot magically feed Aniyomi extensions.
"""
from pathlib import Path


def apply_gecko_browser_patch(root: Path, replace_once):
    replace_once(
        "settings.gradle.kts",
        '''    repositories {
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
    }
}''',
        '''    repositories {
        mavenCentral()
        google()
        maven(url = "https://maven.mozilla.org/maven2/")
        maven(url = "https://www.jitpack.io")
    }
}''',
    )

    replace_once(
        "app/build.gradle.kts",
        '    implementation("androidx.browser:browser:1.10.0") // Chrome Custom Tabs\n',
        '    implementation("androidx.browser:browser:1.10.0") // Chrome Custom Tabs\n'
        '    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:153.0.20260810162159") // GeckoView in-app browser\n',
    )

    manifest = "app/src/main/AndroidManifest.xml"
    replace_once(
        manifest,
        '''        <activity
            android:name=".ui.webview.WebViewActivity"
            android:configChanges="uiMode|orientation|screenSize"
            android:exported="false" />''',
        '''        <activity
            android:name=".ui.webview.GeckoBrowserActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
        <activity
            android:name=".ui.webview.WebViewActivity"
            android:configChanges="uiMode|orientation|screenSize"
            android:exported="false" />''',
    )

    screen = "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewScreen.kt"
    replace_once(
        screen,
        'import eu.kanade.tachiyomi.util.system.openInChromeCustomTab\n',
        'import eu.kanade.tachiyomi.util.system.openInChromeCustomTab\n'
        'import eu.kanade.tachiyomi.ui.webview.GeckoBrowserActivity.Companion.openGeckoBrowser\n',
    )
    replace_once(
        screen,
        '            onOpenInChromeTab = { context.openInChromeCustomTab(it) },\n',
        '            onOpenInChromeTab = { context.openInChromeCustomTab(it) },\n'
        '            onOpenInGecko = { context.openGeckoBrowser(it) },\n',
    )

    activity = "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt"
    replace_once(
        activity,
        'import eu.kanade.tachiyomi.util.system.openInChromeCustomTab\n',
        'import eu.kanade.tachiyomi.util.system.openInChromeCustomTab\n'
        'import eu.kanade.tachiyomi.ui.webview.GeckoBrowserActivity.Companion.openGeckoBrowser\n',
    )
    replace_once(
        activity,
        '                onOpenInChromeTab = { openInChromeCustomTab(it) },\n',
        '                onOpenInChromeTab = { openInChromeCustomTab(it) },\n'
        '                onOpenInGecko = { openGeckoBrowser(it) },\n',
    )

    ui = "app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt"
    replace_once(
        ui,
        '    onOpenInChromeTab: (String) -> Unit,\n',
        '    onOpenInChromeTab: (String) -> Unit,\n'
        '    onOpenInGecko: (String) -> Unit,\n',
    )
    replace_once(
        ui,
        '''                                    AppBar.OverflowAction(
                                        title = "فتح عبر Chrome (نافذة داخل التطبيق)",
                                        onClick = { onOpenInChromeTab(currentUrl) },
                                    ),''',
        '''                                    AppBar.OverflowAction(
                                        title = "فتح عبر Chrome (نافذة داخل التطبيق)",
                                        onClick = { onOpenInChromeTab(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = "متصفح داخل التطبيق (GeckoView) - تجريبي",
                                        onClick = { onOpenInGecko(currentUrl) },
                                    ),''',
    )

    file = root / "app/src/main/java/eu/kanade/tachiyomi/ui/webview/GeckoBrowserActivity.kt"
    if file.exists():
        raise RuntimeError(f"Refusing to overwrite upstream GeckoView activity: {file}")
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(r'''package eu.kanade.tachiyomi.ui.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * A genuine in-app browser powered by Mozilla Gecko. This is NOT Chrome Custom Tabs.
 * Its cookie store remains separate from Aniyomi's OkHttp and Chrome cookie stores.
 */
class GeckoBrowserActivity : Activity() {
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private var canGoBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(EXTRA_URL)
            ?.takeIf { Uri.parse(it).scheme in listOf("http", "https") }
            ?: run { finish(); return }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(23, 25, 34))
        }
        val title = TextView(this).apply {
            text = Uri.parse(initialUrl).host ?: "GeckoView"
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun addButton(text: String, action: () -> Unit) {
            toolbar.addView(Button(this).apply {
                this.text = text
                textSize = 12f
                setOnClickListener { action() }
            })
        }
        addButton("إغلاق") { finish() }
        toolbar.addView(title)
        addButton("تحديث") { if (::session.isInitialized) session.reload() }
        addButton("Chrome") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(initialUrl)))
        }

        geckoView = GeckoView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(geckoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(layout)

        session = GeckoSession().apply {
            // Needed for interactive web content in GeckoView.
            contentDelegate = object : GeckoSession.ContentDelegate {}
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    this@GeckoBrowserActivity.canGoBack = canGoBack
                }
            }
        }
        session.open(runtime ?: GeckoRuntime.create(applicationContext).also { runtime = it })
        geckoView.setSession(session)
        session.loadUri(initialUrl)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::session.isInitialized && canGoBack) {
            session.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) geckoView.releaseSession()
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "qari_gecko_url"
        private var runtime: GeckoRuntime? = null

        fun Context.openGeckoBrowser(url: String) {
            if (Uri.parse(url).scheme !in listOf("http", "https")) return
            val intent = Intent(this, GeckoBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
            if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}
''', encoding="utf-8")
    print(" + GeckoView genuine in-app browser (experimental; distinct Gecko cookies)")
