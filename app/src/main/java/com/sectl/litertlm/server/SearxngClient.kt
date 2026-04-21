package com.sectl.litertlm.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin client for a self-hosted (or any) SearXNG instance. The app calls
 * `search(query)` and gets back a handful of title/snippet/url tuples
 * that [HttpServer] prepends to the chat context before inference.
 *
 * Deliberate design choices:
 *   - HttpURLConnection instead of OkHttp so we don't drag another
 *     dependency into the APK for ~30 lines of code.
 *   - Aggressive 5-second total timeout: if SearXNG is slow or down we
 *     degrade to an un-augmented reply rather than blocking the whole
 *     chat turn. The user sees the answer faster and the log records
 *     the miss.
 *   - Tiny LRU cache (50 entries, 5-min TTL) so the "user edits prompt
 *     and resubmits" loop doesn't flood SearXNG.
 *   - No persistent cookies / session — each query is independent.
 */
class SearxngClient(private val baseUrl: String) {

    @Serializable
    data class Result(
        val title: String,
        val snippet: String,
        val url: String,
    )

    @Serializable
    private data class SxResult(
        val title: String = "",
        val url: String = "",
        val content: String = "",
    )

    @Serializable
    private data class SxResponse(
        val results: List<SxResult> = emptyList(),
    )

    /**
     * Plain search — SEO snippets only. Used by the web-UI sources box so
     * links stay fast; inference augmentation uses [searchAndEnrich] instead.
     */
    suspend fun search(query: String, max: Int = 5): List<Result> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        cache.lookup(trimmed)?.let { return it.take(max) }

        val results = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            runCatching { fetch(trimmed) }.getOrElse {
                Log.w(TAG, "SearXNG fetch failed for '$trimmed': ${it.message}")
                emptyList()
            }
        } ?: run {
            Log.w(TAG, "SearXNG fetch for '$trimmed' timed out after ${SEARCH_TIMEOUT_MS}ms")
            emptyList()
        }

        if (results.isNotEmpty()) cache.store(trimmed, results)
        return results.take(max)
    }

    /**
     * Search + fetch & extract: hits SearXNG, then fetches the top
     * [deepCount] pages in parallel and replaces their SEO snippet with
     * the readable body text. That's the difference between "here are
     * five sites ABOUT weather" and "here is today's weather in Lugano".
     *
     * Each fetch is capped at ~3s so a slow/blocked page doesn't block the
     * whole turn. If a page fails we keep its SEO snippet rather than
     * dropping the result.
     */
    suspend fun searchAndEnrich(
        query: String,
        max: Int = 5,
        deepCount: Int = 2,
        maxCharsPerPage: Int = 2000,
    ): List<Result> {
        val shallow = search(query, max)
        if (shallow.isEmpty() || deepCount <= 0) return shallow

        val top = shallow.take(deepCount)
        val tail = shallow.drop(deepCount)

        val enrichedTop = coroutineScope {
            top.map { r ->
                async(Dispatchers.IO) {
                    val body = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                        runCatching { extractReadableText(r.url, maxCharsPerPage) }.getOrNull()
                    }
                    if (body.isNullOrBlank()) r else r.copy(snippet = body)
                }
            }.awaitAll()
        }
        return enrichedTop + tail
    }

    private fun extractReadableText(pageUrl: String, maxChars: Int): String {
        val doc = Jsoup.connect(pageUrl)
            .userAgent(FETCH_USER_AGENT)
            .timeout(FETCH_TIMEOUT_MS.toInt())
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .get()
        // Strip the obviously noisy parts before taking the text — otherwise
        // the "snippet" is dominated by menus, cookie banners, footers.
        doc.select("script, style, nav, header, footer, aside, noscript, form, iframe, .cookie, .advertisement, .ad, [role=navigation]")
            .forEach { it.remove() }
        val main = doc.select("main, article, [role=main], #content, .content").firstOrNull()
        val raw = (main ?: doc.body()).text()
        // Collapse runs of whitespace (Jsoup already did part of this but
        // the result can still have multi-space gaps around removed nodes).
        val cleaned = raw.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length <= maxChars) cleaned else cleaned.substring(0, maxChars) + "…"
    }

    /** Ping the instance — used by Settings to sanity-check the URL. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$baseUrl/healthz").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    private suspend fun fetch(query: String): List<Result> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // `format=json` is disabled by default on many instances — instance
        // admins have to set `search.formats: [html, json]` in settings.yml.
        // If it 403s we surface an empty list; the user sees a hint in the
        // Settings "Test connection" button rather than here.
        val url = URL("$baseUrl/search?q=$encoded&format=json&safesearch=0&language=auto")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 4_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "SearXNG ${conn.responseCode} for $url")
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = LENIENT_JSON.decodeFromString<SxResponse>(body)
            parsed.results.map {
                Result(
                    title = it.title.ifBlank { it.url },
                    snippet = it.content.trim(),
                    url = it.url,
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SearxngClient"
        private const val USER_AGENT = "VicinoLLM/1.0 (+local; Ktor)"
        private const val SEARCH_TIMEOUT_MS = 5_000L

        /** Per-page timeout for deep fetches. 2 pages × 3s can still parallelise
         *  under the chat-turn total budget. */
        private const val FETCH_TIMEOUT_MS = 3_000L

        // Some sites block obvious bot UAs. A modern Firefox mobile string
        // is the pragmatic trade-off — "passable" to most content sites,
        // still honest enough that we aren't pretending to be a person.
        private const val FETCH_USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

        // kotlinx.serialization: ignore SearXNG fields we don't map.
        private val LENIENT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        // Tiny bounded LRU. Works fine for the 1-user-per-phone workload we
        // target; a production system would use a real cache lib.
        private val cache = object : LinkedHashMap<String, TimedEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, TimedEntry>?): Boolean = size > 50
        }

        private data class TimedEntry(val results: List<Result>, val expiresAt: Long)

        private fun LinkedHashMap<String, TimedEntry>.lookup(key: String): List<Result>? {
            val e = this[key] ?: return null
            if (e.expiresAt < System.currentTimeMillis()) {
                remove(key); return null
            }
            return e.results
        }

        private fun LinkedHashMap<String, TimedEntry>.store(key: String, value: List<Result>) {
            put(key, TimedEntry(value, System.currentTimeMillis() + 5 * 60 * 1000L))
        }

        /** Render results as a compact system-prompt block. */
        fun formatForPrompt(query: String, results: List<Result>): String = buildString {
            append("Recent web results for \"")
            append(query)
            append("\":\n\n")
            results.forEachIndexed { i, r ->
                append("[")
                append(i + 1)
                append("] ")
                append(r.title)
                append("\n")
                if (r.snippet.isNotBlank()) {
                    append(r.snippet.take(400))
                    append("\n")
                }
                append("Source: ")
                append(r.url)
                append("\n\n")
            }
            append("When citing information above, reference sources as [1], [2], etc.")
        }
    }
}
