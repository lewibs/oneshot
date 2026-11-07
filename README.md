# Single Page

A minimal Android browser that opens one page and keeps you there.

- Sign-in, consent and payment pages and server redirects are always allowed.
- YouTube and Reddit pages are locked: the URL can't change, and feeds/recommendations are hidden.
- Google search results each open as their own page.
- Everywhere else, only links within the same site work.

Site lists live in `app/src/main/java/dev/singlepage/viewer/Policy.kt`.

Build and install: `./gradlew installDebug`
