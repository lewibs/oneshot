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
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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
    private var lockKey: String? = null
    private var lockUrl: String? = null
    private var searchMode = false
    private var currentUrl: String? = null
        set(value) {
            field = value
            showAddress()
        }

    // Address bar over the page, and its text box.
    private var topBar: View? = null
    private var address: EditText? = null
    private var topBarShown = true

    // Infinite-scroll detection for pages not on the feed list.
    private var pageHeight = 0
    private var growths = 0
    private var feedLimit: Int? = null

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
            address?.hasFocus() == true -> address?.clearFocus()
            w != null -> goBack(w)
            else -> finish()
        }
    }

    /** Back to the previous page you were actually on, or to the search screen from Google or if there is none. */
    private fun goBack(w: WebView) {
        val history = w.copyBackForwardList()
        var i = history.currentIndex - 1
        // A locked page's history also holds the URL changes that were blocked and undone,
        // so skip past all of them to whatever came before the locked page.
        lockedNetwork()?.let { locked ->
            while (i >= 0 && Policy.network(Uri.parse(history.getItemAtIndex(i).url)) == locked) i--
        }
        // From AI Mode, back goes to the results it was opened from, past the chat's own steps.
        if (searchMode && w.url?.let { Policy.isAiMode(Uri.parse(it)) } == true) {
            while (i >= 0 && Policy.isAiMode(Uri.parse(history.getItemAtIndex(i).url))) i--
            if (i >= 0 && Policy.isGoogleSearch(Uri.parse(history.getItemAtIndex(i).url))) {
                w.goBackOrForward(i - history.currentIndex)
                return
            }
        }
        // From Google results, back always means back to the search box, not an earlier search.
        if (i < 0 || searchMode) {
            web = null
            topBar = null
            address = null
            w.destroy()
            showStart()
            return
        }
        val uri = Uri.parse(history.getItemAtIndex(i).url)
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
        val go = { if (input.text.isNotBlank()) search(input.text.toString()) }

        input.setOnEditorActionListener { _, _, _ -> go(); true }
        input.doAfterTextChanged { goButton.visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE }
        goButton.setOnClickListener { go() }
        view.findViewById<View>(R.id.search_bar).setOnClickListener { input.requestFocus() }
        showRecent(view.findViewById(R.id.recent))

        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        input.requestFocus()
        input.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Opens a link, or searches Google and remembers the search. */
    private fun search(input: String) {
        val text = input.trim()
        if (!Patterns.WEB_URL.matcher(text).matches()) lastSearch = text
        openPage(toUrl(text))
    }

    /** Recent searches as tappable rows under the search box. */
    private fun showRecent(list: LinearLayout) {
        list.removeAllViews()
        val dp = resources.displayMetrics.density
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        val borderless = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, borderless, true)
        for (query in recentSearches) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { search(query) }
            }
            row.addView(TextView(this).apply {
                text = query
                textSize = 15f
                setTextColor(getColor(R.color.text))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_history, 0, 0, 0)
                compoundDrawablePadding = (16 * dp).toInt()
                setPaddingRelative((20 * dp).toInt(), (12 * dp).toInt(), 0, (12 * dp).toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = "Delete $query"
                val pad = (12 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(borderless.resourceId)
                setOnClickListener {
                    removeSearch(query)
                    showRecent(list)
                }
            }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            list.addView(row)
        }
    }

    private fun removeSearch(query: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(RECENT_SEARCHES, recentSearches.filter { it != query }.joinToString("\n"))
            .remove(LAST_SEARCH).apply()
    }

    private val recentSearches: List<String>
        get() {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val saved = prefs.getString(RECENT_SEARCHES, null) ?: prefs.getString(LAST_SEARCH, "")
            return saved.orEmpty().split('\n').filter { it.isNotBlank() }
        }

    /** The newest search. Setting it moves it to the front of [recentSearches]. */
    private var lastSearch: String
        get() = recentSearches.firstOrNull().orEmpty()
        set(value) {
            val list = (listOf(value) + recentSearches.filter { it != value }).take(MAX_RECENT)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(RECENT_SEARCHES, list.joinToString("\n")).remove(LAST_SEARCH).apply()
        }

    private fun EditText.doAfterTextChanged(action: (Editable?) -> Unit) =
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = action(s)
        })

    private fun openPage(url: String) {
        val uri = Uri.parse(url)
        searchMode = Policy.isGoogleSearch(uri)
        lockKey = null
        lockUrl = null
        currentUrl = url
        resetFeedDetection()

        val w = web ?: newWebView(PageClient()).also {
            web = it
            it.setOnScrollChangeListener { v, x, y, _, oldY ->
                capScroll(v, x, y)
                slideTopBar(y, y - oldY)
            }
            root.removeAllViews()
            root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addTopBar(it)
        }
        w.loadUrl(url)
    }

    // ---- Address bar ----

    private fun addTopBar(w: WebView) {
        val bar = layoutInflater.inflate(R.layout.top_bar, root, false)
        val input = bar.findViewById<EditText>(R.id.address)
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        topBar = bar
        address = input
        topBarShown = true
        // The page sits below the bar; moving it rather than resizing it keeps scrolling smooth.
        bar.post { w.translationY = bar.height.toFloat(); fitUnderBar(w, bar.height) }

        // The X empties the box and starts typing a new search.
        val clear = bar.findViewById<View>(R.id.clear)
        clear.setOnClickListener {
            input.requestFocus()
            input.text.clear()
        }
        input.doAfterTextChanged { clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }

        input.setOnFocusChangeListener { _, focused ->
            val ime = getSystemService(InputMethodManager::class.java)
            if (focused) {
                // The newest search, so pressing Go gets you back to its results.
                input.setText(lastSearch.ifEmpty { currentUrl.orEmpty() })
                input.selectAll()
                ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } else {
                ime.hideSoftInputFromWindow(input.windowToken, 0)
                showAddress()
            }
        }
        input.setOnEditorActionListener { _, _, _ ->
            if (input.text.isNotBlank()) {
                val text = input.text.toString()
                input.clearFocus()
                search(text)
            }
            true
        }
        bar.findViewById<View>(R.id.search_again).setOnClickListener {
            lastSearch.takeIf { it.isNotEmpty() }?.let(::search) ?: input.requestFocus()
        }
        showAddress()
    }

    /** Shows the search on Google, otherwise the site you're on, unless you're typing. */
    private fun showAddress() {
        val input = address ?: return
        if (input.hasFocus()) return
        val uri = currentUrl?.let(Uri::parse)
        input.setText(currentQuery() ?: uri?.host?.removePrefix("www.").orEmpty())
    }

    private fun currentQuery(): String? {
        val uri = currentUrl?.let(Uri::parse) ?: return null
        return if (Policy.isGoogle(uri) && uri.path == "/search") uri.getQueryParameter("q") else null
    }

    /** Hides the bar while scrolling down the page and brings it back when scrolling up. */
    private fun slideTopBar(y: Int, dy: Int) {
        val bar = topBar ?: return
        val w = web ?: return
        val show = when {
            address?.hasFocus() == true || y <= 0 -> true
            dy > BAR_SLOP -> false
            dy < -BAR_SLOP -> true
            else -> return
        }
        if (show == topBarShown) return
        topBarShown = show
        val h = bar.height.toFloat()
        bar.animate().translationY(if (show) 0f else -h).setDuration(BAR_ANIM_MS).start()
        if (!show) fitUnderBar(w, 0)
        w.animate().translationY(if (show) h else 0f).setDuration(BAR_ANIM_MS)
            .withEndAction { if (show && topBarShown) fitUnderBar(w, bar.height) }.start()
    }

    /** Shortens the page by the bar's height while the bar shows, so nothing pinned to its bottom is cut off. */
    private fun fitUnderBar(w: WebView, barHeight: Int) {
        val params = w.layoutParams as FrameLayout.LayoutParams
        if (params.bottomMargin == barHeight) return
        params.bottomMargin = barHeight
        w.layoutParams = params
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(client: WebViewClient) = WebView(this).apply {
        // Shown before a page paints anything, so loading doesn't flash black.
        setBackgroundColor(getColor(R.color.bg))
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, GOOGLE_SCRIPT, setOf("*"))
        }
        webViewClient = client
        webChromeClient = Chrome()
        setDownloadListener { url, _, _, _, _ ->
            openExternal(Uri.parse(url))
        }
    }

    // ---- Rules ----

    /** Decides whether the main page may navigate to [uri]. */
    private fun allowed(uri: Uri): Boolean {
        val current = currentUrl?.let(Uri::parse)
        val target = Policy.linkTarget(uri) ?: uri
        val locked = lockedNetwork()
        return when {
            Policy.isAuth(target) -> true
            current != null && Policy.isAuth(current) -> true
            // On a social page: only that page on the same site; anywhere off the site is fine.
            locked != null && Policy.network(target) == locked -> Policy.lockKey(target) == lockKey
            else -> true
        }
    }

    /** The social site the current page is locked to, if any. */
    private fun lockedNetwork() = lockUrl?.let { Policy.network(Uri.parse(it)) }

    /** Fallback for WebViews too old for document-start scripts: restyles Google once it's drawn. */
    private fun trimGoogle(view: WebView, url: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            Policy.isGoogle(Uri.parse(url))
        ) view.evaluateJavascript(GOOGLE_SCRIPT, null)
    }

    private fun blocked() {
        Toast.makeText(this, "Blocked: this page stays put", Toast.LENGTH_SHORT).show()
    }

    /** On YouTube/Reddit, stops the page from scrolling past a few screens so feeds can't be scrolled. */
    private fun resetFeedDetection() {
        pageHeight = 0
        growths = 0
        feedLimit = null
    }

    private fun capScroll(view: View, x: Int, y: Int) {
        val url = currentUrl ?: return
        if (searchMode) return
        val screen = view.height
        val limit = if (Policy.isLocked(Uri.parse(url))) {
            (screen * Policy.MAX_SCROLL_SCREENS).toInt()
        } else {
            feedLimit ?: detectFeed(view as WebView, y, screen) ?: return
        }
        if (y > limit) view.scrollTo(x, limit)
    }

    /**
     * Any other page counts as a feed once it keeps loading more content as you near its bottom.
     * Returns the scroll limit once it does, or null while it still looks like a normal page.
     */
    private fun detectFeed(w: WebView, y: Int, screen: Int): Int? {
        @Suppress("DEPRECATION")
        val height = (w.contentHeight * w.scale).toInt()
        val nearOldBottom = y > screen && y + screen >= pageHeight - 2 * screen
        if (pageHeight > 0 && height > pageHeight + screen / 2 && nearOldBottom) growths++
        pageHeight = maxOf(pageHeight, height)
        if (growths < Policy.FEED_GROWTHS) return null
        Toast.makeText(this, "Infinite scroll: stopping here", Toast.LENGTH_SHORT).show()
        return maxOf((screen * Policy.MAX_SCROLL_SCREENS).toInt(), y).also { feedLimit = it }
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
            if (allowed(uri)) return false
            blocked()
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url
            resetFeedDetection()
            val uri = Uri.parse(url)
            if (Policy.isGoogleSearch(uri)) {
                searchMode = true
                if (uri.path == "/search") uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let { lastSearch = it }
            } else if (searchMode && Policy.isHttp(uri) && !Policy.isAuth(uri)) {
                // Left Google search for a result: that result is the new page.
                searchMode = false
                lockKey = null
                lockUrl = null
            }
            val locked = lockedNetwork()
            if (locked != null && Policy.isHttp(uri) && !Policy.isAuth(uri) && Policy.network(uri) != locked) {
                // Followed a link off the social site: the new page gets its own lock, if any.
                lockKey = null
                lockUrl = null
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String) = trimGoogle(view, url)

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            trimGoogle(view, url)
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
            if (Policy.network(uri) == lockedNetwork() && Policy.linkTarget(uri) == null &&
                Policy.isLocked(uri) && Policy.lockKey(uri) != key
            ) {
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
            if (allowed(uri)) web?.loadUrl(uri.toString()) else blocked()
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
        private const val PREFS = "oneshot"
        private const val LAST_SEARCH = "last_search"
        private const val RECENT_SEARCHES = "recent_searches"
        private const val MAX_RECENT = 5
        private const val BAR_SLOP = 6
        // Runs on Google pages before they're drawn: hides Google's header (logo, menu, sign-in,
        // search box) but keeps the tabs, and in dark mode swaps Google's charcoal for OneShot's black
        // and its link blues for the logo blue. Results and AI Mode both use it.
        private const val GOOGLE_SCRIPT = """
            (() => {
                const host = location.hostname.replace(/^www[.]/, '');
                if (host.split('.')[0] !== 'google' || window.oneshotGoogle) return;
                window.oneshotGoogle = true;
                const dark = matchMedia('(prefers-color-scheme: dark)');
                const map = {
                    '#22242a': '#000000', '#16171a': '#000000',
                    '#2c2e35': '#141418', '#2c303d': '#141418', '#2d2f35': '#141418', '#2d2f38': '#141418',
                    '#28292a': '#141418', '#303134': '#141418',
                    '#363840': '#1c1c22', '#42444d': '#26262c', '#3a3f50': '#26262c', '#4e5059': '#33333b',
                    '#99c3ff': '#3b82f6', '#a8c7fa': '#3b82f6', '#8ab4f8': '#3b82f6', '#b1c5ff': '#3b82f6'
                };
                // The same colours as they appear in computed gradients, e.g. "rgb(34, 36, 42)".
                const rgb = {};
                for (const hex in map) {
                    const n = parseInt(hex.slice(1), 16);
                    rgb['rgb(' + (n >> 16) + ', ' + (n >> 8 & 255) + ', ' + (n & 255) + ')'] = map[hex];
                }
                const hexOrRgb = new RegExp(Object.keys(map).join('|') + '|' +
                    Object.keys(rgb).map(c => c.replace(/[()]/g, '[(]').replace(/[)]/g, '[)]')).join('|'), 'gi');
                const swap = c => map[c.toLowerCase()] || rgb[c];
                const BASE = 'header:has(#tsf), header:has(form[role=search]),' +
                    'body > :has(#gb):not(:has([role=navigation], #rso, #main)),' +
                    'div:has(> div > div > div > div > a[aria-label="Go to Google home"]) { display: none !important; }' +
                    '@media (prefers-color-scheme: dark) { html, body { background: #000 !important; } }' +
                    '.oneshot-link { background: linear-gradient(90deg, #8b2cf5, #3b82f6) !important;' +
                    '-webkit-background-clip: text !important; background-clip: text !important;' +
                    '-webkit-text-fill-color: transparent !important;' +
                        '-webkit-box-decoration-break: clone; box-decoration-break: clone; }';

                // Every rule that sets a colour variable or a background to one of Google's dark colours gets
                // an !important twin with OneShot's colour, wherever it is: Google redefines its variables
                // on many elements, not just :root.
                const rules = [], seen = new WeakSet();
                let theme = null, count = -1;
                const scan = list => {
                    for (const r of list) {
                        if (seen.has(r)) continue;
                        if (r.media && !matchMedia(r.media.mediaText).matches) continue;
                        if (r.cssRules && !r.style) { scan(r.cssRules); continue; }
                        seen.add(r);
                        if (!r.style || !r.selectorText) continue;
                        let out = '';
                        for (const p of r.style) {
                            if (!p.startsWith('--') && !/^background(-color|-image)?$/.test(p)) continue;
                            const v = r.style.getPropertyValue(p), to = v.replace(hexOrRgb, swap);
                            if (to !== v) out += p + ':' + to + ' !important;';
                        }
                        if (out) rules.push(r.selectorText + '{' + out + '}');
                    }
                };
                const remap = () => {
                    const root = document.documentElement;
                    if (!root) return;
                    if (!theme) { theme = document.createElement('style'); theme.id = 'oneshot-google'; }
                    if (dark.matches) {
                        for (const sheet of document.styleSheets) {
                            if (sheet.ownerNode === theme) continue;
                            try { scan(sheet.cssRules) } catch (e) {}
                        }
                    }
                    if (rules.length !== count) { count = rules.length; theme.textContent = BASE + rules.join(''); }
                    if (theme.parentNode !== root) root.appendChild(theme);
                };
                // Link text gets the logo's purple-to-blue fade. Links are found by their blue text, which
                // keeps buttons, chips and plain text links Google styles otherwise as they are.
                const blue = c => {
                    const [r, g, b] = c.match(/\d+/g).map(Number);
                    return b >= 150 && b - r >= 40 && b - g >= 30;
                };
                const paint = () => {
                    for (const a of document.querySelectorAll('a')) {
                        for (const el of [a, ...a.querySelectorAll('*')]) {
                            if (![...el.childNodes].some(n => n.nodeType === 3 && n.data.trim())) continue;
                            const cs = getComputedStyle(el);
                            const on = blue(cs.color) && cs.backgroundColor === 'rgba(0, 0, 0, 0)' && cs.backgroundImage === 'none' ||
                                el.classList.contains('oneshot-link') && blue(cs.color);
                            el.classList.toggle('oneshot-link', on);
                        }
                    }
                };
                let painting = false;
                const repaint = () => {
                    if (painting) return;
                    painting = true;
                    requestAnimationFrame(() => { painting = false; paint(); });
                };
                new MutationObserver(repaint).observe(document, { childList: true, subtree: true });
                document.addEventListener('DOMContentLoaded', repaint);

                // Google adds styles as it goes (results stream in, AI Mode loads its chat), so remap each
                // time before the next frame is drawn.
                let queued = false;
                const later = () => {
                    if (queued) return;
                    queued = true;
                    requestAnimationFrame(() => { queued = false; remap(); });
                };
                new MutationObserver(records => {
                    for (const m of records) for (const n of m.addedNodes)
                        if (n.nodeName === 'STYLE' || n.nodeName === 'LINK' || n.nodeName === 'HTML') { remap(); return later(); }
                }).observe(document, { childList: true, subtree: true });
                document.addEventListener('load', () => { later(); repaint(); }, true);
                document.addEventListener('DOMContentLoaded', remap);
                remap();
            })();
        """
        private const val BAR_ANIM_MS = 180L
    }
}
