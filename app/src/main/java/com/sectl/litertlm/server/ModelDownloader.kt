package com.sectl.litertlm.server

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single-slot streaming file downloader aimed at Hugging Face `.litertlm`
 * files. Designed to stay *simple*:
 *
 *   - one download at a time (matches the device's RAM budget)
 *   - coroutine-based, cancellable via [cancel]
 *   - progress observable via [progress] StateFlow — UI just collects
 *   - writes `<dest>.part` while downloading, atomic rename on complete
 *   - idempotent: if the final file already exists with a plausible size we
 *     skip; if a partial is present we resume via HTTP Range
 *
 * We don't pull in OkHttp or any other library; plain HttpURLConnection
 * handles redirects, gzip and keep-alive well enough.
 */
class ModelDownloader(private val appContext: Context) {

    sealed interface State {
        data object Idle : State
        data class Running(val id: String, val bytesDone: Long, val total: Long) : State {
            val pct: Int get() = if (total > 0) ((bytesDone * 100L) / total).toInt() else 0
        }
        data class Done(val id: String, val path: String) : State
        data class Failed(val id: String, val message: String) : State
        data class Cancelled(val id: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val progress: StateFlow<State> = _state.asStateFlow()

    private var currentJob: Job? = null
    @Volatile private var cancelFlag = false

    /**
     * Kicks off a download. Returns immediately; observe [progress] for
     * status. Calling [start] while another download is running cancels the
     * previous one first.
     */
    fun start(
        scope: CoroutineScope,
        repo: String,
        filename: String,
        hfToken: String?,
    ) {
        currentJob?.cancel()
        cancelFlag = false
        currentJob = scope.launch(Dispatchers.IO) {
            runCatching { download(repo, filename, hfToken) }
                .onFailure {
                    if (cancelFlag) _state.value = State.Cancelled(filename)
                    else _state.value = State.Failed(filename, it.message ?: it.javaClass.simpleName)
                    Log.w(TAG, "download failed: $filename", it)
                }
        }
    }

    fun cancel() {
        cancelFlag = true
        currentJob?.cancel()
    }

    private suspend fun download(repo: String, filename: String, hfToken: String?) = withContext(Dispatchers.IO) {
        val dest = destinationFile(filename)
        if (dest.exists() && dest.length() > MIN_PLAUSIBLE_SIZE) {
            _state.value = State.Done(filename, dest.absolutePath)
            return@withContext
        }

        val part = File(dest.parentFile, "${dest.name}.part")
        val existingBytes = if (part.exists()) part.length() else 0L
        val url = URL("https://huggingface.co/$repo/resolve/main/$filename?download=true")

        _state.value = State.Running(filename, existingBytes, 0L)

        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            if (!hfToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $hfToken")
            if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
            // User-Agent — HF CDN sometimes throttles unknown UAs.
            setRequestProperty("User-Agent", "VicinoLLM/1.0 (+android)")
        }

        try {
            val code = conn.responseCode
            if (code !in setOf(200, 206)) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw RuntimeException("HTTP $code: ${body.take(200)}")
            }

            // When resuming, Content-Length only covers the remaining part; add back the offset.
            val remaining = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            val total = if (code == 206 && remaining > 0) existingBytes + remaining
                        else if (remaining > 0) remaining
                        else 0L

            _state.value = State.Running(filename, existingBytes, total)

            conn.inputStream.use { input ->
                FileOutputStream(part, /* append = */ code == 206).use { output ->
                    val buf = ByteArray(128 * 1024)
                    var done = existingBytes
                    var lastEmitMs = System.currentTimeMillis()
                    while (true) {
                        if (cancelFlag) throw InterruptedException("user cancelled")
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs > 250) {
                            _state.value = State.Running(filename, done, total)
                            lastEmitMs = now
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            // Guarantee disconnect even on HTTP error, cancel, or IO
            // exception mid-transfer. Previously an errored 4xx/5xx left
            // the pooled socket open until GC — over many retries this
            // starved us of file descriptors.
            runCatching { conn.disconnect() }
        }

        if (!part.renameTo(dest)) {
            throw RuntimeException("rename ${part.name} -> ${dest.name} failed")
        }

        _state.value = State.Done(filename, dest.absolutePath)
        Log.i(TAG, "downloaded ${dest.absolutePath} (${dest.length()} bytes)")
    }

    /**
     * Where the file lands. Prefer `/sdcard/Models/` if we have
     * MANAGE_EXTERNAL_STORAGE so the file survives app uninstall, otherwise
     * fall back to the app-private external dir (always writable).
     */
    private fun destinationFile(filename: String): File {
        val publicModels = File(Environment.getExternalStorageDirectory(), "Models")
        val canUsePublic = Environment.isExternalStorageManager() && (publicModels.exists() || publicModels.mkdirs())
        val dir = if (canUsePublic) publicModels else appContext.getExternalFilesDir(null)!!
        return File(dir, filename)
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val MIN_PLAUSIBLE_SIZE = 100L * 1024L * 1024L  // 100 MB, less = corrupt
    }
}
