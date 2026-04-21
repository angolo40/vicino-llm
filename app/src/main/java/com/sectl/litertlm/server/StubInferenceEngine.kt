package com.sectl.litertlm.server

import android.util.Log
import kotlinx.coroutines.delay
import java.io.File

/**
 * Placeholder engine used from Phase 3 until LiteRT-LM wiring lands in Phase 4.
 *
 * It validates that the file exists and is non-empty, simulates a short load
 * delay so the UI's progress indicator gets exercised, and returns a canned
 * echo-style response from [infer]. Token counts are estimated as chars/4
 * to match the same fallback strategy the spec allows for the real engine
 * when the runtime tokenizer isn't available.
 */
class StubInferenceEngine : InferenceEngine {

    override val backend: String = "stub"

    @Volatile override var isLoaded: Boolean = false
        private set

    @Volatile override var loadedModelPath: String? = null
        private set

    override val modelId: String
        get() = loadedModelPath?.substringAfterLast('/') ?: "stub"

    override suspend fun load(modelPath: String) {
        val file = File(modelPath)
        require(file.exists()) { "model file not found: $modelPath" }
        require(file.length() > 0) { "model file is empty: $modelPath" }
        Log.i(TAG, "stub load: $modelPath (${file.length()} bytes) — not actually memory-mapping")

        // Pretend to take a moment so the UI progress bar is visible; keeps
        // the developer honest about putting load() on a background dispatcher.
        delay(600)

        loadedModelPath = modelPath
        isLoaded = true
    }

    override suspend fun infer(prompt: String, params: SamplingParams): InferenceResult {
        check(isLoaded) { "infer() called before load()" }
        val start = System.currentTimeMillis()

        // Simulate a tiny bit of work so latency metrics aren't all zero.
        delay(50)

        val canned = buildString {
            append("[stub reply] loaded=${File(loadedModelPath!!).name}, ")
            append("temp=${params.temperature}, topP=${params.topP}, topK=${params.topK}, ")
            append("max=${params.maxTokens}. You said: ")
            append(prompt.take(160))
            if (prompt.length > 160) append("…")
        }

        val latency = System.currentTimeMillis() - start
        return InferenceResult(
            text = canned,
            promptTokens = estimateTokens(prompt),
            completionTokens = estimateTokens(canned),
            latencyMs = latency,
        )
    }

    override fun inferStream(prompt: String, params: SamplingParams): kotlinx.coroutines.flow.Flow<StreamChunk> =
        kotlinx.coroutines.flow.flow {
            check(isLoaded) { "inferStream() called before load()" }
            val start = System.currentTimeMillis()
            val canned = "[stub streaming reply] You said: ${prompt.take(100)}"
            // Emit word by word to exercise the pipeline.
            val words = canned.split(' ')
            words.forEachIndexed { i, w ->
                kotlinx.coroutines.delay(40)
                emit(StreamChunk(text = if (i == 0) w else " $w"))
            }
            val latency = System.currentTimeMillis() - start
            emit(
                StreamChunk(
                    text = "",
                    isFinal = true,
                    promptTokens = estimateTokens(prompt),
                    completionTokens = estimateTokens(canned),
                    latencyMs = latency,
                ),
            )
        }

    override fun close() {
        isLoaded = false
        loadedModelPath = null
        Log.i(TAG, "stub closed")
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    companion object {
        private const val TAG = "StubInferenceEngine"
    }
}
