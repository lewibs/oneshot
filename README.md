# OneShot

A minimal Android browser that opens one page and keeps you there.

- Sign-in, consent and payment pages and server redirects are always allowed.
- YouTube and Reddit pages are locked: the URL can't change, and scrolling is capped at a few screens.
- Instagram, TikTok, Facebook, X, Threads, Pinterest, LinkedIn, Tumblr and other feeds are capped too (links still work). Any other site that keeps loading more as you reach the bottom is detected as infinite scroll and capped from then on.
- Google search results each open as their own page.
- Everywhere else, only links within the same site work.

Site lists live in `app/src/main/java/dev/oneshot/app/Policy.kt`.

Build and install: `./gradlew installDebug`
