package local.tvwebapp

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Blocks requests to the domains of the asset blocklist.txt (built in only when BuildConfig.BLOCKLIST),
 * with their subdomains. Loaded once per process in the background. The names tell the thread rule:
 * [intercept] (background) waits for the list, [blockedNow] (UI thread) never waits.
 */
object Blocklist {
    private const val WAIT_MS = 2_000L
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val allow = BuildConfig.ALLOW_DOMAINS.toHashSet()
    /** Opened once the list is loaded, failed to load, or is off. */
    private val ready = CountDownLatch(1)
    /** Sorted FNV-1a hashes of the listed domains. A HashSet<String> took ~4 s to fill on the TV; this takes far less. */
    @Volatile private var hashes: LongArray? = null
    @Volatile private var loadStart = 0L
    private var started = false

    /** UI thread, from Activity.onCreate. Only the first call per process does anything (recreate() does not reload). */
    fun start(context: Context) {
        if (started) return
        started = true
        if (!BuildConfig.BLOCKLIST) {
            log("Blockliste: nicht geladen (aus)")
            ready.countDown()
            return
        }
        val app = context.applicationContext
        loadStart = SystemClock.elapsedRealtime()
        Thread({ load(app) }, "blocklist").start()
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest) = intercept(request)
        })
    }

    /**
     * Background thread (shouldInterceptRequest): an empty 403 for a listed host, else null (load normally).
     * Waits until the list is ready, at most until 2 s after loading began; then lets requests through unchecked.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        ready.await(loadStart + WAIT_MS - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)
        val sorted = hashes ?: return null
        if (!blocked(request.url.host, sorted)) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /** UI thread: the host of [url] is listed. Never waits; false while the list is not ready. */
    fun blockedNow(url: Uri): Boolean {
        val sorted = hashes ?: return false
        return blocked(url.host, sorted)
    }

    /** IP addresses only when listed exactly; names also through a listed parent domain. Allowed hosts never. */
    private fun blocked(host: String?, hashes: LongArray): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        if (h.isEmpty() || listed(h) { it in allow }) return false
        if (h.startsWith('[') || IPV4.matches(h)) return hashes.binarySearch(hash(h)) >= 0
        return listed(h) { hashes.binarySearch(hash(it)) >= 0 }
    }

    /** [host] or one of its parent domains is [inSet]: suffix at a '.' boundary, never a substring.
     *  Same rule as BlocklistTask.listed in app/build.gradle.kts. */
    private inline fun listed(host: String, inSet: (String) -> Boolean): Boolean {
        var h = host
        while (true) {
            if (inSet(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    /** FNV-1a 64 over the low byte of each char: the same value [load] computes from the asset's ASCII bytes. */
    private fun hash(s: String): Long {
        var x = FNV_OFFSET
        for (c in s) x = (x xor (c.code.toLong() and 0xff)) * FNV_PRIME
        return x
    }

    /** Reads the asset in the format BlocklistTask.run in app/build.gradle.kts promises (see there). */
    private fun load(context: Context) {
        try {
            val bytes = context.assets.open("blocklist.txt").use { it.readBytes() }
            var n = 0
            for (b in bytes) if (b == '\n'.code.toByte()) n++
            val all = LongArray(n + 1) // + a last line without '\n'
            var count = 0
            var x = FNV_OFFSET
            var empty = true
            for (b in bytes) {
                if (b == '\n'.code.toByte()) {
                    if (!empty) all[count++] = x
                    x = FNV_OFFSET; empty = true
                } else {
                    x = (x xor (b.toLong() and 0xff)) * FNV_PRIME; empty = false
                }
            }
            if (!empty) all[count++] = x
            val sorted = all.copyOf(count).also { it.sort() }
            hashes = sorted
            log("Blockliste: ${sorted.size} Domains in ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} ms")
        } catch (e: Throwable) { // Any failure (missing or broken asset, out of memory): run on without blocking.
            log("Blockliste: nicht geladen ($e)")
        } finally {
            ready.countDown()
        }
    }

}
