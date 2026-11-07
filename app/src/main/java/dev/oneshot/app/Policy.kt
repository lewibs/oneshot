package dev.oneshot.app

import android.net.Uri

/** Which pages may be reached from which. All site lists live here. */
object Policy {

    /**
     * Social sites, by domain, named so a site's domains count as one. The page you open on
     * one is locked: links within the site are blocked, links off it work, scrolling is capped.
     */
    private val socialSites = mapOf(
        "youtube.com" to "youtube", "youtu.be" to "youtube", "youtube-nocookie.com" to "youtube",
        "reddit.com" to "reddit", "redd.it" to "reddit",
        "instagram.com" to "instagram", "tiktok.com" to "tiktok", "facebook.com" to "facebook",
        "x.com" to "x", "twitter.com" to "x", "threads.net" to "threads", "threads.com" to "threads",
        "pinterest.com" to "pinterest", "linkedin.com" to "linkedin", "tumblr.com" to "tumblr",
        "9gag.com" to "9gag", "imgur.com" to "imgur", "quora.com" to "quora",
        "twitch.tv" to "twitch", "bsky.app" to "bluesky",
    )

    /** How far a feed may scroll, in screen heights. */
    const val MAX_SCROLL_SCREENS = 3.0

    /** Times a page must load more content as you near its bottom before it counts as a feed. */
    const val FEED_GROWTHS = 2

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

    /** The social site [uri] is on, e.g. "youtube" for youtu.be, or null. */
    fun network(uri: Uri) = socialSites.entries.firstOrNull { hostMatches(uri, it.key) }?.value

    fun isLocked(uri: Uri) = network(uri) != null && !isAuth(uri)

    /** Where a site's outbound-link redirector (l.instagram.com/?u=...) is sending you, or null. */
    fun linkTarget(uri: Uri): Uri? {
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val param = when {
            host == "l.instagram.com" || host == "l.facebook.com" || host == "lm.facebook.com" -> "u"
            hostMatches(uri, "youtube.com") && path == "/redirect" -> "q"
            host == "out.reddit.com" -> "url"
            hostMatches(uri, "linkedin.com") && path.startsWith("/redir") -> "url"
            host == "t.umblr.com" -> "z"
            else -> return null
        }
        return uri.getQueryParameter(param)?.let(Uri::parse)
    }

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
        val name = network(uri) ?: site(uri)
        return when {
            site(uri) == "youtu.be" -> "youtube:watch:" + path.removePrefix("/")
            name == "youtube" && path == "/watch" -> "youtube:watch:" + uri.getQueryParameter("v")?.lowercase()
            else -> "$name:$path"
        }
    }

    private fun hostMatches(uri: Uri, host: String): Boolean {
        val h = uri.host?.lowercase() ?: return false
        return h == host || h.endsWith(".$host")
    }
}
