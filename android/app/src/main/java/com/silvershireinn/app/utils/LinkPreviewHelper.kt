package com.silvershireinn.app.utils

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches Open Graph metadata for a URL and caches results in memory.
 * Also provides URL-detection and classification helpers.
 */
object LinkPreviewHelper {

    data class LinkPreview(
        val url: String,
        val title: String,
        val description: String,
        val imageUrl: String?,
        val domain: String,
        val isVideo: Boolean = false
    )

    // Sentinel so we don't re-request failed URLs
    private val EMPTY = LinkPreview("", "", "", null, "")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, LinkPreview>()   // read from several threads

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // http(s), www., and bare domains (example.com / sub.example.org/path)
    private val URL_REGEX = Regex(
        """(?i)\b((?:https?://|www\.)[^\s<>"'\]]+|\b(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+(?:com|org|net|edu|gov|io|co|us|uk|dev|app|info|biz|me|tv|cc|ai|gg|xyz|app)(?:/[^\s<>"'\]]*)?)"""
    )

    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "mkv", "avi", "m3u8")

    private val YOUTUBE_REGEX = Regex("""(youtube\.com/watch\?.*v=|youtu\.be/)([A-Za-z0-9_-]+)""")

    /** Returns the first website-like URL found in [text], normalized to http(s), or null. */
    fun firstUrl(text: String): String? {
        val raw = URL_REGEX.find(text)?.value ?: return null
        return normalizeUrl(raw)
    }

    /** Normalize www./bare domain matches to an https URL for Intent VIEW / previews. */
    fun normalizeUrl(raw: String): String {
        val trail = setOf('.', ',', ')', ';', ']', '!', '?', '\'')
        var u = raw.trim().trimEnd { it in trail }
        if (!u.contains("://")) u = "https://$u"
        return u
    }

    /** True if [text] contains any linkifiable URL (including bare domains). */
    fun containsUrl(text: String): Boolean = URL_REGEX.containsMatchIn(text)

    /** Returns true if the URL points to a video (by extension or YouTube). */
    fun isVideoUrl(url: String): Boolean {
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        return ext in VIDEO_EXTENSIONS || YOUTUBE_REGEX.containsMatchIn(url)
    }

    /** Returns true if the URL is a YouTube video link. */
    fun isYouTubeUrl(url: String): Boolean = YOUTUBE_REGEX.containsMatchIn(url)

    /**
     * Fetches OG metadata for [url] on the IO dispatcher.
     * Returns null if the fetch fails or yields no useful data.
     * Caches results (including failures) in memory.
     */
    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext if (it === EMPTY) null else it }

        // Mark as attempted so we don't hammer the server on failures
        cache[url] = EMPTY

        try {
            // GET directly (many sites reject HEAD), stop if it isn't a web page, and read at
            // most 256 KB: the tags we need are in <head>; whole pages used to be loaded.
            val html = client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) Silvershire link preview")
                    .header("Accept", "text/html")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val type = resp.header("Content-Type") ?: ""
                if (!type.contains("text/html", true)) return@withContext null
                val src = resp.body?.source() ?: return@withContext null
                src.request(262_144)
                val n = minOf(src.buffer.size, 262_144L)
                src.buffer.readUtf8(n)
            }
            val title       = ogTag(html, "og:title") ?: titleTag(html) ?: return@withContext null
            val description = ogTag(html, "og:description") ?: twitterTag(html, "twitter:description") ?: ""
            val imageUrl    = ogTag(html, "og:image") ?: twitterTag(html, "twitter:image")
            val host        = Uri.parse(url).host?.removePrefix("www.") ?: url

            val preview = LinkPreview(
                url         = url,
                title       = decodeEntities(title.trim()),
                description = decodeEntities(description.trim()),
                imageUrl    = imageUrl?.let { resolveUrl(it, url) },
                domain      = host,
                isVideo     = isVideoUrl(url)
            )
            cache[url] = preview
            preview
        } catch (_: Exception) {
            cache.remove(url)   // network hiccup: allow a later retry instead of never previewing
            null
        }
    }

    /** "Tom &amp; Jerry" -> "Tom & Jerry" */
    private fun decodeEntities(t: String): String = try {
        if (android.os.Build.VERSION.SDK_INT >= 24) android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        else @Suppress("DEPRECATION") android.text.Html.fromHtml(t).toString()
    } catch (_: Exception) { t }

    // ── HTML parsing helpers ──────────────────────────────────────────────────

    private fun ogTag(html: String, property: String): String? {
        // property="og:xxx" content="..." (order 1)
        val p1 = Regex(
            """<meta[^>]+property=["']${Regex.escape(property)}["'][^>]+content=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        // content="..." property="og:xxx" (order 2)
        val p2 = Regex(
            """<meta[^>]+content=["']([^"']*)["'][^>]+property=["']${Regex.escape(property)}["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        return (p1 ?: p2)?.takeIf { it.isNotBlank() }
    }

    private fun twitterTag(html: String, name: String): String? {
        val p1 = Regex(
            """<meta[^>]+name=["']${Regex.escape(name)}["'][^>]+content=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        val p2 = Regex(
            """<meta[^>]+content=["']([^"']*)["'][^>]+name=["']${Regex.escape(name)}["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        return (p1 ?: p2)?.takeIf { it.isNotBlank() }
    }

    private fun titleTag(html: String): String? =
        Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()

    /** Resolves a potentially-relative image URL against the source page URL. */
    private fun resolveUrl(imageUrl: String, pageUrl: String): String {
        return when {
            imageUrl.startsWith("http") -> imageUrl
            imageUrl.startsWith("//")   -> "https:$imageUrl"
            imageUrl.startsWith("/")    -> {
                val uri = Uri.parse(pageUrl)
                "${uri.scheme}://${uri.host}$imageUrl"
            }
            else -> imageUrl
        }
    }

    /**
     * Linkify http(s), www., and common bare domains on [textView].
     * Safe to call repeatedly; existing URLSpans are left alone for bare-domain pass.
     */
    fun linkifyTextView(textView: android.widget.TextView) {
        android.text.util.Linkify.addLinks(textView, android.text.util.Linkify.WEB_URLS)
        val bare = java.util.regex.Pattern.compile(
            """(?i)\b(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+(?:com|org|net|edu|gov|io|co|us|uk|dev|app|info|biz|me|tv|cc|ai|gg|xyz)(?:/[^\s<>"'\]]*)?"""
        )
        android.text.util.Linkify.addLinks(
            textView,
            bare,
            "https://",
            android.text.util.Linkify.MatchFilter { s, start, end ->
                val sp = s as? android.text.Spannable ?: return@MatchFilter true
                sp.getSpans(start, end, android.text.style.URLSpan::class.java).isEmpty()
            },
            android.text.util.Linkify.TransformFilter { _, url -> normalizeUrl(url) }
        )
    }

}
