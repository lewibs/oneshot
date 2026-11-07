package dev.singlepage.viewer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Patterns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * Opens one page and keeps you there. Rules:
 *  - sign-in/payment pages and server redirects are always allowed
 *  - YouTube/Reddit pages are locked: the URL may not change at all
 *  - Google search results each open as a new page
 *  - otherwise only links within the same site are allowed
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var popup: WebView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Navigation state for the main page.
    private var homeSite: String? = null
    private var lockKey: String? = null
    private var lockUrl: String? = null
    private var searchMode = false
    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            if (fullscreenView == null) v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            else v.setPadding(0, 0, 0, 0)
            WindowInsets.CONSUMED
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }

        val url = urlFromIntent(intent)
        if (url != null) openPage(url) else showStart()
    }

    override fun onDestroy() {
        popup?.destroy()
        web?.destroy()
        super.onDestroy()
    }

    @Deprecated("Used below Android 13")
    override fun onBackPressed() = handleBack()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT < 33) {
            handleBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleBack() {
        val w = web
        when {
            fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
            popup != null -> closePopup()
            w != null && lockKey == null && w.canGoBack() -> w.goBack()
            else -> finish()
        }
    }

    // ---- Opening pages ----

    private fun urlFromIntent(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_VIEW -> i.dataString
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            val m = Patterns.WEB_URL.matcher(text)
            if (m.find()) toUrl(m.group()) else toUrl(text)
        }
        Intent.ACTION_WEB_SEARCH -> i.getStringExtra("query")?.let { toUrl(it) }
        else -> null
    }

    private fun toUrl(input: String): String {
        val text = input.trim()
        return when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            !text.contains(' ') && text.contains('.') -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
    }

    private fun showStart() {
        val input = EditText(this).apply {
            hint = "Paste a link or search"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
        }
        val go = { if (input.text.isNotBlank()) openPage(toUrl(input.text.toString())) }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        val button = Button(this).apply {
            text = "Open"
            setOnClickListener { go() }
        }
        val title = TextView(this).apply {
            text = "Single Page"
            textSize = 24f
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        root.removeAllViews()
        root.addView(layout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        input.requestFocus()
    }

    private fun openPage(url: String) {
        val uri = Uri.parse(url)
        homeSite = Policy.site(uri)
        searchMode = Policy.isGoogleSearch(uri)
        lockKey = null
        lockUrl = null
        currentUrl = url

        val w = web ?: newWebView(PageClient()).also {
            web = it
            root.removeAllViews()
            root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        w.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(client: WebViewClient) = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = client
        webChromeClient = Chrome()
        setDownloadListener { url, _, _, _, _ ->
            openExternal(Uri.parse(url))
        }
    }

    // ---- Rules ----

    /** Decides whether the main page may navigate to [uri]. */
    private fun allowed(uri: Uri, isRedirect: Boolean): Boolean {
        val current = currentUrl?.let(Uri::parse)
        return when {
            Policy.isAuth(uri) -> true
            isRedirect -> true
            current != null && Policy.isAuth(current) -> true
            lockKey != null -> Policy.lockKey(uri) == lockKey
            searchMode -> true
            else -> Policy.site(uri) == homeSite
        }
    }

    private fun blocked() {
        Toast.makeText(this, "Blocked: this page stays put", Toast.LENGTH_SHORT).show()
    }

    private fun injectCss(view: WebView, url: String?) {
        val script = url?.let { Distractions.script(Uri.parse(it)) } ?: return
        view.evaluateJavascript(script, null)
    }

    /** Non-web links (mail, phone, app intents). Returns true: the WebView never loads them itself. */
    private fun openExternal(uri: Uri): Boolean {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            if (intent.`package` in Policy.blockedApps || intent.scheme in Policy.blockedSchemes) {
                blocked()
                return true
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            blocked()
        } catch (e: Exception) {
            blocked()
        }
        return true
    }

    // ---- Main page ----

    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            if (!Policy.isHttp(uri)) {
                return if (uri.scheme in setOf("about", "data", "blob", "javascript")) false
                else openExternal(uri)
            }
            if (allowed(uri, request.isRedirect)) return false
            blocked()
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url
            val uri = Uri.parse(url)
            if (Policy.isGoogleSearch(uri)) {
                searchMode = true
            } else if (searchMode && Policy.isHttp(uri) && !Policy.isAuth(uri)) {
                // Left Google search for a result: that result is the new page.
                searchMode = false
                homeSite = Policy.site(uri)
                lockKey = null
                lockUrl = null
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String) = injectCss(view, url)

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            injectCss(view, url)
            val uri = Uri.parse(url)
            if (lockKey == null && !searchMode && Policy.isLocked(uri)) {
                lockKey = Policy.lockKey(uri)
                lockUrl = url
            }
        }

        // Catches YouTube/Reddit changing the URL with JavaScript instead of loading a page.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
            injectCss(view, url)
            val key = lockKey ?: return
            val uri = Uri.parse(url)
            if (Policy.isLocked(uri) && Policy.lockKey(uri) != key) {
                blocked()
                lockUrl?.let { view.loadUrl(it) }
            }
        }
    }

    // ---- Popups, fullscreen video, file uploads ----

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            val probe = newWebView(PopupClient())
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window == popup) closePopup()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenView = view
            fullscreenCallback = callback
            root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            root.requestApplyInsets()
        }

        override fun onHideCustomView() {
            fullscreenView?.let { root.removeView(it) }
            fullscreenView = null
            fullscreenCallback = null
            root.requestApplyInsets()
        }

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(params.createIntent(), FILE_REQUEST)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    @Deprecated("Simple file chooser result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQUEST) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /**
     * A window opened by the page. Sign-in popups are shown on top so they can report back
     * to the page; anything else is treated like a link tap on the main page.
     */
    private inner class PopupClient : WebViewClient() {
        private var decided = false

        private fun decide(view: WebView, uri: Uri): Boolean {
            if (decided) {
                // Inside a sign-in popup: allow the flow, but never into locked sites.
                if (Policy.isLocked(uri)) {
                    blocked()
                    return true
                }
                return false
            }
            if (!Policy.isHttp(uri)) return false
            decided = true
            if (Policy.isAuth(uri)) {
                showPopup(view)
                return false
            }
            if (allowed(uri, isRedirect = false)) web?.loadUrl(uri.toString()) else blocked()
            view.stopLoading()
            view.post { view.destroy() }
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
            request.isForMainFrame && decide(view, request.url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!decided) decide(view, Uri.parse(url))
        }
    }

    private fun showPopup(view: WebView) {
        popup = view
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun closePopup() {
        popup?.let {
            root.removeView(it)
            it.destroy()
        }
        popup = null
    }

    companion object {
        private const val FILE_REQUEST = 1
    }
}
