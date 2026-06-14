package com.sectl.litertlm.server

import android.content.res.AssetManager
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Ktor (CIO) HTTP server exposing the OpenAI-compatible API.
 *
 * v2 adds:
 *   - SSE streaming when request.stream == true
 *   - multi-model routing via request.model → [EngineHolder.resolve]
 *   - /v1/embeddings (501 Not Implemented — documented in README)
 *   - sampling defaults read from [samplingDefaults] when request omits a field
 */
class HttpServer(
    private val port: Int = ServerState.DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    private val samplingDefaults: () -> SamplingParams = { SamplingParams() },
    /** Android AssetManager, used to serve the bundled web UI. Null = no UI. */
    private val assets: AssetManager? = null,
    /** Lists all .litertlm / .task files available on device — web UI calls this. */
    private val availableModels: () -> List<ModelRepository.ModelFile> = { emptyList() },
    /**
     * Triggers a model load from the web UI. The service wires this to its
     * LOAD_MODEL intent so the UI thread doesn't block the request.
     * Returns true if the load was scheduled, false if invalid / ignored.
     */
    private val loadModel: (absolutePath: String, backendKind: String) -> Boolean = { _, _ -> false },
    /** Unloads a specific model by id (filename). */
    private val unloadModel: suspend (id: String) -> Unit = {},
    /** Current server API key. Null/blank = no auth. Polled per request so
     *  changes from the UI take effect without restarting the server. */
    private val serverApiKey: () -> String? = { null },
    /** SearXNG base URL (null/blank = web-search feature disabled). Polled
     *  per request so the user can add/change it from Settings without a
     *  server restart. */
    private val searxngUrl: () -> String? = { null },
    /** Whether to ask the model to rewrite the user message into a query
     *  before hitting SearXNG. Read per request from Prefs. */
    private val webSearchRewriteQuery: () -> Boolean = { true },
    /** How many SearXNG results to inject. Read per request. */
    private val webSearchMaxResults: () -> Int = { 5 },
) {
    @Volatile private var engine: io.ktor.server.engine.EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        if (engine != null) {
            Log.w(TAG, "start() called while engine is already running; ignoring")
            return
        }

        val server = embeddedServer(CIO, port = port, host = host) {
            install(ContentNegotiation) {
                json(SHARED_JSON)
            }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "unhandled exception in handler", cause)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        OpenAiError.of(
                            message = cause.message ?: "internal server error",
                            type = "server_error",
                            code = "internal_error",
                        ),
                    )
                }
            }

            routing {
                // ---- Web UI (served from app/src/main/assets/webui/) ----
                if (assets != null) {
                    get("/") { serveAsset(call, assets, "webui/index.html") }
                    get("/assets/{path...}") {
                        val rel = call.parameters.getAll("path")?.joinToString("/") ?: ""
                        // Guard against any '..' shenanigans — `rel` comes from the URL.
                        if (rel.contains("..") || rel.startsWith("/")) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@get
                        }
                        serveAsset(call, assets, "webui/assets/$rel")
                    }
                }

                get("/health") {
                    ServerState.incrementRequestCount()
                    val defaultEng = EngineHolder.current
                    val s = ServerState.state.value
                    val modelName = defaultEng?.modelId ?: "none"
                    val backend = defaultEng?.backend ?: "none"
                    call.respond(
                        HealthResponse(
                            status = "ok",
                            model = modelName,
                            backend = backend,
                            loading = s.loading,
                            loadedModels = EngineHolder.all().map { it.modelId },
                        ),
                    )
                    RequestLog.record("/health", 0, 0, 0, 200)
                }

                get("/v1/models") {
                    if (!requireAuth(call)) return@get
                    ServerState.incrementRequestCount()
                    val data = EngineHolder.all().map { ModelEntry(id = it.modelId) }
                    call.respond(ModelsListResponse(data = data))
                    RequestLog.record("/v1/models", 0, 0, 0, 200)
                }

                post("/v1/chat/completions") {
                    if (!requireAuth(call)) return@post
                    ServerState.incrementRequestCount()
                    val startMs = System.currentTimeMillis()
                    val req = try {
                        call.receive<ChatCompletionRequest>()
                    } catch (t: Throwable) {
                        call.respond(HttpStatusCode.BadRequest, badJson(t))
                        RequestLog.record("/v1/chat/completions", 0, 0, System.currentTimeMillis() - startMs, 400)
                        return@post
                    }

                    val inf = EngineHolder.resolve(req.model)
                    if (inf == null || !inf.isLoaded) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            if (req.model.isNullOrBlank()) NO_MODEL_ERROR else noSuchModelError(req.model),
                        )
                        RequestLog.record("/v1/chat/completions", 0, 0, System.currentTimeMillis() - startMs, 503)
                        return@post
                    }

                    if (req.messages.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, badField("messages[] must be non-empty"))
                        RequestLog.record("/v1/chat/completions", 0, 0, System.currentTimeMillis() - startMs, 400)
                        return@post
                    }

                    val params = req.toSamplingParams(samplingDefaults())
                    val modelLabel = inf.modelId

                    // Optional web-search augmentation. Runs before the
                    // multimodal-vs-text split so both code paths benefit;
                    // failures here degrade silently (we inference without
                    // the injected context rather than surfacing the error).
                    val augmentedMessages =
                        maybeAugmentWithWebSearch(req.messages, req.webSearch, inf, params)

                    // Decide text-only vs multimodal path. We stick to the
                    // multimodal API whenever any message carries a Parts
                    // payload, so images/audio don't get lost in the template.
                    val isMultimodal = augmentedMessages.any { it.content is MessageContent.Parts }
                    val multimodalParts = if (isMultimodal)
                        GemmaChatTemplate.formatMultimodal(augmentedMessages) else emptyList()
                    val prompt = if (isMultimodal) "" else GemmaChatTemplate.format(augmentedMessages)

                    // Server-side enforcement of the engine's image budget.
                    // EngineConfig is built with maxNumImages=4; sending more
                    // than that crashes the native preprocessor. A malicious
                    // or buggy client can easily exceed it, so we reject
                    // before inference kicks off.
                    val imageCount = multimodalParts.count { it is MultimodalPart.Image }
                    if (imageCount > MAX_IMAGES_PER_REQUEST) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            badField("at most $MAX_IMAGES_PER_REQUEST images per request (received $imageCount)"),
                        )
                        RequestLog.record("/v1/chat/completions", 0, 0, System.currentTimeMillis() - startMs, 400)
                        return@post
                    }

                    if (req.stream) {
                        if (isMultimodal) {
                            // SDK streaming works with Contents too, but our
                            // internal streamChat takes a String — route the
                            // multimodal case through a dedicated helper.
                            streamChatMultimodal(
                                call = call,
                                engine = inf,
                                modelLabel = modelLabel,
                                parts = multimodalParts,
                                params = params,
                                startMs = startMs,
                            )
                        } else {
                            streamChat(
                                call = call,
                                engine = inf,
                                modelLabel = modelLabel,
                                prompt = prompt,
                                params = params,
                                startMs = startMs,
                                endpoint = "/v1/chat/completions",
                            )
                        }
                        return@post
                    }

                    val result = if (isMultimodal) {
                        withContext(Dispatchers.IO) { inf.inferMultimodal(multimodalParts, params) }
                    } else {
                        withContext(Dispatchers.IO) { inf.infer(prompt, params) }
                    }
                    val response = ChatCompletionResponse(
                        id = "chatcmpl-${UUID.randomUUID()}",
                        created = System.currentTimeMillis() / 1000,
                        model = modelLabel,
                        choices = listOf(
                            ChatChoice(
                                message = textMessage("assistant", result.text),
                                finishReason = "stop",
                            ),
                        ),
                        usage = Usage(
                            promptTokens = result.promptTokens,
                            completionTokens = result.completionTokens,
                            totalTokens = result.totalTokens,
                        ),
                    )
                    call.respond(response)
                    RequestLog.record(
                        endpoint = "/v1/chat/completions",
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        latencyMs = result.latencyMs,
                        statusCode = 200,
                    )
                }

                post("/v1/completions") {
                    if (!requireAuth(call)) return@post
                    ServerState.incrementRequestCount()
                    val startMs = System.currentTimeMillis()
                    val req = try {
                        call.receive<CompletionRequest>()
                    } catch (t: Throwable) {
                        call.respond(HttpStatusCode.BadRequest, badJson(t))
                        RequestLog.record("/v1/completions", 0, 0, System.currentTimeMillis() - startMs, 400)
                        return@post
                    }

                    val inf = EngineHolder.resolve(req.model)
                    if (inf == null || !inf.isLoaded) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            if (req.model.isNullOrBlank()) NO_MODEL_ERROR else noSuchModelError(req.model),
                        )
                        RequestLog.record("/v1/completions", 0, 0, System.currentTimeMillis() - startMs, 503)
                        return@post
                    }
                    if (req.prompt.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, badField("prompt must be non-empty"))
                        RequestLog.record("/v1/completions", 0, 0, System.currentTimeMillis() - startMs, 400)
                        return@post
                    }
                    val params = req.toSamplingParams(samplingDefaults())
                    val modelLabel = inf.modelId

                    if (req.stream) {
                        streamLegacy(
                            call = call,
                            engine = inf,
                            modelLabel = modelLabel,
                            prompt = req.prompt,
                            params = params,
                            startMs = startMs,
                        )
                        return@post
                    }

                    val result = withContext(Dispatchers.IO) { inf.infer(req.prompt, params) }
                    val response = CompletionResponse(
                        id = "cmpl-${UUID.randomUUID()}",
                        created = System.currentTimeMillis() / 1000,
                        model = modelLabel,
                        choices = listOf(
                            CompletionChoice(text = result.text, finishReason = "stop"),
                        ),
                        usage = Usage(
                            promptTokens = result.promptTokens,
                            completionTokens = result.completionTokens,
                            totalTokens = result.totalTokens,
                        ),
                    )
                    call.respond(response)
                    RequestLog.record(
                        endpoint = "/v1/completions",
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        latencyMs = result.latencyMs,
                        statusCode = 200,
                    )
                }

                // ---- Model management (web UI → server) ----

                // Extended listing: all files on disk + which ones are currently loaded.
                // Not OpenAI-standard; the web UI uses this to render the Models pane.
                get("/v1/models/available") {
                    if (!requireAuth(call)) return@get
                    ServerState.incrementRequestCount()
                    val files = availableModels()
                    val loadedIds = EngineHolder.all().map { it.modelId }.toSet()
                    val entries = files.map { f ->
                        AvailableModelEntry(
                            id = f.name,
                            path = f.absolutePath,
                            sizeMib = f.sizeMiB,
                            source = when (f.source) {
                                ModelRepository.ModelFile.Source.PUBLIC_MODELS -> "sdcard/Models"
                                ModelRepository.ModelFile.Source.APP_EXTERNAL -> "app-external"
                            },
                            loaded = f.name in loadedIds,
                        )
                    }
                    call.respond(AvailableModelsResponse(data = entries))
                    RequestLog.record("/v1/models/available", 0, 0, 0, 200)
                }

                // Kick off a load. Returns 202 Accepted — the caller should
                // poll /health or /v1/models to see when the engine shows up.
                post("/v1/models/load") {
                    if (!requireAuth(call)) return@post
                    ServerState.incrementRequestCount()
                    val req = try {
                        call.receive<LoadModelRequest>()
                    } catch (t: Throwable) {
                        call.respond(HttpStatusCode.BadRequest, badJson(t))
                        RequestLog.record("/v1/models/load", 0, 0, 0, 400)
                        return@post
                    }
                    val backend = (req.backend ?: "gpu").lowercase()
                    if (backend !in setOf("gpu", "cpu")) {
                        call.respond(HttpStatusCode.BadRequest, badField("backend must be 'gpu' or 'cpu'"))
                        RequestLog.record("/v1/models/load", 0, 0, 0, 400)
                        return@post
                    }
                    // Resolve id → absolute path via availableModels list.
                    val file = availableModels().firstOrNull { it.name == req.id }
                    if (file == null) {
                        call.respond(HttpStatusCode.NotFound, noSuchModelError(req.id))
                        RequestLog.record("/v1/models/load", 0, 0, 0, 404)
                        return@post
                    }
                    val ok = loadModel(file.absolutePath, backend)
                    if (!ok) {
                        call.respond(HttpStatusCode.InternalServerError, OpenAiError.of(
                            message = "Unable to schedule load",
                            type = "server_error",
                            code = "load_failed",
                        ))
                        RequestLog.record("/v1/models/load", 0, 0, 0, 500)
                        return@post
                    }
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to "loading", "id" to req.id, "backend" to backend))
                    RequestLog.record("/v1/models/load", 0, 0, 0, 202)
                }

                // Unload a specific model by id.
                delete("/v1/models/{id}") {
                    if (!requireAuth(call)) return@delete
                    ServerState.incrementRequestCount()
                    val id = call.parameters["id"].orEmpty()
                    if (EngineHolder.byId(id) == null) {
                        call.respond(HttpStatusCode.NotFound, noSuchModelError(id))
                        RequestLog.record("/v1/models/$id", 0, 0, 0, 404)
                        return@delete
                    }
                    unloadModel(id)
                    call.respond(mapOf("status" to "unloaded", "id" to id))
                    RequestLog.record("/v1/models/$id", 0, 0, 0, 200)
                }

                post("/v1/embeddings") {
                    if (!requireAuth(call)) return@post
                    // LiteRT-LM 0.10 does not expose hidden states / logits
                    // from its public Kotlin API. Returning OpenAI-style 501
                    // with a clear message so clients can gracefully fall
                    // back to another provider for embedding work.
                    ServerState.incrementRequestCount()
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        OpenAiError.of(
                            message = "Embeddings not supported by LiteRT-LM ${BuildConfigLike.LITERTLM_VERSION}",
                            type = "server_error",
                            code = "embeddings_not_supported",
                        ),
                    )
                    RequestLog.record("/v1/embeddings", 0, 0, 0, 501)
                }

                // Non-OpenAI: expose the SearXNG client for the web UI to
                // surface "sources" next to a reply and for Settings to run
                // a connectivity check. Returns 503 when no SearXNG URL is
                // configured, otherwise the raw result list.
                get("/v1/search") {
                    if (!requireAuth(call)) return@get
                    ServerState.incrementRequestCount()
                    val startMs = System.currentTimeMillis()
                    val base = searxngUrl()?.takeIf { it.isNotBlank() }
                    if (base == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, WebSearchError("searxng_not_configured"))
                        RequestLog.record("/v1/search", 0, 0, 0, 503)
                        return@get
                    }
                    val q = call.request.queryParameters["q"]?.trim().orEmpty()
                    if (q.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, WebSearchError("missing_query"))
                        RequestLog.record("/v1/search", 0, 0, 0, 400)
                        return@get
                    }
                    val n = call.request.queryParameters["n"]?.toIntOrNull()?.coerceIn(1, 10)
                        ?: webSearchMaxResults()
                    val results = SearxngClient(base).search(q, n)
                    call.respond(WebSearchResponse(query = q, results = results))
                    RequestLog.record("/v1/search", 0, 0, System.currentTimeMillis() - startMs, 200)
                }
            }
        }

        server.start(wait = false)
        engine = server
        Log.i(TAG, "Ktor server started on $host:$port")
    }

    fun stop() {
        engine?.let {
            Log.i(TAG, "stopping Ktor server")
            it.stop(500, 2000)
        }
        engine = null
    }

    /**
     * Inline bearer-token check. Returns true if the request passes; if it
     * doesn't, the handler has already responded with 401 and the caller
     * should `return@post` / `return@get` immediately.
     *
     * Deliberately NOT a Ktor plugin — one function call at the top of each
     * /v1/ route is clearer than wiring `install(Authentication)` with a
     * custom provider, and the check is cheap enough to never matter.
     */
    private suspend fun requireAuth(call: ApplicationCall): Boolean {
        val expected = serverApiKey().orEmpty()
        if (expected.isBlank()) return true  // open server

        // The bundled web UI lives at the same origin and is trusted by design
        // — the user already runs their own phone-hosted server. It tags its
        // fetches with a sentinel header so we don't force them through the
        // same bearer check applied to external clients like OpenWebUI /
        // OpenClaw / the Python SDK.
        val uiHeader = call.request.headers["X-Vicino-UI"]
        val sameOriginReferer = call.request.headers["Referer"]?.let {
            val host = call.request.host()
            val port = call.request.port()
            // Accept any referer whose authority matches this request's host,
            // regardless of scheme — Android's WebView / Chrome may sanitise.
            it.startsWith("http://$host:$port/") || it.startsWith("https://$host:$port/")
        } ?: false
        if (uiHeader == "1" && sameOriginReferer) return true

        val header = call.request.headers["Authorization"].orEmpty()
        val token = when {
            header.startsWith("Bearer ", ignoreCase = true) -> header.substring(7).trim()
            header.isNotBlank() -> header.trim()
            else -> call.request.queryParameters["api_key"].orEmpty()
        }
        // Constant-time comparison — plain `==` on String short-circuits on
        // the first mismatched char, which leaks the length and each
        // character of `expected` via response-time side channels. On a LAN
        // this is mostly theoretical, but it's cheap to do right.
        if (tokensEqual(token, expected)) return true
        call.respond(
            HttpStatusCode.Unauthorized,
            OpenAiError.of(
                message = "Invalid API key. Set the `Authorization: Bearer <key>` header.",
                type = "invalid_request_error",
                code = "invalid_api_key",
            ),
        )
        return false
    }

    // ---------- Web-search augmentation ---------------------------------

    /**
     * When [enabled] is true AND [searxngUrl] has a value, pull the latest
     * SearXNG results for the last user message and prepend them as a
     * system message. Returns the list unchanged otherwise.
     *
     * Uses the engine itself for the optional query-rewriting step — cheap
     * because we cap it at 32 output tokens and run it synchronously on
     * the same inference mutex.
     */
    private suspend fun maybeAugmentWithWebSearch(
        messages: List<ChatMessage>,
        enabled: Boolean,
        engine: InferenceEngine,
        params: SamplingParams,
    ): List<ChatMessage> {
        if (!enabled) return messages
        val base = searxngUrl()?.takeIf { it.isNotBlank() } ?: return messages
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return messages
        val userText = extractPlainText(lastUser.content).ifBlank { return messages }

        val query = if (webSearchRewriteQuery()) {
            runCatching { rewriteQuery(engine, userText) }.getOrDefault(userText)
        } else {
            userText
        }

        // Use the enriched flavour for inference augmentation — the top 2
        // results get their SEO snippet replaced with real page text so the
        // model can actually answer "what is X today?" rather than "here
        // are five websites about X". Costs +3-5 s per turn.
        val results = runCatching {
            SearxngClient(base).searchAndEnrich(query, webSearchMaxResults())
        }.getOrDefault(emptyList())

        if (results.isEmpty()) {
            Log.i(TAG, "web_search: no results for '$query' — inference will run un-augmented")
            return messages
        }

        val injected = ChatMessage(
            role = "system",
            content = MessageContent.Text(SearxngClient.formatForPrompt(query, results)),
        )
        // Insert the search context just before the final user turn so the
        // model reads it as fresh "browsing output" right before answering.
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        return messages.toMutableList().apply { add(lastUserIndex, injected) }
    }

    private suspend fun rewriteQuery(engine: InferenceEngine, userText: String): String {
        val prompt = """
            You are a search-query optimizer. Rewrite the user message below into 3–6 keywords suitable for a web search engine. Return ONLY the keywords, no punctuation, no quotes, no explanation.

            User message: $userText
            Keywords:
        """.trimIndent()
        val tight = SamplingParams(temperature = 0.2f, topP = 0.9f, topK = 20, maxTokens = 32)
        val out = withContext(Dispatchers.IO) { engine.infer(prompt, tight) }
        val first = out.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        // Guard against the model echoing the prompt back or returning empty.
        return first.takeIf { it.isNotBlank() && it.length < 200 } ?: userText
    }

    private fun extractPlainText(content: MessageContent): String = when (content) {
        is MessageContent.Text -> content.text
        is MessageContent.Parts ->
            content.parts.filterIsInstance<MessageContent.Part.Text>()
                .joinToString(" ") { it.text }
    }

    // ---------- SSE helpers ---------------------------------------------

    private suspend fun streamChat(
        call: io.ktor.server.application.ApplicationCall,
        engine: InferenceEngine,
        modelLabel: String,
        prompt: String,
        params: SamplingParams,
        startMs: Long,
        endpoint: String,
    ) {
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        var promptTok = 0
        var completionTok = 0
        var latencyMs = 0L

        call.respondTextWriter(contentType = SSE_CT) {
            // Opening role chunk, per OpenAI contract.
            writeSse(encodeDelta(id, created, modelLabel, role = "assistant", content = null, finishReason = null))
            flush()

            // If the client disconnects mid-stream (tab closed, network drop),
            // `flush()` throws IOException. We convert that into a flow
            // cancellation so the underlying inference stops rolling tokens
            // into a dead socket — otherwise the inferenceMutex stays held
            // while the GPU keeps running and no other request can proceed.
            withContext(Dispatchers.IO) {
                try {
                    engine.inferStream(prompt, params).collect { chunk ->
                        if (chunk.isFinal) {
                            promptTok = chunk.promptTokens
                            completionTok = chunk.completionTokens
                            latencyMs = chunk.latencyMs
                        } else if (chunk.text.isNotEmpty()) {
                            writeSse(encodeDelta(id, created, modelLabel, role = null, content = chunk.text, finishReason = null))
                            flush()
                        }
                    }
                } catch (io: java.io.IOException) {
                    Log.i(TAG, "client disconnected mid-stream; cancelling inference")
                    currentCoroutineContext().cancel()
                }
            }

            runCatching {
                writeSse(encodeDelta(id, created, modelLabel, role = null, content = null, finishReason = "stop"))
                write("data: [DONE]\n\n")
                flush()
            }
        }
        if (latencyMs == 0L) latencyMs = System.currentTimeMillis() - startMs
        RequestLog.record(endpoint, promptTok, completionTok, latencyMs, 200)
    }

    /**
     * Multimodal streaming path. For now we run the inference synchronously
     * and emit the full reply as a single SSE chunk — the SDK doesn't
     * currently expose a multimodal streaming variant, and chunking after
     * the fact would be cosmetic-only.
     */
    private suspend fun streamChatMultimodal(
        call: ApplicationCall,
        engine: InferenceEngine,
        modelLabel: String,
        parts: List<MultimodalPart>,
        params: SamplingParams,
        startMs: Long,
    ) {
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        var promptTok = 0
        var completionTok = 0
        var latencyMs = 0L

        call.respondTextWriter(contentType = SSE_CT) {
            writeSse(encodeDelta(id, created, modelLabel, role = "assistant", content = null, finishReason = null))
            flush()
            withContext(Dispatchers.IO) {
                try {
                    engine.inferMultimodalStream(parts, params).collect { chunk ->
                        if (chunk.isFinal) {
                            promptTok = chunk.promptTokens
                            completionTok = chunk.completionTokens
                            latencyMs = chunk.latencyMs
                        } else if (chunk.text.isNotEmpty()) {
                            writeSse(encodeDelta(id, created, modelLabel, role = null, content = chunk.text, finishReason = null))
                            flush()
                        }
                    }
                } catch (io: java.io.IOException) {
                    Log.i(TAG, "client disconnected mid-stream (multimodal); cancelling inference")
                    currentCoroutineContext().cancel()
                }
            }
            runCatching {
                writeSse(encodeDelta(id, created, modelLabel, role = null, content = null, finishReason = "stop"))
                write("data: [DONE]\n\n")
                flush()
            }
        }
        if (latencyMs == 0L) latencyMs = System.currentTimeMillis() - startMs
        RequestLog.record(
            endpoint = "/v1/chat/completions",
            promptTokens = promptTok,
            completionTokens = completionTok,
            latencyMs = latencyMs,
            statusCode = 200,
        )
    }

    private suspend fun streamLegacy(
        call: io.ktor.server.application.ApplicationCall,
        engine: InferenceEngine,
        modelLabel: String,
        prompt: String,
        params: SamplingParams,
        startMs: Long,
    ) {
        val id = "cmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        var promptTok = 0
        var completionTok = 0
        var latencyMs = 0L

        call.respondTextWriter(contentType = SSE_CT) {
            withContext(Dispatchers.IO) {
                engine.inferStream(prompt, params).collect { chunk ->
                    if (chunk.isFinal) {
                        promptTok = chunk.promptTokens
                        completionTok = chunk.completionTokens
                        latencyMs = chunk.latencyMs
                    } else if (chunk.text.isNotEmpty()) {
                        writeSse(encodeLegacyDelta(id, created, modelLabel, chunk.text, null))
                        flush()
                    }
                }
            }
            writeSse(encodeLegacyDelta(id, created, modelLabel, "", "stop"))
            write("data: [DONE]\n\n")
            flush()
        }
        if (latencyMs == 0L) latencyMs = System.currentTimeMillis() - startMs
        RequestLog.record("/v1/completions", promptTok, completionTok, latencyMs, 200)
    }

    private fun encodeDelta(
        id: String,
        created: Long,
        model: String,
        role: String?,
        content: String?,
        finishReason: String?,
    ): String {
        val chunk = ChatStreamChunk(
            id = id,
            created = created,
            model = model,
            choices = listOf(
                ChatStreamChoice(
                    index = 0,
                    delta = ChatStreamDelta(role = role, content = content),
                    finishReason = finishReason,
                ),
            ),
        )
        return SHARED_JSON.encodeToString(ChatStreamChunk.serializer(), chunk)
    }

    private fun encodeLegacyDelta(
        id: String,
        created: Long,
        model: String,
        text: String,
        finishReason: String?,
    ): String {
        val chunk = LegacyStreamChunk(
            id = id,
            created = created,
            model = model,
            choices = listOf(LegacyStreamChoice(text = text, index = 0, finishReason = finishReason)),
        )
        return SHARED_JSON.encodeToString(LegacyStreamChunk.serializer(), chunk)
    }

    private fun java.io.Writer.writeSse(payload: String) {
        write("data: ")
        write(payload)
        write("\n\n")
    }

    // ---------- response shapes (sync + SSE) ----------------------------

    @Serializable
    data class HealthResponse(
        val status: String,
        val model: String,
        val backend: String,
        val loading: Boolean = false,
        @kotlinx.serialization.SerialName("loaded_models") val loadedModels: List<String> = emptyList(),
    )

    @Serializable
    data class ChatStreamChunk(
        val id: String,
        val `object`: String = "chat.completion.chunk",
        val created: Long,
        val model: String,
        val choices: List<ChatStreamChoice>,
    )

    @Serializable
    data class ChatStreamChoice(
        val index: Int = 0,
        val delta: ChatStreamDelta,
        @kotlinx.serialization.SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class ChatStreamDelta(
        val role: String? = null,
        val content: String? = null,
    )

    @Serializable
    data class LegacyStreamChunk(
        val id: String,
        val `object`: String = "text_completion",
        val created: Long,
        val model: String,
        val choices: List<LegacyStreamChoice>,
    )

    @Serializable
    data class LegacyStreamChoice(
        val text: String,
        val index: Int = 0,
        @kotlinx.serialization.SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class LoadModelRequest(
        val id: String,
        val backend: String? = null,  // "gpu" | "cpu"; default gpu
    )

    @Serializable
    data class AvailableModelsResponse(
        val `object`: String = "list",
        val data: List<AvailableModelEntry>,
    )

    @Serializable
    data class AvailableModelEntry(
        val id: String,
        val path: String,
        @kotlinx.serialization.SerialName("size_mib") val sizeMib: Long,
        val source: String,
        val loaded: Boolean,
    )

    @Serializable
    data class OpenAiError(val error: Payload) {
        @Serializable
        data class Payload(val message: String, val type: String, val code: String)

        companion object {
            fun of(message: String, type: String, code: String) = OpenAiError(Payload(message, type, code))
        }
    }

    private fun badJson(t: Throwable) = OpenAiError.of(
        message = "invalid JSON: ${t.message}",
        type = "invalid_request_error",
        code = "bad_request",
    )
    private fun badField(msg: String) = OpenAiError.of(
        message = msg,
        type = "invalid_request_error",
        code = "bad_request",
    )
    private fun noSuchModelError(model: String) = OpenAiError.of(
        message = "Model '$model' is not loaded. Load it from the app first, or omit the `model` field to use the default.",
        type = "invalid_request_error",
        code = "model_not_found",
    )

    companion object {
        private const val TAG = "HttpServer"
        private val SSE_CT = ContentType("text", "event-stream")
        private val SHARED_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        // Must match maxNumImages in LiteRtLmEngine.EngineConfig — if they
        // drift the native preprocessor segfaults on images 5..N.
        private const val MAX_IMAGES_PER_REQUEST = 4

        /**
         * Compare two token strings in constant time relative to the
         * expected length. Prevents timing side channels from leaking the
         * API key a character at a time. Safe for short (<= a few hundred
         * byte) tokens; not intended for large payloads.
         */
        private fun tokensEqual(given: String, expected: String): Boolean {
            val a = given.toByteArray(Charsets.UTF_8)
            val b = expected.toByteArray(Charsets.UTF_8)
            return java.security.MessageDigest.isEqual(a, b)
        }

        private val NO_MODEL_ERROR = OpenAiError.of(
            message = "No model loaded",
            type = "server_error",
            code = "model_not_loaded",
        )

        /**
         * Serve a single bundled asset. Infers Content-Type from the file
         * extension. 404s if the asset is missing.
         */
        internal suspend fun serveAsset(
            call: io.ktor.server.application.ApplicationCall,
            assets: AssetManager,
            path: String,
        ) {
            val bytes = try {
                assets.open(path).use { it.readBytes() }
            } catch (_: Throwable) {
                call.respond(HttpStatusCode.NotFound)
                return
            }
            val ct = when {
                path.endsWith(".html") -> ContentType.Text.Html
                path.endsWith(".css")  -> ContentType.Text.CSS
                path.endsWith(".js")   -> ContentType.Application.JavaScript
                // ES module extension — Chrome/WebView refuse to execute a
                // <script type="module"> whose response has a non-JS MIME
                // (OctetStream), so pdf.js 4.x was failing silently here.
                path.endsWith(".mjs")  -> ContentType.Application.JavaScript
                path.endsWith(".svg")  -> ContentType.Image.SVG
                path.endsWith(".png")  -> ContentType.Image.PNG
                path.endsWith(".ico")  -> ContentType("image", "x-icon")
                path.endsWith(".json") -> ContentType.Application.Json
                else -> ContentType.Application.OctetStream
            }
            call.respondBytes(bytes, ct)
        }
    }
}

// Compile-time constant for the LiteRT-LM version, shown in the 501 body.
private object BuildConfigLike {
    const val LITERTLM_VERSION = "0.13.1"
}

// /v1/search response shape — mirrored by the web UI "sources" box.
@kotlinx.serialization.Serializable
data class WebSearchResponse(val query: String, val results: List<SearxngClient.Result>)

@kotlinx.serialization.Serializable
data class WebSearchError(val code: String)

/** Helper — request-level sampling override onto the persisted defaults. */
private fun ChatCompletionRequest.toSamplingParams(defaults: SamplingParams): SamplingParams =
    SamplingParams(
        temperature = temperature ?: defaults.temperature,
        topP = topP ?: defaults.topP,
        topK = defaults.topK,
        maxTokens = maxTokens ?: defaults.maxTokens,
    )

private fun CompletionRequest.toSamplingParams(defaults: SamplingParams): SamplingParams =
    SamplingParams(
        temperature = temperature ?: defaults.temperature,
        topP = topP ?: defaults.topP,
        topK = defaults.topK,
        maxTokens = maxTokens ?: defaults.maxTokens,
    )
