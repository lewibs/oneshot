# OneShot

A minimal Android browser that opens one page and keeps you there.

- Social sites (YouTube, Reddit, Instagram, TikTok, Facebook, X, Threads, Pinterest, LinkedIn, Tumblr and more) are locked to the page you opened: links within the site are blocked, links off it still work, and scrolling is capped at a few screens.
- Every other site works normally, links to anywhere included. A site that keeps loading more as you reach the bottom is detected as infinite scroll and capped from then on.
- Sign-in, consent and payment pages are always allowed.

Site lists live in `app/src/main/java/dev/oneshot/app/Policy.kt`.

Build and install: `./gradlew installDebug`
