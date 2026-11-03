package eu.kanade.presentation.webview

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * Native page links are READ from the DOM of a page actually opened by the user.
 * We do not share Gecko cookies with Aniyomi's OkHttp or bypass site security.
 */
data class QariSourceLink(val label: String, val url: String, val kind: String)

class QariGeckoController {
    private var session: GeckoSession? = null

    fun attach(value: GeckoSession) { session = value }
    fun detach(value: GeckoSession) { if (session === value) session = null }
    fun goBack() { session?.goBack() }
    fun goForward() { session?.goForward() }
    fun reload() { session?.reload() }
    fun navigate(url: String) {
        if (isTrustedPage(url)) session?.loadUri(url)
    }
}

/** One GeckoRuntime per Android application process, not a runtime per Composable. */
private object QariGeckoRuntime {
    private var instance: GeckoRuntime? = null

    @Synchronized
    fun get(context: Context): GeckoRuntime = instance
        ?: GeckoRuntime.create(context.applicationContext).also { instance = it }
}

private fun isTrustedPage(url: String): Boolean {
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && (host == "arabshentai.com" || host == "www.arabshentai.com")
}

@Composable
fun QariGeckoInlineBrowser(
    initialUrl: String,
    controller: QariGeckoController,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String?) -> Unit,
    onCanGoBack: (Boolean) -> Unit,
    onCanGoForward: (Boolean) -> Unit,
    onSourceLinks: (List<QariSourceLink>) -> Unit,
    onStatus: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val urlChange = rememberUpdatedState(onUrlChange)
    val titleChange = rememberUpdatedState(onTitleChange)
    val backChange = rememberUpdatedState(onCanGoBack)
    val forwardChange = rememberUpdatedState(onCanGoForward)
    val linksChange = rememberUpdatedState(onSourceLinks)
    val statusChange = rememberUpdatedState(onStatus)

    val runtime = remember(context.applicationContext) { QariGeckoRuntime.get(context.applicationContext) }
    val session = remember(runtime) {
        GeckoSession().apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    backChange.value(canGoBack)
                }
                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    forwardChange.value(canGoForward)
                }
            }
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    titleChange.value(title)
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    urlChange.value(url)
                    linksChange.value(emptyList())
                    statusChange.value(null)
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (!success) statusChange.value("تعذّر تحميل الصفحة في GeckoView. تأكد من اتصال الـProxy.")
                }
            }
            open(runtime)
        }
    }
    // Keep the actual Android view reference, not a second Activity/window.
    val viewRef = remember(session) { arrayOfNulls<GeckoView>(1) }

    DisposableEffect(runtime, session) {
        controller.attach(session)
        val bridge = object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender,
            ): GeckoResult<Any>? {
                if (nativeApp != "qariSource" || message !is JSONObject) return null
                if (message.optString("type") != "qari-source-links") return null
                val page = message.optString("url")
                if (!isTrustedPage(page)) return null
                val array = message.optJSONArray("links") ?: return null
                val links = buildList {
                    for (i in 0 until minOf(array.length(), 60)) {
                        val item = array.optJSONObject(i) ?: continue
                        val target = item.optString("url")
                        val label = item.optString("label").trim().take(100)
                        if (!isTrustedPage(target) || label.length < 3) continue
                        val kind = item.optString("kind").takeIf { it in setOf("episode", "title", "link") } ?: "link"
                        add(QariSourceLink(label, target, kind))
                    }
                }
                linksChange.value(links.distinctBy { it.url })
                return null
            }
        }
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/qari_source_bridge/",
            "qari-source-bridge@local",
        ).accept(
            { extension ->
                if (extension != null) {
                    session.webExtensionController.setMessageDelegate(extension, bridge, "qariSource")
                } else {
                    statusChange.value("جسر قراءة الروابط غير متاح: لم يتم تحميل الإضافة.")
                }
            },
            { error ->
                statusChange.value("جسر قراءة الروابط غير متاح: ${error?.message ?: "خطأ غير معروف"}")
            },
        )
        onDispose {
            controller.detach(session)
            viewRef[0]?.releaseSession()
            viewRef[0] = null
            session.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            GeckoView(c).apply {
                viewRef[0] = this
                setSession(session)
                session.loadUri(initialUrl)
            }
        },
    )
}

@Composable
fun QariGeckoSourceLinks(
    links: List<QariSourceLink>,
    onSelect: (String) -> Unit,
) {
    if (links.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(max = 192.dp),
    ) {
        Text(
            text = "روابط الصفحة (${links.size}) — استعراض أولي، ليست حلقات مؤكدة",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        LazyColumn {
            items(links, key = { it.url }) { link ->
                Text(
                    text = when (link.kind) {
                        "episode" -> "حلقة محتملة: ${link.label}"
                        "title" -> "عنوان محتمل: ${link.label}"
                        else -> link.label
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(link.url) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
