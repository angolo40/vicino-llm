package com.sectl.litertlm.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of the last [CAPACITY] HTTP request events, shown
 * in the UI for debugging. Reset on process death (spec said so).
 */
object RequestLog {

    const val CAPACITY = 50

    data class Entry(
        val timestampMs: Long,
        val endpoint: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val latencyMs: Long,
        val tokensPerSecond: Double,
        val statusCode: Int,
    ) {
        fun format(): String {
            val time = TIME_FMT.format(Date(timestampMs))
            val status = if (statusCode == 200) "ok " else "$statusCode"
            return "$time $status ${endpoint.padEnd(22)} " +
                "p=${promptTokens.toString().padStart(4)} " +
                "c=${completionTokens.toString().padStart(4)} " +
                "${latencyMs}ms " +
                "%.1f tok/s".format(tokensPerSecond)
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun record(
        endpoint: String,
        promptTokens: Int,
        completionTokens: Int,
        latencyMs: Long,
        statusCode: Int,
    ) {
        val tokPerSec = if (latencyMs > 0 && completionTokens > 0) {
            completionTokens * 1000.0 / latencyMs
        } else {
            0.0
        }
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            endpoint = endpoint,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            latencyMs = latencyMs,
            tokensPerSecond = tokPerSec,
            statusCode = statusCode,
        )
        _entries.update { prev ->
            (prev + entry).takeLast(CAPACITY)
        }
    }

    private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
}
