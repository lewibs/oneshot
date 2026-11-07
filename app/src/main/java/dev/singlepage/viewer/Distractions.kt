package dev.singlepage.viewer

import android.net.Uri

/** CSS that hides feeds, recommendations and navigation on locked sites. Best effort: sites change their markup. */
object Distractions {

    private val youtube = """
        ytm-pivot-bar-renderer,
        ytm-item-section-renderer[section-identifier="related-items"],
        ytm-single-column-watch-next-results-renderer ytm-video-with-context-renderer,
        ytm-compact-video-renderer, ytm-compact-autoplay-renderer,
        ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model, ytm-rich-section-renderer,
        ytm-merch-shelf-renderer, ytm-promoted-sparkles-web-renderer,
        yt-lockup-view-model, .ytp-endscreen-content, .ytp-ce-element,
        .ytm-autonav-bar, ytm-autonav-toggle-button-container
        { display: none !important; }
    """.trimIndent()

    private val reddit = """
        #left-sidebar-container, #right-sidebar-container, reddit-sidebar-nav,
        pdp-right-rail, faceplate-tracker[noun="related_posts"],
        shreddit-async-loader[bundlename*="related"], shreddit-async-loader[bundlename*="more_posts"],
        shreddit-async-loader[bundlename*="trending"], community-highlight-carousel,
        games-section-badge-controller, xpromo-pill, xpromo-nsfw-blocking-container,
        shreddit-experience-tree, [slot="related-posts"], #pdp-more-posts
        { display: none !important; }
    """.trimIndent()

    fun script(uri: Uri): String? {
        val css = when (Policy.site(uri)) {
            "youtube.com", "youtube-nocookie.com" -> youtube
            "reddit.com" -> reddit
            else -> return null
        }
        val escaped = css.replace("\\", "\\\\").replace("`", "\\`")
        return """
            (function() {
              if (document.getElementById('spv-hide')) return;
              var s = document.createElement('style');
              s.id = 'spv-hide';
              s.textContent = `$escaped`;
              (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()
    }
}
