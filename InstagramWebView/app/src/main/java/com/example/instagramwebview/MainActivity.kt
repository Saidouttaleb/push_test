package com.example.instagramwebview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.instagramwebview.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Holds the callback for <input type="file"> requests from the WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    // Launcher for the system file-picker used by Instagram's media upload
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { arrayOf(it) }
            } else null
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
        }

    // True when the incoming intent is a shared reel / single-reel deep-link.
    // In that mode we lock down all navigation to prevent further browsing.
    private var isReelDeepLink = false

    // The URL we must never navigate away from when in reel-lock mode
    private var lockedReelUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Determine whether this launch is a shared reel link
        val incomingUrl = resolveIncomingUrl(intent)
        isReelDeepLink = incomingUrl != null && isReelUrl(incomingUrl)
        lockedReelUrl = if (isReelDeepLink) incomingUrl else null

        setupWebView()
        setupSwipeRefresh()
        setupRetryButton()

        val startUrl = incomingUrl ?: INSTAGRAM_HOME
        binding.webView.loadUrl(startUrl)
    }

    // -------------------------------------------------------------------------
    // WebView setup
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Use a desktop UA so Instagram serves the full web interface
            userAgentString = DESKTOP_UA
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        // Expose a minimal JS bridge (no data ever sent to us)
        wv.addJavascriptInterface(WebAppInterface(), JS_BRIDGE_NAME)

        wv.webViewClient = InstagramWebViewClient()
        wv.webChromeClient = InstagramWebChromeClient()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.ig_gradient_start,
            R.color.ig_gradient_mid,
            R.color.ig_gradient_end
        )
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.webView.reload()
        }
        // Disable pull-to-refresh when in reel-lock mode so the user cannot
        // navigate away even via a refresh that triggers navigation.
        if (isReelDeepLink) {
            binding.swipeRefreshLayout.isEnabled = false
        }
    }

    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.webView.reload()
        }
    }

    // -------------------------------------------------------------------------
    // Back-button handling
    // -------------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = binding.webView
        when {
            // In reel-lock mode back = close the app, never go back to Instagram
            isReelDeepLink -> finish()
            wv.canGoBack() -> wv.goBack()
            else -> super.onBackPressed()
        }
    }

    // Key-event fallback (hardware back on older devices)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack() && !isReelDeepLink) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------------------
    // URL helpers
    // -------------------------------------------------------------------------

    private fun resolveIncomingUrl(intent: Intent?): String? {
        if (intent == null) return null
        val uri = intent.data ?: return null
        val url = uri.toString()
        return if (url.contains("instagram.com")) url else null
    }

    private fun isReelUrl(url: String): Boolean =
        REEL_PATH_REGEX.containsMatchIn(url)

    // -------------------------------------------------------------------------
    // JavaScript & CSS injection
    // -------------------------------------------------------------------------

    /**
     * Called by the WebViewClient after every page load.
     * Injects CSS that hides unwanted sections, then JS that enforces scroll
     * limits and removes suggested / explore / reel-feed elements.
     */
    private fun injectContentFilters(view: WebView, isReel: Boolean) {
        view.evaluateJavascript(buildCssInjection(isReel), null)
        view.evaluateJavascript(buildJsInjection(isReel), null)
    }

    /** Returns a JS snippet that creates a <style> tag with our CSS rules. */
    private fun buildCssInjection(reelLock: Boolean): String {
        val css = buildString {
            // ── Bottom navigation: hide Explore (search), Reels, and Shop tabs ──
            // Instagram uses SVG aria-labels on nav links
            append("""
                /* Hide Explore / Search tab */
                a[href="/explore/"] { display: none !important; }

                /* Hide Reels tab in bottom nav */
                a[href*="/reels/"] svg[aria-label*="Reel"],
                a[href*="/reels/"] svg[aria-label*="reel"] { display: none !important; }
                nav a[href*="/reels"] { display: none !important; }

                /* Hide the dedicated Reels nav item (icon-based selector) */
                [aria-label="Reels"] { display: none !important; }

                /* Hide Shop / Marketplace tab */
                a[href*="/shop"] { display: none !important; }

                /* ── Suggested posts separator and everything below it ── */
                /* The "Suggested posts" heading is inside an <hr>-adjacent block */
                hr ~ * { display: none !important; }

                /* Catch the "Suggested Posts" header element directly */
                [data-testid="suggested-posts-header"],
                div[class*="suggested"] { display: none !important; }

                /* ── Reels in the main feed ── */
                /* Reel cards inside the feed use a video element with no controls */
                article:has(> div > div > div > video) { display: none !important; }

                /* Standalone reel shelf ("Reels you might like", etc.) */
                div[class*="ReelShelf"],
                div[class*="reelShelf"],
                section[class*="reel"] { display: none !important; }

                /* ── Stories tray: keep it but ensure only followed accounts show ──
                   We cannot reliably CSS-filter "suggested" stories without JS,
                   so JS handles that below. */

            """.trimIndent())

            if (reelLock) {
                // In reel-lock mode hide every UI chrome except the video itself
                append("""
                    /* Hide everything around the reel player */
                    header, nav, footer,
                    [role="navigation"],
                    div[class*="Sidebar"],
                    div[class*="sidebar"],
                    section[class*="Comments"],
                    /* Related reels section */
                    section[class*="related"],
                    div[class*="SuggestedContent"],
                    /* The "more reels" overlay at the end of a reel */
                    div[class*="EndCard"],
                    div[class*="endCard"] { display: none !important; }

                    /* Prevent vertical overflow so user cannot scroll to next reel */
                    body, html { overflow: hidden !important; }
                    div[class*="ReelsViewer"],
                    div[class*="reelViewer"] { overflow: hidden !important; }
                """.trimIndent())
            }
        }

        // Wrap in a JS snippet that appends the <style> node
        return """
            (function() {
                var existing = document.getElementById('__igwv_style');
                if (existing) existing.remove();
                var style = document.createElement('style');
                style.id = '__igwv_style';
                style.type = 'text/css';
                style.appendChild(document.createTextNode(${css.toJsString()}));
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
    }

    /**
     * Returns a JS snippet that:
     *  1. Removes suggested-post articles by scanning text content.
     *  2. Removes suggested / sponsored story bubbles.
     *  3. In reel-lock mode, disables swipe/scroll to next reel.
     *  4. Sets up a MutationObserver to re-apply the above on dynamic updates.
     */
    private fun buildJsInjection(reelLock: Boolean): String = """
        (function() {
            'use strict';

            // ── Helpers ──────────────────────────────────────────────────────

            function removeMatchingArticles() {
                var articles = document.querySelectorAll('article');
                articles.forEach(function(article) {
                    // "Suggested for you" cards contain this accessible label
                    var label = (article.getAttribute('aria-label') || '').toLowerCase();
                    if (label.indexOf('suggested') !== -1) {
                        article.remove();
                        return;
                    }
                    // Sponsored posts have a "Sponsored" tag inside them
                    var spans = article.querySelectorAll('span');
                    for (var i = 0; i < spans.length; i++) {
                        var txt = spans[i].textContent.trim();
                        if (txt === 'Sponsored' || txt === 'Suggested for you') {
                            article.remove();
                            return;
                        }
                    }
                });
            }

            function removeHrAndBelow() {
                // After the last followed-account post Instagram inserts an <hr>
                // and then "Suggested posts". Remove the <hr> and everything after.
                var hrs = document.querySelectorAll('main hr, article ~ hr');
                hrs.forEach(function(hr) {
                    var next = hr.nextElementSibling;
                    while (next) {
                        var toRemove = next;
                        next = next.nextElementSibling;
                        toRemove.remove();
                    }
                    hr.remove();
                });
            }

            function removeSuggestedStories() {
                // Story bubbles that are "suggested" have an aria-label containing
                // "Suggested" or appear after the user's own story bubble.
                var storyItems = document.querySelectorAll('[aria-label*="Suggested"], [aria-label*="suggested"]');
                storyItems.forEach(function(el) { el.remove(); });
            }

            function removeReelEntries() {
                // Reel entries in the bottom nav and in the feed
                document.querySelectorAll('a[href*="/reels/"]').forEach(function(a) {
                    // Only remove if it's a nav-level link, not the actual reel player
                    if (a.closest('nav') || a.closest('[role="navigation"]')) {
                        a.remove();
                    }
                });
            }

            function applyAll() {
                removeMatchingArticles();
                removeHrAndBelow();
                removeSuggestedStories();
                removeReelEntries();
            }

            ${if (reelLock) buildReelLockJs() else ""}

            // ── Initial run ──────────────────────────────────────────────────
            applyAll();

            // ── MutationObserver re-applies on dynamic DOM changes ───────────
            if (window.__igwv_observer) {
                window.__igwv_observer.disconnect();
            }
            var observer = new MutationObserver(function(mutations) {
                applyAll();
            });
            window.__igwv_observer = observer;
            observer.observe(document.body || document.documentElement, {
                childList: true,
                subtree: true
            });

        })();
    """.trimIndent()

    /** Returns the JS fragment that locks a single reel in view. */
    private fun buildReelLockJs(): String = """
        // ── Reel-lock: prevent swiping / scrolling to other reels ───────────
        function lockReelScroll() {
            // Freeze body scroll
            document.body.style.overflow = 'hidden';
            document.documentElement.style.overflow = 'hidden';

            // The reel feed container uses touch events for swipe-to-next.
            // We intercept touchmove on the reel wrapper and stop propagation.
            var reelContainers = document.querySelectorAll(
                '[class*="Reel"], [class*="reel"], [data-testid*="reel"]'
            );
            reelContainers.forEach(function(el) {
                if (!el.__igwv_locked) {
                    el.__igwv_locked = true;
                    el.addEventListener('touchmove', function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                    }, { passive: false });
                    el.addEventListener('wheel', function(e) {
                        e.stopPropagation();
                        e.preventDefault();
                    }, { passive: false });
                }
            });

            // Hide "next reel" / "up next" overlay cards
            document.querySelectorAll('[class*="EndCard"], [class*="endCard"], [class*="NextReel"]')
                .forEach(function(el) { el.remove(); });
        }
        lockReelScroll();
        // Also call after a short delay for late-rendered elements
        setTimeout(lockReelScroll, 800);
        setTimeout(lockReelScroll, 2000);
    """

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    private inner class InstagramWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()

            // In reel-lock mode, only the original reel URL is allowed
            if (isReelDeepLink) {
                val locked = lockedReelUrl ?: return true
                // Allow the exact reel URL and its direct children (e.g. CDN resources)
                if (!url.contains("instagram.com") || !isReelUrl(url)) {
                    // Allow CDN / static asset requests but block navigation to other pages
                    if (request.isForMainFrame) return true
                }
                return false
            }

            // Block navigation to Explore, Reels feed, and Shop
            if (isBlockedNavigation(url)) {
                Toast.makeText(
                    this@MainActivity,
                    "This section is disabled.",
                    Toast.LENGTH_SHORT
                ).show()
                return true // consumed — do not load
            }

            // Allow everything else on instagram.com; send other domains to the browser
            return if (url.contains("instagram.com") ||
                url.contains("cdninstagram.com") ||
                url.contains("fbcdn.net")
            ) {
                false // let WebView handle it
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            binding.progressBar.visibility = View.VISIBLE
            binding.errorView.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            binding.progressBar.visibility = View.GONE
            binding.swipeRefreshLayout.isRefreshing = false
            // Inject our CSS + JS filters after every page load
            injectContentFilters(view, isReelDeepLink || isReelUrl(url))
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
                binding.errorView.visibility = View.VISIBLE
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never proceed past SSL errors — cancel and show error screen
            handler.cancel()
            binding.progressBar.visibility = View.GONE
            binding.errorView.visibility = View.VISIBLE
        }
    }

    private inner class InstagramWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress == 100) {
                binding.progressBar.visibility = View.GONE
            } else {
                binding.progressBar.visibility = View.VISIBLE
            }
        }

        // Required for file-upload dialogs (e.g. uploading a profile picture)
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = filePathCallback
            val intent = fileChooserParams.createIntent()
            filePickerLauncher.launch(intent)
            return true
        }

        // Prevent pop-up windows
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean = false

        override fun onJsAlert(
            view: WebView, url: String, message: String, result: JsResult
        ): Boolean {
            result.cancel()
            return true
        }

        override fun onJsConfirm(
            view: WebView, url: String, message: String, result: JsResult
        ): Boolean {
            result.cancel()
            return true
        }
    }

    /**
     * Minimal JavaScript bridge — exists only so that future debug/logging JS
     * can call Android.log(). No data is ever collected or transmitted.
     */
    inner class WebAppInterface {
        @JavascriptInterface
        fun log(message: String) {
            // Intentionally no-op in release builds.
            // In a debug build you could use: android.util.Log.d("IGWV", message)
        }
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    companion object {
        private const val INSTAGRAM_HOME = "https://www.instagram.com/"

        /**
         * Desktop user-agent so Instagram serves the full web app (not the
         * mobile lite page which lacks DM support).
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val JS_BRIDGE_NAME = "Android"

        /** Matches /reel/<id>/ and /reels/<id>/ URL patterns */
        private val REEL_PATH_REGEX = Regex("""/reels?/[\w-]+""")

        /**
         * Returns true for URLs that should be completely blocked in normal
         * (non-reel-lock) mode: Explore, Reels feed, and Shop.
         */
        private fun isBlockedNavigation(url: String): Boolean {
            if (!url.contains("instagram.com")) return false
            val path = Uri.parse(url).path ?: return false
            return path == "/explore/" ||
                path.startsWith("/explore") ||
                path == "/reels/" ||
                path.startsWith("/reels") && !REEL_PATH_REGEX.containsMatchIn(path) ||
                path.startsWith("/shop")
        }
    }
}

// ── Extension: escapes a Kotlin String for safe embedding inside JS ──────────
private fun String.toJsString(): String {
    val escaped = this
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")
    return "`$escaped`"
}
