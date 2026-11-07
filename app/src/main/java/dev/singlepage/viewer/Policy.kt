package dev.singlepage.viewer

import android.net.Uri

/** Which pages may be reached from which. All site lists live here. */
object Policy {

    /** Sites where the opened page is locked: the URL may not change at all. */
    private val lockedSites = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "reddit.com", "redd.it",
    )

    /** How far a YouTube/Reddit page may scroll, in screen heights. */
    const val MAX_SCROLL_SCREENS = 3.0

    /** Apps that links must never escape into. */
    val blockedApps = setOf("com.google.android.youtube", "com.reddit.frontpage")
    val blockedSchemes = setOf("vnd.youtube", "youtube", "reddit")

    /** Sign-in, consent and payment pages: always allowed so logins don't break. "host" or "host/pathPrefix". */
    private val authPatterns = listOf(
        "accounts.google.com", "accounts.youtube.com", "myaccount.google.com",
        "consent.google.com", "consent.youtube.com", "pay.google.com",
        "appleid.apple.com", "idmsa.apple.com",
        "login.microsoftonline.com", "login.microsoft.com", "login.live.com", "account.live.com",
        "github.com/login", "github.com/session", "github.com/sessions",
        "facebook.com/login", "facebook.com/dialog", "facebook.com/v",
        "x.com/i/oauth2", "twitter.com/i/oauth2", "api.twitter.com/oauth",
        "discord.com/oauth2", "discord.com/login",
        "slack.com/oauth", "slack.com/signin",
        "login.yahoo.com", "id.atlassian.com",
        "amazon.com/ap",
        "auth0.com", "okta.com", "onelogin.com",
        "paypal.com", "stripe.com", "klarna.com", "shop.app",
    )

    fun isHttp(uri: Uri) = uri.scheme == "http" || uri.scheme == "https"

    fun isLocked(uri: Uri) = lockedSites.any { hostMatches(uri, it) } && !isAuth(uri)

    fun isAuth(uri: Uri) = authPatterns.any { pattern ->
        val host = pattern.substringBefore('/')
        val path = pattern.substringAfter('/', "")
        hostMatches(uri, host) && (path.isEmpty() || (uri.path ?: "").startsWith("/$path"))
    }

    fun isGoogle(uri: Uri) = site(uri)?.let { it.startsWith("google.") } == true

    fun isGoogleSearch(uri: Uri) = isGoogle(uri) && uri.path.orEmpty().let {
        it == "/search" || it == "/" || it == "" || it == "/webhp" || it == "/url"
    }

    /** Roughly the registrable domain: "m.youtube.com" -> "youtube.com", "bbc.co.uk" stays. */
    fun site(uri: Uri): String? {
        val labels = uri.host?.lowercase()?.split('.') ?: return null
        if (labels.size < 2) return labels.joinToString(".")
        val shortTld = labels.last().length == 2
        val secondLevel = labels[labels.size - 2] in setOf("co", "com", "org", "net", "ac", "gov", "edu")
        val keep = if (labels.size >= 3 && shortTld && secondLevel) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    /** What must stay the same on a locked page. Ignores tracking params and timestamps. */
    fun lockKey(uri: Uri): String {
        val path = uri.path.orEmpty().trimEnd('/').lowercase()
        return when (site(uri)) {
            "youtu.be" -> "yt:watch:" + path.removePrefix("/")
            "youtube.com", "youtube-nocookie.com" ->
                if (path == "/watch") "yt:watch:" + uri.getQueryParameter("v")?.lowercase()
                else "yt:$path"
            else -> "${site(uri)}:$path"
        }
    }

    private fun hostMatches(uri: Uri, host: String): Boolean {
        val h = uri.host?.lowercase() ?: return false
        return h == host || h.endsWith(".$host")
    }
}
