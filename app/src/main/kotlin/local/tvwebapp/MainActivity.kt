package local.tvwebapp

import android.app.Activity
import android.media.AudioManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/**
 * One line in `adb logcat -s tvwebapp`: the blocklist status and the trail of keys, page changes and popups with
 * the app's decision on each, so a user's report ("blocked after a click") can be replayed from the log.
 * URLs are cut to keep lines readable.
 */
internal fun log(line: String) {
    Log.i("tvwebapp", line)
}

private fun short(url: String?) = url?.let { if (it.length > 200) it.take(200) + "…" else it } ?: "-"

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var cursor: CursorView
    private lateinit var loading: LoadingView
    /** [FOCUS_RING] in this start's theme colours. */
    private lateinit var focusRing: String
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    /** True once the WebView is destroyed (renderer crash or onDestroy); lifecycle calls must skip it. */
    private var webViewGone = false
    /** Focus mode: what the page's focus is on, outside fullscreen (reported by the page, see FOCUS_RING). */
    private var focused = Target.NONE
    /** The player the page shows in fullscreen (reported by the page). Only valid while [fullscreenView] is set. */
    private var fullscreenPlayer = Target.NONE
    /**
     * elapsedRealtime of the last page change the user started (a click, or a popup from a click), 0 after any
     * key press since. Within [FORWARD_MS] of it the new page may forward to another site on its own.
     */
    private var userNavAt = 0L

    /**
     * Page elements the keys treat specially. FRAME: a player embedded from another site (iframe), out of the
     * app's reach; VIDEO: a <video> in the page, or on a player page (see FOCUS_RING) the player element
     * around its video; TEXT: a text field, whose keyboard opens only on OK.
     */
    private enum class Target {
        NONE, FRAME, VIDEO, TEXT;
        val player get() = this == FRAME || this == VIDEO
        companion object { fun of(i: Int) = entries.getOrElse(i) { NONE } }
    }

    /** The decision on a page change: [text] goes to the log, and as a Toast when [toast]. */
    private enum class Nav(val blocked: Boolean, val toast: Boolean, val text: String) {
        ALLOW(false, false, "erlaubt"),
        FORWARD(false, false, "erlaubt (Weiterleitung direkt nach Klick)"),
        SCHEME(true, false, "Schema ignoriert"),
        AD(true, true, "Werbung blockiert"),
        REDIRECT(true, true, "Weiterleitung blockiert"),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Blocklist.start(this)
        // The build is not debuggable (speed), so page inspection from the Mac (chrome://inspect) is enabled here.
        WebView.setWebContentsDebuggingEnabled(true)

        root = FrameLayout(this)
        webView = createWebView()
        root.addView(webView, match())
        val theme = Theme.of(this)
        focusRing = FOCUS_RING.replace("@ACCENT@", Theme.css(theme.accent)).replace("@OUTLINE@", Theme.css(theme.outline))
        loading = LoadingView(this, theme) { cursor.showNow() } // the pointer appears with the page, not over the splash
        root.addView(loading, match())
        cursor = CursorView(this, BuildConfig.CURSOR, theme, { fullscreenView ?: webView }, ::modeChanged, ::focusOk)
        cursor.hideNow()
        root.addView(cursor, match())
        webView.addJavascriptInterface(PageBridge(), "tvwebapp")
        setContentView(root)
        hideSystemBars()
        webView.requestFocus()
        webView.loadUrl(BuildConfig.START_URL)
    }

    private fun createWebView() = WebView(this).apply wv@{
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Every new window reaches onCreateWindow, which decides by user gesture.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Off by default (WebView default: never). Lets an https page load http content, scripts included,
            // which someone on the network could tamper with; only for sites that need it.
            if (BuildConfig.ALLOW_INSECURE_CONTENT) mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (BuildConfig.DESKTOP_MODE) {
                userAgentString = desktopUserAgent(userAgentString)
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            // zoom sets the page's viewport width (see preparePage); the WebView honours it only like this.
            if (BuildConfig.ZOOM != 100) useWideViewPort = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@wv, true)
        }
        webViewClient = Client()
        webChromeClient = ChromeClient()
        setDownloadListener { url, _, _, mimeType, _ ->
            if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) playMedia(url)
            else Toast.makeText(this@MainActivity, "Download nicht unterstützt", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- keys ---------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) userNavAt = 0L
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { log("Taste: Zurück"); handleBack() }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_UP) {
            log("Taste: OK (${if (cursor.pointerMode) "Zeiger" else "Fokus auf $focused"}${if (fullscreenView != null) ", Vollbild" else ""})")
        }
        if (KeyEvent.isMediaSessionKey(event.keyCode)) return super.dispatchKeyEvent(event)
        if (cursor.onKey(event)) return true
        if (!cursor.pointerMode && event.keyCode in ARROWS && playerArrow(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Focus mode, arrow key on a video player; returns false when no player is involved.
     * Inline the arrows leave the player: players take them as hotkeys and never let the focus out.
     * In fullscreen ▲/▼ set the TV volume and ◀/▶ seek (embedded players: by their own hotkeys).
     */
    private fun playerArrow(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (fullscreenView != null) {
            when {
                fullscreenPlayer == Target.NONE -> return false
                e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                    val dir = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                }
                fullscreenPlayer == Target.FRAME -> return false
                else -> if (down) page("seek(${if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -SEEK_S else SEEK_S})")
            }
            return true
        }
        if (!focused.player) return false
        if (down) {
            val key = KeyEvent(e)
            webView.evaluateJavascript("!!window.__tvw&&__tvw.standIn(${e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN},${e.keyCode == KeyEvent.KEYCODE_DPAD_UP})") {
                // The focus is now on a stand-in over the player: the page's own navigation goes from there.
                if (it == "true" && !webViewGone) {
                    webView.dispatchKeyEvent(key)
                    webView.dispatchKeyEvent(KeyEvent.changeAction(key, KeyEvent.ACTION_UP))
                }
            }
        }
        return true
    }

    /**
     * Focus mode, OK released. On a player: play it in fullscreen. In video fullscreen: play/pause.
     * On a text field: open its keyboard (no Enter, which would submit a form).
     */
    private fun focusOk(up: KeyEvent) {
        if (fullscreenView != null && fullscreenPlayer == Target.VIDEO) { page("toggle()"); return }
        if (fullscreenView == null && focused == Target.FRAME) {
            // Embedded players start on a tap; Enter only reaches the hoster page's body. The tap also gives
            // the player the focus, so its keys work in fullscreen right away.
            webView.evaluateJavascript("window.__tvw&&__tvw.focusCenter()") { json ->
                val xy = json.trim('[', ']').split(',').mapNotNull { it.toFloatOrNull() }
                if (xy.size != 2 || webViewGone) return@evaluateJavascript
                cursor.tapAt(xy[0], xy[1])
                // Once the tap has reached the page: fullscreen needs the user activation it brings.
                webView.postDelayed({ page("enterPlayer()") }, 300)
            }
            return
        }
        if (fullscreenView == null && focused == Target.TEXT) {
            webView.evaluateJavascript("window.__tvw&&__tvw.allowKeyboard()") {
                if (!webViewGone) getSystemService(InputMethodManager::class.java).showSoftInput(webView, 0)
            }
            return
        }
        val v = fullscreenView ?: webView
        v.dispatchKeyEvent(KeyEvent.changeAction(up, KeyEvent.ACTION_DOWN))
        v.dispatchKeyEvent(up)
        // Right after a real key press: the page may enter fullscreen only within a user gesture.
        if (fullscreenView == null && focused.player) page("enterPlayer()")
    }

    /** The user's last input went into a player embedded from another site (any mode). */
    private fun inEmbeddedPlayer() =
        if (fullscreenView != null) fullscreenPlayer == Target.FRAME else focused == Target.FRAME

    /** Calls a function of window.__tvw (see FOCUS_RING) in the main frame. */
    private fun page(call: String) {
        if (!webViewGone) webView.evaluateJavascript("window.__tvw&&__tvw.$call", null)
    }

    private fun handleBack() {
        when {
            fullscreenView != null -> exitFullscreen(notifyPage = true)
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    // ---- video fullscreen -----------------------------------------------------

    private fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) { callback.onCustomViewHidden(); return }
        fullscreenView = view
        fullscreenCallback = callback
        root.addView(view, root.indexOfChild(cursor), match()) // above the WebView and loading bar, below the cursor
        webView.visibility = View.INVISIBLE
        view.requestFocus()
        cursor.hideNow()
        hideSystemBars()
    }

    /**
     * Leaves video fullscreen. [notifyPage]: true when the app ends it (BACK), so the page must be told;
     * false when the page ended it itself (onHideCustomView). Safe to call again while already left.
     */
    private fun exitFullscreen(notifyPage: Boolean) {
        val v = fullscreenView ?: return
        val callback = fullscreenCallback
        root.removeView(v)
        fullscreenView = null
        fullscreenCallback = null
        webView.visibility = View.VISIBLE
        webView.requestFocus()
        // After clearing state: the page may answer with onHideCustomView, which then returns early.
        if (notifyPage) callback?.onCustomViewHidden()
    }

    /** Plays a media URL that the WebView offered as a download. Joins history, so BACK works. */
    private fun playMedia(url: String) {
        val src = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
            |<style>html,body{margin:0;height:100%;background:#000}video{width:100%;height:100%}</style></head>
            |<body><video src="$src" autoplay controls></video></body></html>""".trimMargin()
        webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    // ---- lifecycle -------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        if (webViewGone) return
        webView.evaluateJavascript(PAUSE_MEDIA, null)
        webView.onPause()
        webView.pauseTimers()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        if (webViewGone) return
        webView.resumeTimers()
        webView.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        if (!webViewGone) {
            webViewGone = true
            root.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- clients ---------------------------------------------------------------

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val nav = navigation(view, request)
            if (request.isForMainFrame) {
                log("Navigation: ${short(view.url)} -> ${short(request.url.toString())} " +
                    "(Klick=${request.hasGesture()}, Server-Umleitung=${request.isRedirect}): ${nav.text}")
                if (request.hasGesture() && !nav.blocked) userNavAt = SystemClock.elapsedRealtime()
            }
            if (nav.toast) Toast.makeText(this@MainActivity, nav.text, Toast.LENGTH_SHORT).show()
            return nav.blocked
        }

        private fun navigation(view: WebView, request: WebResourceRequest): Nav {
            if (request.url.scheme?.lowercase() !in LOADABLE_SCHEMES) return Nav.SCHEME // intent:, market:, mailto: …
            if (request.isForMainFrame && Blocklist.blockedNow(request.url)) return Nav.AD
            // Ad scripts send the whole page to another site without any key press. Server redirects and
            // navigations the user started (link, form) stay allowed, and so does a page the user just opened
            // forwarding on its own (hosters move: voe.sx/x forwards by script to its current domain).
            val current = view.url?.let { Uri.parse(it).host }
            if (request.isForMainFrame && !request.hasGesture() && !request.isRedirect &&
                current != null && site(request.url.host) != site(current)
            ) {
                return if (SystemClock.elapsedRealtime() - userNavAt < FORWARD_MS) Nav.FORWARD else Nav.REDIRECT
            }
            return Nav.ALLOW
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = Blocklist.intercept(request)

        // Both: commit is early (less zoom flicker), finished catches pages that replace <html> late.
        override fun onPageCommitVisible(view: WebView, url: String) {
            log("Seite: ${short(url)}")
            // A new document: its script starts at "nothing focused" and reports only changes from there.
            focused = Target.NONE
            loading.shown(); preparePage(view)
        }
        override fun onPageFinished(view: WebView, url: String) { loading.done(); preparePage(view) }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            webViewGone = true
            root.removeView(view)
            view.destroy()
            val now = SystemClock.elapsedRealtime()
            if (lastRendererCrash != 0L && now - lastRendererCrash < 30_000) {
                Toast.makeText(this@MainActivity, "Seite abgestürzt", Toast.LENGTH_LONG).show()
                finish()
            } else {
                lastRendererCrash = now
                recreate()
            }
            return true
        }
    }

    private fun preparePage(view: WebView) {
        if (BuildConfig.ZOOM != 100) view.evaluateJavascript("$ZOOM_VIEWPORT(${BuildConfig.ZOOM})", null)
        view.evaluateJavascript(WATCH_VIDEO, null)
        view.evaluateJavascript("$focusRing;__tvw.ring(${!cursor.pointerMode})", null)
    }

    /** Long OK switched modes: hand the position over, so the user continues where they were. */
    private fun modeChanged(pointerMode: Boolean) {
        log("Modus: ${if (pointerMode) "Zeiger" else "Fokus"}")
        Toast.makeText(this, if (pointerMode) "Zeiger" else "Fokus", Toast.LENGTH_SHORT).show()
        if (webViewGone) return
        if (pointerMode) {
            webView.evaluateJavascript("window.__tvw&&(__tvw.ring(false),__tvw.focusCenter())") { json ->
                val xy = json.trim('[', ']').split(',').mapNotNull { it.toFloatOrNull() }
                if (xy.size == 2) cursor.placeAt(xy[0], xy[1])
            }
        } else {
            webView.evaluateJavascript(
                "window.__tvw&&(__tvw.focusAt(${cursor.pointerX},${cursor.pointerY}),__tvw.ring(true))", null,
            )
        }
    }

    /**
     * Page side: [WATCH_VIDEO] and [FOCUS_RING]. Any frame can call these, so they may do no harm:
     * started() only hides the pointer; focused() and fullscreen() only change what the arrows and OK do
     * and whether popups from embedded players are dropped (main-frame calls check the focus themselves;
     * the TV volume is something the user can change back; a lying frame at most gets a popup through).
     */
    private inner class PageBridge {
        @JavascriptInterface fun started() = runOnUiThread { cursor.hideNow() }
        @JavascriptInterface fun focused(kind: Int) = runOnUiThread { focused = Target.of(kind) }
        @JavascriptInterface fun fullscreen(kind: Int) = runOnUiThread { fullscreenPlayer = Target.of(kind) }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Blocked: popups without a click, and popups from embedded players (their clicks open ads).
            if (!isUserGesture || inEmbeddedPlayer()) {
                log("Popup: verworfen (${if (!isUserGesture) "ohne Klick" else "aus eingebettetem Player"})")
                return false
            }
            // Catch the new window's first URL and load it in the main WebView instead.
            val catcher = WebView(this@MainActivity)
            catcher.webViewClient = object : WebViewClient() {
                private var done = false
                private fun take(url: String?) {
                    if (done || url == null || url == "about:blank") return
                    done = true
                    // Ads open their window as the finger touches down; the page reports that the focus went
                    // into the player only after that. Decide once the report is in.
                    root.postDelayed({
                        if (inEmbeddedPlayer()) log("Popup: ${short(url)}: verworfen (aus eingebettetem Player)")
                        else if (!webViewGone) {
                            if (Blocklist.blockedNow(Uri.parse(url))) {
                                log("Popup: ${short(url)}: Werbung blockiert")
                                Toast.makeText(this@MainActivity, "Werbung blockiert", Toast.LENGTH_SHORT).show()
                            } else {
                                log("Popup: ${short(url)}: im Fenster geöffnet")
                                userNavAt = SystemClock.elapsedRealtime()
                                webView.loadUrl(url)
                            }
                        }
                        catcher.destroy()
                    }, POPUP_DECIDE_MS) // catcher is never attached, so catcher.post would never run
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(r.url.toString()); return true }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) = take(url)
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = Blocklist.intercept(r)
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) = loading.progress(newProgress)
        override fun onShowCustomView(view: View, callback: CustomViewCallback) = showFullscreen(view, callback)
        override fun onHideCustomView() = exitFullscreen(notifyPage = false)

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        // Default poster is a grey play icon; a transparent pixel looks cleaner.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        /** How long after a page change the user started the new page may forward on its own. */
        private const val FORWARD_MS = 5_000L
        private val LOADABLE_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

        /**
         * The site a host belongs to, roughly: its last two labels, three for short country pairs such as
         * co.uk. Without the public suffix list some different sites look the same (fewer blocks, never more).
         */
        private fun site(host: String?): String {
            val labels = (host ?: "").lowercase().trimEnd('.').split('.')
            val n = if (labels.size >= 3 && labels[labels.size - 2].length <= 3 && labels.last().length == 2) 3 else 2
            return labels.takeLast(n).joinToString(".")
        }
        // Hides the pointer when a video starts with sound. Muted videos are usually decoration
        // (hero loops, hover previews) and must not take the pointer away while the user navigates.
        // Sees only the main frame, not videos inside iframes.
        /**
         * Defines window.__tvw in the page (once per document):
         * ring(on): draws a frame around the focused element, also where the site hides its own focus
         *   outline; follows the element every frame (scrolling, animations); main frame only, a focused
         *   iframe gets the frame as a whole.
         * focusCenter(): centre of the focused element in device pixels as [x, y], or null.
         * focusAt(x, y): focuses the focusable element at device pixels x, y, if there is one.
         * Reports to the app (as Target ordinal: 0 none, 1 iframe, 2 video, 3 text) what the focus is on outside
         * fullscreen (every frame while the ring is on, otherwise on focus changes), and what is in fullscreen.
         * Player page: a video fills half the screen and at most PAGE_EXTRAS focusable elements lie outside its
         * player element (a hoster's embed page; a site with a big trailer has many links, so it is none).
         * Rechecked at most once a second. There the player element counts as a video, and while the ring is on
         * and nothing has the focus, it gets the focus, so OK plays it right away.
         * For a focused player:
         *   standIn(down, up) moves the focus onto a stand-in over the player in the main frame, so the next
         *   arrow key navigates from there; for down/up it first scrolls the player's far edge to mid-screen,
         *   so the elements beyond a tall player are in view. Returns false if no player has the focus.
         *   enterPlayer() plays it in fullscreen (needs a user gesture just before).
         * For a video player in fullscreen: seek(seconds), toggle() play/pause; act on its largest video.
         * Text fields reached in focus mode get inputmode=none, so no keyboard pops up while moving through
         * the page; allowKeyboard() restores it on the focused field (the app then sends OK, which opens the
         * keyboard), leaving focus mode restores it too.
         * The ring hides while a player is in fullscreen. An iframe in fullscreen gets the focus back (the
         * page loses it on the switch), so the keys reach the embedded player. Leaving fullscreen scrolls the
         * focused element back into view (entering it scrolls the page to the top).
         * The frame element sits outside body and cancels the page scale of the zoom option (see preparePage).
         */
        /**
         * zoom option: gives the page a viewport of screen width * 100 / zoom CSS pixels, shown at zoom / 100,
         * like browser zoom: the page lays out for that width (its media queries see it), e.g. 50 → 1920.
         */
        private const val ZOOM_VIEWPORT = """(function(z){var m=document.querySelector('meta[data-tvw]');
if(!m){m=document.createElement('meta');m.name='viewport';m.setAttribute('data-tvw','');}
(document.head||document.documentElement).appendChild(m);
m.content='width='+Math.round(screen.width*100/z)+',initial-scale='+z/100+',minimum-scale='+z/100+',maximum-scale='+z/100})"""

        private const val FOCUS_RING = """if(!window.__tvw)window.__tvw=(function(){
var box=document.createElement('tvw-focus'),on=false,running=false,last='',focused=0;
var SEL='a[href],button,input,select,textarea,summary,[tabindex],[contenteditable]',PAGE_EXTRAS=5;
var BASE='position:fixed;pointer-events:none;z-index:2147483647;box-sizing:border-box;margin:0;padding:0;'+
'border:3px solid @ACCENT@;border-radius:6px;box-shadow:0 0 0 2px @OUTLINE@,inset 0 0 0 2px @OUTLINE@;background:none;display:block;';
function active(){var e=document.activeElement;while(e&&e.shadowRoot&&e.shadowRoot.activeElement)e=e.shadowRoot.activeElement;
return e&&e!==document.body&&e!==document.documentElement?e:null}
function scale(){return screen.width/document.documentElement.clientWidth||1}
function px(){return devicePixelRatio*scale()}
function area(v){var r=v.getBoundingClientRect();return r.width*r.height}
function largest(root){var best=null,a=0;root.querySelectorAll('video').forEach(function(v){var x=area(v);if(x>a){a=x;best=v}});return best}
var pp=null,ppAt=0;
function playerEl(){var t=Date.now();if(t-ppAt<1000)return pp;ppAt=t;pp=null;var v=largest(document);
if(!v||area(v)<innerWidth*innerHeight/2)return null;var p=v.parentElement&&v.parentElement.closest(SEL);
if(!p||/^(A|BUTTON)$/.test(p.tagName)||area(p)>area(v)*1.5)p=v;var n=0;
document.querySelectorAll(SEL).forEach(function(x){if(!p.contains(x)&&x.getClientRects().length)n++});
if(n<=PAGE_EXTRAS)pp=p;return pp}
function kind(e){return !e?0:e.tagName==='IFRAME'?1:e.tagName==='VIDEO'||e===playerEl()?2:editable(e)?3:0}
function autoFocus(){var p=playerEl();if(!p)return;if(!p.matches(SEL))p.tabIndex=-1;p.focus({preventScroll:true})}
function player(e){var k=kind(e);return k===1||k===2}
function fsKind(f){return !f?0:f.tagName==='IFRAME'?1:(f.tagName==='VIDEO'||f.querySelector('video'))?2:0}
function report(k){if(k!==focused){focused=k;tvwebapp.focused(k)}}
document.addEventListener('fullscreenchange',function(){var f=document.fullscreenElement;tvwebapp.fullscreen(fsKind(f));
if(f){if(f.tagName==='IFRAME')f.focus()}else requestAnimationFrame(function(){var e=active();if(e)e.scrollIntoView({block:'center'})})});
function vid(){var f=document.fullscreenElement,best=null,a=0;if(!f)return null;if(f.tagName==='VIDEO')return f;
f.querySelectorAll('video').forEach(function(v){var r=v.getBoundingClientRect();if(r.width*r.height>=a){a=r.width*r.height;best=v}});return best}
function quiet(p){if(p&&p.catch)p.catch(function(){})}
function editable(e){return !!e&&(e.isContentEditable||e.tagName==='TEXTAREA'||e.tagName==='INPUT'&&
/^(text|search|email|url|tel|password|number)$/.test(e.type))}
var allowed=null;
function restore(e){if(!e||!e.hasAttribute||!e.hasAttribute('data-tvw-im'))return;var m=e.getAttribute('data-tvw-im');
e.removeAttribute('data-tvw-im');if(m)e.setAttribute('inputmode',m);else e.removeAttribute('inputmode')}
function block(e){if(!on||e===allowed||!editable(e)||e.hasAttribute('data-tvw-im'))return;
e.setAttribute('data-tvw-im',e.getAttribute('inputmode')||'');e.setAttribute('inputmode','none')}
document.addEventListener('focusin',function(ev){block(ev.target)},true);
document.addEventListener('focusout',function(ev){if(ev.target===allowed)allowed=null;restore(ev.target)},true);
function now(){return document.fullscreenElement?0:kind(active())}
// Without the ring: focus moving into an iframe only blurs this window. Reported at once, before the
// click that moved it can open a popup; again later in case the focus was not settled yet.
function later(){if(!on)setTimeout(function(){report(now())})}
addEventListener('blur',function(){if(!on)report(now());later()});addEventListener('focus',later);document.addEventListener('focusin',later,true);
function tick(){if(!on){running=false;box.remove();last='';report(now());return}requestAnimationFrame(tick);
var fs=document.fullscreenElement;if(!fs&&!active())autoFocus();
var e=active(),s='display:none;';report(fs?0:kind(e));if(fsKind(fs))e=null;
if(e){var r=e.getBoundingClientRect(),w=innerWidth,h=innerHeight,
l=Math.max(2,r.left-4),t=Math.max(2,r.top-4),R=Math.min(w-2,r.right+4),B=Math.min(h-2,r.bottom+4);
var q=scale();if((r.width||r.height)&&R>l&&B>t)s='left:'+l*q+'px;top:'+t*q+'px;width:'+(R-l)*q+'px;height:'+(B-t)*q+'px;';}
var host=fs&&fs.tagName!=='VIDEO'&&fs.tagName!=='IFRAME'?fs:document.documentElement;
if(box.parentNode!==host)host.appendChild(box);
s+='zoom:'+(1/scale());if(s!==last){last=s;box.style.cssText=BASE+s}}
return{ring:function(v){on=v;if(on)block(active());else restore(active());if(on&&!running){running=true;tick()}},
allowKeyboard:function(){var e=active();allowed=e;restore(e)},
focusCenter:function(){var e=active();if(!e)return null;var r=e.getBoundingClientRect(),d=px();
return[(r.left+r.width/2)*d,(r.top+r.height/2)*d]},
focusAt:function(x,y){var d=px(),e=document.elementFromPoint(x/d,y/d);e=e&&e.closest(SEL);
if(e)e.focus({preventScroll:true})},
enterPlayer:function(){var e=active();if(!player(e))return;var v=e.tagName==='VIDEO'?e:e===playerEl()?largest(e):null;if(v)quiet(v.play());
quiet(e.requestFullscreen())},
seek:function(s){var v=vid();if(v)v.currentTime=Math.max(0,Math.min(v.duration||1e9,v.currentTime+s))},
toggle:function(){var v=vid();if(v){if(v.paused)quiet(v.play());else v.pause()}},
standIn:function(down,up){var f=active();if(!player(f)){report(0);return false}
var r=f.getBoundingClientRect(),h=innerHeight;
if(down&&r.bottom>h-40)scrollBy({top:r.bottom-h/2,behavior:'instant'});
if(up&&r.top<40)scrollBy({top:r.top-h/2,behavior:'instant'});
r=f.getBoundingClientRect();var o=document.documentElement.getBoundingClientRect(),p=document.createElement('tvw-standin');p.tabIndex=-1;
p.style.cssText='position:absolute;display:block;outline:none;pointer-events:none;left:'+(r.left-o.left)+'px;top:'+(r.top-o.top)+
'px;width:'+r.width+'px;height:'+r.height+'px';
p.addEventListener('blur',function(){p.remove()});document.documentElement.appendChild(p);
p.focus({preventScroll:true});report(0);return true}}})()"""
        private const val WATCH_VIDEO = "if(!window.__tvwVideo){window.__tvwVideo=1;" +
            "document.addEventListener('playing',function(e){var v=e.target;" +
            "if(v.tagName==='VIDEO'&&!v.muted&&v.volume>0)tvwebapp.started()},true)}"
        private val ARROWS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        private const val SEEK_S = 10
        private const val POPUP_DECIDE_MS = 300L
        private const val PAUSE_MEDIA = "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"
        // Static on purpose: after a crash the Activity is recreated, and the new instance must still
        // see the first crash to detect a second one within 30 s. 0 = no crash yet in this process.
        private var lastRendererCrash = 0L

        fun desktopUserAgent(default: String): String {
            val chrome = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: "120.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        }
    }
}
