package com.sectl.litertlm.server

import android.util.Base64
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves OpenAI-style media URLs (`data:image/png;base64,…` or
 * `http(s)://…`) into raw byte arrays. Runs on the inference thread so
 * the HTTP download is blocking by design; callers already wrap with
 * Dispatchers.IO.
 *
 * Size cap keeps a rogue / huge image from blowing through RAM on the
 * phone — 16 MB is generous for still images up to ~4K resolution.
 */
object MediaLoader {
    private const val TAG = "MediaLoader"
    private const val MAX_BYTES = 16 * 1024 * 1024  // 16 MB

    fun loadDataUrl(url: String): ByteArray? {
        val prefix = "data:"
        if (!url.startsWith(prefix)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(prefix.length, comma)
        val body = url.substring(comma + 1)
        return if (header.contains("base64", ignoreCase = true)) {
            runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull()
        } else {
            // URL-encoded plain data — decode percent-escapes.
            runCatching {
                java.net.URLDecoder.decode(body, Charsets.UTF_8.name()).toByteArray(Charsets.ISO_8859_1)
            }.getOrNull()
        }
    }

    fun loadBase64(base64: String): ByteArray? =
        runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()

    fun loadHttp(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VicinoLLM/1.0")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "http ${conn.responseCode} fetching $url")
                null
            } else {
                conn.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetch failed: $url", t)
            null
        } finally {
            // Always close the connection — Android's HttpURLConnection holds
            // a pooled socket even on non-2xx responses and we were leaking
            // file descriptors on every failure path.
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Try all supported URL schemes and return the raw bytes, or null if
     * nothing worked. Never throws — callers treat null as "media skipped".
     */
    fun resolveImageUrl(url: String): ByteArray? = when {
        url.startsWith("data:") -> loadDataUrl(url)
        url.startsWith("http://") || url.startsWith("https://") -> loadHttp(url)
        else -> null
    }

    private fun java.io.InputStream.readNBytesCompat(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            if (total + n > maxBytes) error("media exceeds $maxBytes bytes")
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
