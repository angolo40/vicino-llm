package com.sectl.litertlm.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide observable state. Read by the UI, written by the service.
 *
 * Phase 3 adds [modelName], [modelPath], [backend], [loading]. Phase 4 will
 * flip backend from "stub" to "cpu"/"gpu" once LiteRT-LM is wired.
 */
object ServerState {
    data class Snapshot(
        val running: Boolean = false,
        val host: String = "0.0.0.0",
        val port: Int = DEFAULT_PORT,
        val requestCount: Long = 0,
        val modelName: String = "none",
        val modelPath: String? = null,
        val backend: String = "none",
        val loading: Boolean = false,
        /** Filenames of all currently loaded models (multi-model v2). */
        val loadedModelIds: List<String> = emptyList(),
    )

    const val DEFAULT_PORT = 8080

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun setRunning(running: Boolean, host: String = _state.value.host, port: Int = _state.value.port) {
        _state.update { it.copy(running = running, host = host, port = port) }
    }

    fun incrementRequestCount() {
        _state.update { it.copy(requestCount = it.requestCount + 1) }
    }

    fun resetRequestCount() {
        _state.update { it.copy(requestCount = 0) }
    }

    fun setLoading(loading: Boolean) {
        _state.update { it.copy(loading = loading) }
    }

    fun setModel(name: String, path: String?, backend: String) {
        _state.update { it.copy(modelName = name, modelPath = path, backend = backend) }
    }

    fun clearModel() {
        _state.update { it.copy(modelName = "none", modelPath = null, backend = "none", loadedModelIds = emptyList()) }
    }

    fun setLoadedModelIds(ids: List<String>) {
        _state.update { it.copy(loadedModelIds = ids) }
    }
}
