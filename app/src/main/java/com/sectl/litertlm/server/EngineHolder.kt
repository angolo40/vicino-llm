package com.sectl.litertlm.server

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide registry of currently loaded [InferenceEngine]s.
 *
 * v2 change: supports multiple engines alive at once, keyed by filename
 * ([InferenceEngine.modelId]). This is the OpenAI routing contract —
 * clients select the engine via the `model` field in the request.
 *
 * The "default" engine (first loaded, or last-added) is used when the
 * request doesn't specify a model.
 *
 * Loading is serialized via [mutex]; inference reads are lock-free.
 * Callers MUST call [loadNew] on a background dispatcher (init is slow).
 */
object EngineHolder {
    private const val TAG = "EngineHolder"

    private val engines = java.util.concurrent.ConcurrentHashMap<String, InferenceEngine>()

    // `defaultEngine` is cached together with its id inside a single
    // @Volatile reference so `current` is always self-consistent even if
    // another thread mutates the registry between two reads. The previous
    // `defaultId` + `engines[defaultId]` pattern could return null for a
    // request that arrived microseconds before an unload() call rebuilt
    // the default — that race actually surfaced during concurrent
    // OpenWebUI autocompletes.
    @Volatile private var defaultEntry: Pair<String, InferenceEngine>? = null

    private val mutex = Mutex()

    /** Convenience: the most recently installed engine, or null. */
    val current: InferenceEngine?
        get() = defaultEntry?.second

    /** Returns all loaded engines. */
    fun all(): List<InferenceEngine> = engines.values.toList()

    /** Look up by filename (e.g. "gemma-4-E2B-it.litertlm"). Null if absent. */
    fun byId(id: String): InferenceEngine? = engines[id]

    /** If [id] is null, returns [current]. Used by HTTP handlers. */
    fun resolve(id: String?): InferenceEngine? {
        if (id.isNullOrBlank()) return current
        engines[id]?.let { return it }
        return engines.entries.firstOrNull {
            it.key.equals(id, ignoreCase = true) ||
                it.key.startsWith(id, ignoreCase = true)
        }?.value
    }

    /**
     * Install a new engine. If an engine with the same [InferenceEngine.modelId]
     * is already present, it is closed first. Becomes the new default engine.
     */
    suspend fun loadNew(engine: InferenceEngine, modelPath: String) = mutex.withLock {
        engine.load(modelPath)
        val id = engine.modelId
        engines.put(id, engine)?.let { previous ->
            Log.i(TAG, "replacing previous engine for $id (${previous.backend})")
            runCatching { previous.close() }
        }
        defaultEntry = id to engine
        Log.i(TAG, "installed: $id (backend=${engine.backend}). total=${engines.size}")
    }

    /** Close and forget one engine by id. */
    suspend fun unload(id: String) = mutex.withLock {
        engines.remove(id)?.let {
            runCatching { it.close() }
            Log.i(TAG, "unloaded $id")
        }
        if (defaultEntry?.first == id) {
            val next = engines.entries.firstOrNull()
            defaultEntry = next?.let { it.key to it.value }
        }
    }

    /** Close all engines. */
    suspend fun unloadAll() = mutex.withLock {
        engines.values.forEach { runCatching { it.close() } }
        engines.clear()
        defaultEntry = null
        Log.i(TAG, "unloaded all")
    }
}
