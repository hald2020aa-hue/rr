package eu.kanade.presentation.webview

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.source.anime.QariVerifiedAnimeCatalog
import org.json.JSONObject

/**
 * Foreground, resumable anime-page indexer. It navigates one authenticated
 * GeckoSession sequentially and never copies challenge cookies to OkHttp.
 * The persistent catalog and its checkpoint are owned by the native source.
 */
class QariAnimeSync(
    private val context: Context,
    private val browser: QariGeckoController,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var running = false
    private var stage = "archive"
    private var expectedUrl: String? = null
    private var lastCompleted: String? = null

    val isRunning: Boolean get() = running

    fun start(notify: (String) -> Unit) {
        if (running) return
        val target = QariVerifiedAnimeCatalog.resumeTarget(context)
        if (target == null) {
            QariVerifiedAnimeCatalog.markDetailsFinished(context)
            notify(QariVerifiedAnimeCatalog.progress(context))
            return
        }
        stage = target.first
        running = true
        lastCompleted = null
        visit(target.second, notify)
    }

    fun pause(notify: ((String) -> Unit)? = null) {
        running = false
        pending?.let(handler::removeCallbacks)
        pending = null
        expectedUrl = null
        notify?.invoke("متوقفة. المحفوظات باقية؛ استأنف من حيث توقفت. " +
            QariVerifiedAnimeCatalog.progress(context))
    }

    private fun canonical(url: String): String {
        val uri = Uri.parse(url)
        return uri.buildUpon().authority("arabshentai.com").fragment(null)
            .build().toString().trimEnd('/')
    }

    private fun visit(url: String, notify: (String) -> Unit) {
        if (!running) return
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (uri.scheme != "https" || host != "arabshentai.com") {
            pause()
            notify("توقفت المزامنة: رابط خارج نطاق المصدر.")
            return
        }
        expectedUrl = canonical(url)
        notify("جاري مزامنة $stage: ${uri.path.orEmpty()} — " +
            QariVerifiedAnimeCatalog.progress(context))
        browser.navigate(url)
    }

    private fun delayedVisit(url: String, notify: (String) -> Unit) {
        if (!running) return
        pending?.let(handler::removeCallbacks)
        val task = Runnable {
            pending = null
            visit(url, notify)
        }
        pending = task
        // One page at a time; avoid a request storm on protected websites.
        handler.postDelayed(task, 1800L)
    }

    fun onArchive(pageUrl: String, data: JSONObject, notify: (String) -> Unit) {
        if (!running || stage != "archive") return
        val seenUrl = canonical(pageUrl)
        if (expectedUrl != seenUrl || lastCompleted == seenUrl) return
        if ((data.optJSONArray("items")?.length() ?: 0) == 0) return
        lastCompleted = seenUrl
        val next = QariVerifiedAnimeCatalog.markArchivePage(
            context,
            pageUrl,
            data.optString("nextUrl").takeIf(String::isNotBlank),
            data.optInt("total", 0),
        )
        if (next != null) {
            delayedVisit(next, notify)
            return
        }
        val total = data.optInt("total", 0)
        val current = data.optInt("current", 1)
        if (total > current) {
            pause()
            notify("لم أجد رابط الصفحة التالية رغم وجود $total صفحة. " +
                "العناوين محفوظة؛ افتح الصفحة التالية يدويًا ثم استأنف. " +
                QariVerifiedAnimeCatalog.progress(context))
            return
        }
        stage = "details"
        lastCompleted = null
        val nextDetail = QariVerifiedAnimeCatalog.nextPendingDetail(context)
        if (nextDetail != null) {
            delayedVisit(nextDetail, notify)
        } else {
            finish(notify)
        }
    }

    fun onDetails(pageUrl: String, notify: (String) -> Unit) {
        if (!running || stage != "details") return
        val done = canonical(pageUrl)
        if (done != expectedUrl || done == lastCompleted) return
        lastCompleted = done
        val next = QariVerifiedAnimeCatalog.nextPendingDetail(context)
        if (next == null) {
            finish(notify)
        } else if (canonical(next) == done) {
            pause()
            notify("تعذّر حفظ تفاصيل هذا العنوان. " +
                QariVerifiedAnimeCatalog.progress(context))
        } else {
            delayedVisit(next, notify)
        }
    }

    private fun finish(notify: (String) -> Unit) {
        QariVerifiedAnimeCatalog.markDetailsFinished(context)
        pause()
        notify("اكتملت المزامنة للصفحات التي أمكن الوصول إليها. " +
            QariVerifiedAnimeCatalog.progress(context))
    }
}
