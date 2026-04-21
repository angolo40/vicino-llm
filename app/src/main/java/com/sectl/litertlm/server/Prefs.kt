package com.sectl.litertlm.server

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight wrapper around SharedPreferences for the handful of settings
 * the user expects to survive an app kill (backend choice, last port,
 * last loaded model path).
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("gemma_server_prefs", Context.MODE_PRIVATE)

    var backendKind: LiteRtLmEngine.BackendKind
        get() = when (sp.getString(KEY_BACKEND, "gpu")) {
            "cpu" -> LiteRtLmEngine.BackendKind.CPU
            else -> LiteRtLmEngine.BackendKind.GPU
        }
        set(value) {
            sp.edit().putString(
                KEY_BACKEND,
                if (value == LiteRtLmEngine.BackendKind.CPU) "cpu" else "gpu",
            ).apply()
        }

    var port: Int
        get() = sp.getInt(KEY_PORT, ServerState.DEFAULT_PORT)
        set(value) { sp.edit().putInt(KEY_PORT, value).apply() }

    var lastModelPath: String?
        get() = sp.getString(KEY_LAST_MODEL, null)
        set(value) { sp.edit().putString(KEY_LAST_MODEL, value).apply() }

    /**
     * When true, launching the app will attempt to re-start the server on
     * the saved port AND re-load the last model in the background. When
     * false, the user has to tap Start/Load manually each time.
     *
     * Explicitly NOT the same as "start on device boot" — we intentionally
     * do NOT register a BOOT_COMPLETED receiver. Starting the server that
     * early in boot risks SELinux denials, battery optimization kicking in
     * before the user sees the notification, and scoped-storage perm races.
     */
    var autoRestoreEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_RESTORE, true)
        set(value) { sp.edit().putBoolean(KEY_AUTO_RESTORE, value).apply() }

    /**
     * Circuit breaker state for auto-restore crash loops.
     *
     * When a model is too big for the device's RAM budget, the OS kills the
     * service repeatedly: START_STICKY restarts it → auto-restore reloads
     * the model → the OS kills it again. Left unchecked this drains the
     * battery, spams the notification shade, and can make the app impossible
     * to "escape" from without `adb uninstall`.
     *
     * We track unexpected restart timestamps (recent kills). If there are
     * more than [AUTO_RESTORE_KILL_LIMIT] within [AUTO_RESTORE_WINDOW_MS],
     * [autoRestoreTripped] flips to true and [ServerService] skips the
     * reload on the next restart. The user sees a banner in Settings
     * explaining how to reset it (just flip the "auto-restore" switch).
     */
    private fun restartTimestamps(): MutableList<Long> {
        val raw = sp.getString(KEY_AUTO_RESTORE_KILLS, null) ?: return mutableListOf()
        return raw.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
    }

    private fun saveRestartTimestamps(list: List<Long>) {
        sp.edit().putString(KEY_AUTO_RESTORE_KILLS, list.joinToString(",")).apply()
    }

    /** Call from onStartCommand when a null intent indicates a system restart. */
    fun recordAutoRestoreAttempt(nowMs: Long = System.currentTimeMillis()) {
        val pruned = restartTimestamps()
            .filter { it > nowMs - AUTO_RESTORE_WINDOW_MS }
            .toMutableList()
        pruned.add(nowMs)
        saveRestartTimestamps(pruned)
        if (pruned.size > AUTO_RESTORE_KILL_LIMIT) {
            autoRestoreTripped = true
        }
    }

    fun clearAutoRestoreHistory() {
        saveRestartTimestamps(emptyList())
        autoRestoreTripped = false
    }

    /** Sticky flag flipped by [recordAutoRestoreAttempt] when we detect a
     *  tight crash loop. Cleared by the user via the Settings switch. */
    var autoRestoreTripped: Boolean
        get() = sp.getBoolean(KEY_AUTO_RESTORE_TRIPPED, false)
        private set(value) { sp.edit().putBoolean(KEY_AUTO_RESTORE_TRIPPED, value).apply() }

    /** Fallback sampling defaults applied when an HTTP client omits the field. */
    var defaultTemperature: Float
        get() = sp.getFloat(KEY_DEFAULT_TEMP, 0.8f)
        set(value) { sp.edit().putFloat(KEY_DEFAULT_TEMP, value.coerceIn(0f, 2f)).apply() }

    var defaultTopP: Float
        get() = sp.getFloat(KEY_DEFAULT_TOPP, 0.95f)
        set(value) { sp.edit().putFloat(KEY_DEFAULT_TOPP, value.coerceIn(0f, 1f)).apply() }

    var defaultTopK: Int
        get() = sp.getInt(KEY_DEFAULT_TOPK, 40)
        set(value) { sp.edit().putInt(KEY_DEFAULT_TOPK, value.coerceIn(1, 200)).apply() }

    var defaultMaxTokens: Int
        get() = sp.getInt(KEY_DEFAULT_MAXTOK, 512)
        set(value) { sp.edit().putInt(KEY_DEFAULT_MAXTOK, value.coerceIn(16, 4096)).apply() }

    // Engine-level context window (max total tokens kept in KV cache).
    // Distinct from defaultMaxTokens which is per-request output budget.
    // Applied at model load; changing it requires reloading the model.
    var contextWindow: Int
        get() = sp.getInt(KEY_CONTEXT_WINDOW, 4096)
        set(value) { sp.edit().putInt(KEY_CONTEXT_WINDOW, value.coerceIn(1024, 32768)).apply() }

    fun defaultSampling(): SamplingParams = SamplingParams(
        temperature = defaultTemperature,
        topP = defaultTopP,
        topK = defaultTopK,
        maxTokens = defaultMaxTokens,
    )

    /** Hugging Face token for gated models (Gemma family). Null/blank = no auth. */
    var hfToken: String?
        get() = sp.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) { sp.edit().putString(KEY_HF_TOKEN, value?.trim().orEmpty()).apply() }

    // Server-side API key enforced on /v1 routes when set. Null/blank = open.
    var serverApiKey: String?
        get() = sp.getString(KEY_SERVER_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) { sp.edit().putString(KEY_SERVER_KEY, value?.trim().orEmpty()).apply() }

    /**
     * SearXNG base URL for the optional web-search augmentation. When set and
     * a chat request carries `web_search=true`, the server fetches the top N
     * results from this instance and prepends them as a system message before
     * inference. Null/blank disables the feature entirely (the toggle in the
     * web UI is hidden when no URL is configured).
     *
     * Privacy note: point this at a self-hosted instance if you can. Public
     * SearXNG instances can see every query you issue.
     */
    var searxngUrl: String?
        get() = sp.getString(KEY_SEARXNG_URL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val normalized = value?.trim()?.trimEnd('/').orEmpty()
            sp.edit().putString(KEY_SEARXNG_URL, normalized).apply()
        }

    /**
     * When true, the server asks the model to rewrite the user message into a
     * tight query string before hitting SearXNG. Costs ~1-2 seconds per turn
     * but typically improves result quality dramatically for conversational
     * prompts ("can you check the weather?" → "weather today Rome").
     */
    var webSearchRewriteQuery: Boolean
        get() = sp.getBoolean(KEY_WEBSEARCH_REWRITE, true)
        set(value) { sp.edit().putBoolean(KEY_WEBSEARCH_REWRITE, value).apply() }

    /** How many SearXNG results to pull and inject per request. Upper-bounded
     *  at 10 so we don't blow the context window. */
    var webSearchMaxResults: Int
        get() = sp.getInt(KEY_WEBSEARCH_MAX, 5)
        set(value) { sp.edit().putInt(KEY_WEBSEARCH_MAX, value.coerceIn(1, 10)).apply() }

    fun resetSamplingDefaults() {
        sp.edit()
            .remove(KEY_DEFAULT_TEMP)
            .remove(KEY_DEFAULT_TOPP)
            .remove(KEY_DEFAULT_TOPK)
            .remove(KEY_DEFAULT_MAXTOK)
            .apply()
    }

    companion object {
        private const val KEY_BACKEND = "backend"
        private const val KEY_PORT = "port"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_AUTO_RESTORE = "auto_restore"
        private const val KEY_DEFAULT_TEMP = "default_temp"
        private const val KEY_DEFAULT_TOPP = "default_top_p"
        private const val KEY_DEFAULT_TOPK = "default_top_k"
        private const val KEY_DEFAULT_MAXTOK = "default_max_tokens"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_SERVER_KEY = "server_api_key"
        private const val KEY_CONTEXT_WINDOW = "context_window"
        private const val KEY_SEARXNG_URL = "searxng_url"
        private const val KEY_WEBSEARCH_REWRITE = "websearch_rewrite"
        private const val KEY_WEBSEARCH_MAX = "websearch_max"
        private const val KEY_AUTO_RESTORE_KILLS = "auto_restore_kills"
        private const val KEY_AUTO_RESTORE_TRIPPED = "auto_restore_tripped"

        /** 3 kills in 5 minutes trips the circuit breaker. Tuned so a single
         *  bad model reload doesn't disable auto-restore, but an actual
         *  loop does within about a minute. */
        private const val AUTO_RESTORE_KILL_LIMIT = 3
        private const val AUTO_RESTORE_WINDOW_MS = 5L * 60L * 1000L

        // Build-time configurable links for the About tab.
        // Change these when you have live accounts. Kept as plain consts so
        // downstream rebuilds don't need a Gradle BuildConfig roundtrip.
        const val PROJECT_URL = "https://github.com/angolo40/vicino-llm"

        /**
         * Crypto tip jar. No payment processor, no KYC — users scan the QR or
         * copy the address. Order matters: first entry is the "default" one
         * shown largest in the layout.
         *
         * [uriScheme] is the wallet-friendly prefix (e.g. "monero:") embedded
         * in the QR payload so scanning wallets jump straight to "send".
         * Empty string for bare-address payloads (EVM-family).
         */
        data class CryptoTip(
            val symbol: String,
            val label: String,
            val address: String,
            val uriScheme: String,
        )

        val CRYPTO_ADDRESSES: List<CryptoTip> = listOf(
            CryptoTip(
                symbol = "XMR",
                label = "Monero",
                address = "87pT82pEGVxXD7Cv1wKrm5eD1r1J1cpBTXBtFKagAkzUQtpfK4WiuRdUB5RyPzwiNwMCK37r161JXDueNfkzYDma7rAq7ya",
                uriScheme = "monero:",
            ),
            CryptoTip(
                symbol = "LTC",
                label = "Litecoin",
                address = "ltc1qhzj2hvfpu8vtex22mzccamhr5utzdw34946a40",
                uriScheme = "litecoin:",
            ),
        )
    }
}
