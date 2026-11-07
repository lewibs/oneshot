package dev.oneshot.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * Opens one page and keeps you there. Rules:
 *  - sign-in/payment pages and server redirects are always allowed
 *  - YouTube/Reddit pages are locked: the URL may not change at all, and scrolling is capped
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
        // Status/nav bar icons that contrast with the light or dark background.
        val darkIcons = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else darkIcons, darkIcons)

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
            w != null -> goBack(w)
            else -> finish()
        }
    }

    /** Back to the previous page you were actually on, or to the search screen if there is none. */
    private fun goBack(w: WebView) {
        val history = w.copyBackForwardList()
        var i = history.currentIndex - 1
        // A locked page's history also holds the URL changes that were blocked and undone,
        // so skip past all of them to whatever came before the locked page.
        if (lockKey != null) {
            while (i >= 0 && Policy.isLocked(Uri.parse(history.getItemAtIndex(i).url))) i--
        }
        if (i < 0) {
            web = null
            w.destroy()
            showStart()
            return
        }
        val uri = Uri.parse(history.getItemAtIndex(i).url)
        homeSite = Policy.site(uri)
        searchMode = Policy.isGoogleSearch(uri)
        lockKey = null
        lockUrl = null
        w.goBackOrForward(i - history.currentIndex)
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
        val view = layoutInflater.inflate(R.layout.start, root, false)
        val input = view.findViewById<EditText>(R.id.input)
        val goButton = view.findViewById<ImageButton>(R.id.go)
        val go = { if (input.text.isNotBlank()) openPage(toUrl(input.text.toString())) }

        input.setOnEditorActionListener { _, _, _ -> go(); true }
        input.doAfterTextChanged { goButton.visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE }
        goButton.setOnClickListener { go() }
        view.findViewById<View>(R.id.search_bar).setOnClickListener { input.requestFocus() }

        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        input.requestFocus()
        input.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun EditText.doAfterTextChanged(action: (Editable?) -> Unit) =
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = action(s)
        })

    private fun openPage(url: String) {
        val uri = Uri.parse(url)
        homeSite = Policy.site(uri)
        searchMode = Policy.isGoogleSearch(uri)
        lockKey = null
        lockUrl = null
        currentUrl = url

        val w = web ?: newWebView(PageClient()).also {
            web = it
            it.setOnScrollChangeListener { v, x, y, _, _ -> capScroll(v, x, y) }
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

    /** On YouTube/Reddit, stops the page from scrolling past a few screens so feeds can't be scrolled. */
    private fun capScroll(view: View, x: Int, y: Int) {
        val url = currentUrl ?: return
        if (searchMode || !Policy.isLocked(Uri.parse(url))) return
        val limit = (view.height * Policy.MAX_SCROLL_SCREENS).toInt()
        if (y > limit) view.scrollTo(x, limit)
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

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            val uri = Uri.parse(url)
            if (lockKey == null && !searchMode && Policy.isLocked(uri)) {
                lockKey = Policy.lockKey(uri)
                lockUrl = url
            }
        }

        // Catches YouTube/Reddit changing the URL with JavaScript instead of loading a page.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
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
