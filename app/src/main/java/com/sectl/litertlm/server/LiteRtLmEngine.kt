package com.sectl.litertlm.server

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real LiteRT-LM-backed inference engine. Ships in Phase 4 as the default.
 *
 * The LiteRT-LM SDK (com.google.ai.edge.litertlm:litertlm-android:0.10.2)
 * is AutoCloseable on both [Engine] and [Conversation]. We hold one
 * persistent Engine and recreate Conversations lazily per-infer so sampler
 * parameters from each request can override the defaults.
 *
 * Threading: all SDK calls are blocking. [load] and [infer] expect to be
 * invoked from a background dispatcher (Dispatchers.IO) — `initialize()`
 * on the S10 takes 30-60s for the 3.65 GB E4B model.
 *
 * GPU caveat: on the Samsung S10 (Mali-G76 MP12 + Exynos 9820) the vendor
 * OpenCL driver has been historically buggy. If [load] with [BackendKind.GPU]
 * throws a LiteRtLmJniException we surface it to the caller so the UI can
 * offer to retry with CPU.
 */
class LiteRtLmEngine(
    private val backendKind: BackendKind = BackendKind.GPU,
    /** Context window applied at model load. 4096 is a safe default; users
     *  with RAM headroom can raise via Settings up to 32768 (Gemma 4 cap). */
    private val contextWindow: Int = 4096,
) : InferenceEngine {

    enum class BackendKind { CPU, GPU }

    override val backend: String = when (backendKind) {
        BackendKind.CPU -> "cpu"
        BackendKind.GPU -> "gpu"
    }

    @Volatile override var isLoaded: Boolean = false
        private set

    @Volatile override var loadedModelPath: String? = null
        private set

    override val modelId: String
        get() = loadedModelPath?.substringAfterLast('/') ?: "unloaded"

    private var engine: Engine? = null

    // LiteRT-LM 0.10 enforces a single active session per Engine. If a second
    // request races in before the previous Conversation is fully closed, the
    // SDK throws FAILED_PRECONDITION: "A session already exists. Only one
    // session is supported at a time." Serialize inference access here so
    // HTTP clients (especially OpenWebUI, which fires request-after-request
    // as the user types) never overlap Conversations.
    private val inferenceMutex = Mutex()

    override suspend fun load(modelPath: String) {
        if (isLoaded && loadedModelPath == modelPath) {
            Log.i(TAG, "load: same model already loaded — skipping")
            return
        }
        // If a prior engine is present (different model, or retry after error)
        // release it before allocating a new one. Avoids memory blow-up.
        close()

        val pickedBackend = when (backendKind) {
            BackendKind.CPU -> Backend.CPU()
            BackendKind.GPU -> Backend.GPU()
        }

        // Context window + multimodal capacity.
        //   - maxNumImages > 0 MUST be set or Gemma4DataProcessor segfaults
        //     the native image preprocessor when a user actually sends an
        //     image (verified via SIGSEGV on 0x0 in stb_image_preprocessor).
        //   - visionBackend can follow the main backend (Adreno / Mali OK).
        //   - audioBackend is CPU-ONLY on Gemma 4 — the SDK throws
        //     `INVALID_ARGUMENT: Audio backend constraint mismatch. Model
        //     requires one of [cpu] but Audio backend is GPU` if we pass GPU.
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = pickedBackend,
            visionBackend = pickedBackend,
            audioBackend = Backend.CPU(),
            maxNumTokens = contextWindow.coerceIn(1024, 32_768),
            maxNumImages = 4,
        )

        Log.i(TAG, "load: initialize Engine(backend=$backend, model=$modelPath) — may take 30-60s on S10")
        val t0 = System.currentTimeMillis()
        val eng = Engine(cfg)
        eng.initialize()
        val loadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "load: initialize() completed in ${loadMs}ms")

        engine = eng
        loadedModelPath = modelPath
        isLoaded = true

        // NOTE: A "vision prewarm" (running a 1x1 image through at load time)
        // was attempted to force Gemma4DataProcessor to allocate its encoder
        // slabs early. It DIDN'T help: on S24 the process was still killed by
        // the kernel's memory-pressure policy on the first real image, plus
        // the prewarm itself raised resident memory earlier (worsening the
        // oom_adj score). Slab allocations are tied to actual tensor shapes
        // anyway, so a 1x1 image doesn't warm up the buffers a full-size
        // image needs. Kept removed; survival now lives in ServerService's
        // auto-restore + HttpServer's 503 fallback.
    }

    override suspend fun infer(prompt: String, params: SamplingParams): InferenceResult =
        inferenceMutex.withLock {
            val eng = engine
            check(isLoaded && eng != null) { "infer() called before load()" }

            val sampler = SamplerConfig(
                topK = params.topK,
                topP = params.topP.toDouble(),
                temperature = params.temperature.toDouble(),
                seed = 0,
            )
            val convCfg = ConversationConfig(samplerConfig = sampler)

            val t0 = System.currentTimeMillis()
            val conversation: Conversation = eng.createConversation(convCfg)
            val reply = try {
                conversation.sendMessage(prompt, emptyMap())
            } finally {
                runCatching { conversation.close() }
            }
            val latency = System.currentTimeMillis() - t0

            val replyText = extractText(reply.contents)
            InferenceResult(
                text = replyText,
                promptTokens = estimateTokens(prompt),
                completionTokens = estimateTokens(replyText),
                latencyMs = latency,
            )
        }

    override fun inferStream(prompt: String, params: SamplingParams): kotlinx.coroutines.flow.Flow<StreamChunk> =
        kotlinx.coroutines.flow.flow {
            // inferenceMutex guards BOTH createConversation and the Flow
            // consumption so two HTTP requests cannot race a second session.
            inferenceMutex.withLock {
                val eng = engine
                check(isLoaded && eng != null) { "inferStream() called before load()" }

                val sampler = SamplerConfig(
                    topK = params.topK,
                    topP = params.topP.toDouble(),
                    temperature = params.temperature.toDouble(),
                    seed = 0,
                )
                val convCfg = ConversationConfig(samplerConfig = sampler)
                val t0 = System.currentTimeMillis()
                val conversation: Conversation = eng.createConversation(convCfg)
                val fullText = StringBuilder()

                try {
                    // SDK 0.10 contract (confirmed empirically): Conversation
                    // .sendMessageAsync(prompt) emits each new delta Message as
                    // it becomes available. Each Message carries only the NEW
                    // delta text, not the cumulative reply — so we emit it
                    // straight through, no substring math. Empirically
                    // verified against non-streaming replies.
                    conversation.sendMessageAsync(prompt, emptyMap()).collect { msg ->
                        val delta = buildString {
                            msg.contents.contents.forEach { c ->
                                if (c is Content.Text) append(c.text)
                            }
                        }
                        if (delta.isNotEmpty()) {
                            fullText.append(delta)
                            emit(StreamChunk(text = delta))
                        }
                    }
                } finally {
                    runCatching { conversation.close() }
                }

                val latency = System.currentTimeMillis() - t0
                val finalText = fullText.toString()
                emit(
                    StreamChunk(
                        text = "",
                        isFinal = true,
                        promptTokens = estimateTokens(prompt),
                        completionTokens = estimateTokens(finalText),
                        latencyMs = latency,
                    ),
                )
            }
        }

    override suspend fun inferMultimodal(
        parts: List<MultimodalPart>,
        params: SamplingParams,
    ): InferenceResult = inferenceMutex.withLock {
        val eng = engine
        check(isLoaded && eng != null) { "inferMultimodal() called before load()" }

        val sampler = SamplerConfig(
            topK = params.topK,
            topP = params.topP.toDouble(),
            temperature = params.temperature.toDouble(),
            seed = 0,
        )
        val convCfg = ConversationConfig(samplerConfig = sampler)

        // Map our own Part types onto LiteRT-LM Content.* variants. The SDK
        // accepts a Contents built from a varargs of Content, so we just
        // walk the list in order.
        val sdkContents = Contents.of(
            *parts.map { part ->
                when (part) {
                    is MultimodalPart.Text -> Content.Text(part.text)
                    is MultimodalPart.Image -> Content.ImageBytes(part.bytes)
                    is MultimodalPart.Audio -> Content.AudioBytes(part.bytes)
                }
            }.toTypedArray()
        )

        val t0 = System.currentTimeMillis()
        val conversation: Conversation = eng.createConversation(convCfg)
        val reply = try {
            conversation.sendMessage(sdkContents, emptyMap())
        } finally {
            runCatching { conversation.close() }
        }
        val latency = System.currentTimeMillis() - t0
        val text = extractText(reply.contents)

        // Approximate prompt tokens from all text chunks + media placeholders
        // (we don't have the tokenizer; a crude estimate is fine here).
        val promptChars = parts.sumOf {
            when (it) {
                is MultimodalPart.Text -> it.text.length
                is MultimodalPart.Image -> 256  // Gemma vision is ~256 tok/image
                is MultimodalPart.Audio -> 128  // rough
            }
        }
        InferenceResult(
            text = text,
            promptTokens = (promptChars / 4).coerceAtLeast(1),
            completionTokens = estimateTokens(text),
            latencyMs = latency,
        )
    }

    override fun inferMultimodalStream(
        parts: List<MultimodalPart>,
        params: SamplingParams,
    ): kotlinx.coroutines.flow.Flow<StreamChunk> = kotlinx.coroutines.flow.flow {
        inferenceMutex.withLock {
            val eng = engine
            check(isLoaded && eng != null) { "inferMultimodalStream() called before load()" }

            val sampler = SamplerConfig(
                topK = params.topK,
                topP = params.topP.toDouble(),
                temperature = params.temperature.toDouble(),
                seed = 0,
            )
            val convCfg = ConversationConfig(samplerConfig = sampler)

            val sdkContents = Contents.of(
                *parts.map { part ->
                    when (part) {
                        is MultimodalPart.Text -> Content.Text(part.text)
                        is MultimodalPart.Image -> Content.ImageBytes(part.bytes)
                        is MultimodalPart.Audio -> Content.AudioBytes(part.bytes)
                    }
                }.toTypedArray()
            )

            val t0 = System.currentTimeMillis()
            val conversation: Conversation = eng.createConversation(convCfg)
            val fullText = StringBuilder()
            try {
                // SDK 0.10 exposes sendMessageAsync(Contents, Map): Flow<Message>
                // — same delta semantics as the text-only variant, so we just
                // mirror inferStream's consumption pattern.
                conversation.sendMessageAsync(sdkContents, emptyMap()).collect { msg ->
                    val delta = buildString {
                        msg.contents.contents.forEach { c ->
                            if (c is Content.Text) append(c.text)
                        }
                    }
                    if (delta.isNotEmpty()) {
                        fullText.append(delta)
                        emit(StreamChunk(text = delta))
                    }
                }
            } finally {
                runCatching { conversation.close() }
            }

            val latency = System.currentTimeMillis() - t0
            val finalText = fullText.toString()
            val promptChars = parts.sumOf {
                when (it) {
                    is MultimodalPart.Text -> it.text.length
                    is MultimodalPart.Image -> 256
                    is MultimodalPart.Audio -> 128
                }
            }
            emit(
                StreamChunk(
                    text = "",
                    isFinal = true,
                    promptTokens = (promptChars / 4).coerceAtLeast(1),
                    completionTokens = estimateTokens(finalText),
                    latencyMs = latency,
                ),
            )
        }
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
        isLoaded = false
        loadedModelPath = null
        Log.i(TAG, "close: engine released")
    }

    private fun extractText(contents: com.google.ai.edge.litertlm.Contents): String {
        // Flatten the response Contents into a single string. Gemma 4 replies
        // are typically a single Content.Text; be defensive and concatenate
        // any text contents, skip non-text chunks.
        return buildString {
            contents.contents.forEach { c ->
                if (c is Content.Text) append(c.text)
            }
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    companion object {
        private const val TAG = "LiteRtLmEngine"
    }
}
