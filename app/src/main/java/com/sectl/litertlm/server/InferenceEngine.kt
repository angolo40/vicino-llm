package com.sectl.litertlm.server

import kotlinx.coroutines.flow.Flow

//
// Abstraction over an on-device LLM runtime.
//
// StubInferenceEngine is the placeholder used before LiteRT-LM wiring.
// LiteRtLmEngine is the real implementation.
//
// The interface stays minimal and library-agnostic on purpose — swapping
// in MediaPipe LLM, llama.cpp JNI, or a future runtime should only require
// a new implementation of this interface.
//
interface InferenceEngine : AutoCloseable {

    // Human-readable backend label shown in /health (e.g. "stub", "cpu", "gpu").
    val backend: String

    // Filename-only model id used for multi-model routing in the v1 API.
    // Taken from the loaded file name (e.g. "gemma-4-E2B-it.litertlm").
    val modelId: String

    // True once load has completed successfully. Cleared by close.
    val isLoaded: Boolean

    // Absolute filesystem path of the currently loaded model file, or null.
    val loadedModelPath: String?

    // Load a model from modelPath. Idempotent w.r.t. the same path; loading
    // a different path without close first is undefined behavior.
    //
    // Expected to memory-map the file where possible — do not read it into
    // RAM eagerly. Suspending: callers should launch on Dispatchers.IO.
    // On a Samsung S10 the real load is 30–60 s for E4B.
    suspend fun load(modelPath: String)

    // Run inference synchronously (non-streaming). The caller is responsible
    // for applying any chat template — this function takes the prompt as-is.
    //
    // Throws IllegalStateException if isLoaded is false.
    suspend fun infer(
        prompt: String,
        params: SamplingParams = SamplingParams(),
    ): InferenceResult

    /**
     * Multimodal variant. Callers hand over already-decoded raw bytes for
     * images and audio alongside text. Engines that don't support media
     * fall back to the plain-text portion and log a warning.
     *
     * Default implementation flattens to [infer] so stub engines don't
     * need to override it.
     */
    suspend fun inferMultimodal(
        parts: List<MultimodalPart>,
        params: SamplingParams = SamplingParams(),
    ): InferenceResult =
        infer(parts.filterIsInstance<MultimodalPart.Text>().joinToString("\n") { it.text }, params)

    // Streaming variant. Returns a cold Flow emitting each new text delta as
    // it arrives from the runtime, followed by a final chunk carrying token
    // counts and latency. Callers convert chunks to SSE frames on the HTTP
    // side.
    //
    // Implementations that cannot produce incremental output should emit the
    // full infer result as a single chunk then the final metadata chunk.
    fun inferStream(
        prompt: String,
        params: SamplingParams = SamplingParams(),
    ): Flow<StreamChunk>

    /**
     * Multimodal streaming variant. Same contract as [inferStream] but with
     * image/audio payloads. Default implementation runs [inferMultimodal] and
     * emits the whole reply as a single chunk — engines that expose a real
     * streaming multimodal API (LiteRT-LM SDK 0.10+ does) should override.
     */
    fun inferMultimodalStream(
        parts: List<MultimodalPart>,
        params: SamplingParams = SamplingParams(),
    ): Flow<StreamChunk> = kotlinx.coroutines.flow.flow {
        val result = inferMultimodal(parts, params)
        if (result.text.isNotEmpty()) emit(StreamChunk(text = result.text))
        emit(
            StreamChunk(
                text = "",
                isFinal = true,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                latencyMs = result.latencyMs,
            ),
        )
    }
}

// Lightly-typed bag of sampling knobs. Mirrors the subset of OpenAI chat
// completion parameters that LiteRT-LM's SamplerConfig actually exposes.
data class SamplingParams(
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
)

// Full non-streaming result.
data class InferenceResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
    val tokensPerSecond: Double
        get() = if (latencyMs > 0) completionTokens * 1000.0 / latencyMs else 0.0
}

// Runtime-ready multimodal piece. Text comes as a plain string; images and
// audio as raw bytes already resolved from data URLs / HTTP.
sealed interface MultimodalPart {
    data class Text(val text: String) : MultimodalPart
    data class Image(val bytes: ByteArray) : MultimodalPart
    data class Audio(val bytes: ByteArray) : MultimodalPart
}

// One piece of a streaming inference response.
data class StreamChunk(
    val text: String,
    val isFinal: Boolean = false,
    // Populated only on the final chunk.
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0,
)
