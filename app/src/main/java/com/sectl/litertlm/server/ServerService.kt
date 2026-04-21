package com.sectl.litertlm.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that hosts:
 *   - the Ktor HTTP server
 *   - the persistent notification + PARTIAL_WAKE_LOCK
 *
 * The actual [InferenceEngine] lives in the process-wide [EngineHolder] so
 * both the HTTP handlers and the debug UI can talk to it without going
 * through the service's Binder.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var server: HttpServer? = null
    private val prefs by lazy { Prefs(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerAndUnloadEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LOAD_MODEL -> {
                val path = intent.getStringExtra(EXTRA_MODEL_PATH)
                val backendStr = intent.getStringExtra(EXTRA_BACKEND) ?: "gpu"
                if (path.isNullOrBlank()) {
                    Log.w(TAG, "LOAD_MODEL without ${EXTRA_MODEL_PATH} — ignoring")
                } else {
                    loadModelAsync(path, parseBackend(backendStr))
                }
                return START_STICKY
            }
            ACTION_UNLOAD_ALL -> {
                scope.launch {
                    EngineHolder.unloadAll()
                    ServerState.clearModel()
                }
                return START_STICKY
            }
            ACTION_UNLOAD_ONE -> {
                val id = intent.getStringExtra(EXTRA_MODEL_ID)
                if (id.isNullOrBlank()) {
                    Log.w(TAG, "UNLOAD_ONE without ${EXTRA_MODEL_ID} — ignoring")
                } else {
                    scope.launch {
                        EngineHolder.unload(id)
                        // Reflect the new registry state in ServerState so the
                        // Models tab repopulates the chip group correctly and
                        // the persistent notification updates. If no engines
                        // remain, clear the "current model" fields too.
                        val remaining = EngineHolder.all()
                        ServerState.setLoadedModelIds(remaining.map { it.modelId })
                        if (remaining.isEmpty()) {
                            ServerState.clearModel()
                        } else if (EngineHolder.current != null) {
                            // `current` now points to whatever engine
                            // EngineHolder picked as the new default — refresh
                            // the header fields to match, otherwise the UI
                            // keeps showing the just-unloaded name.
                            val eng = EngineHolder.current
                            if (eng != null) {
                                ServerState.setModel(
                                    name = eng.modelId,
                                    path = eng.loadedModelPath,
                                    backend = eng.backend,
                                )
                            }
                        }
                    }
                }
                return START_STICKY
            }
            else -> {
                startServer(intent?.getIntExtra(EXTRA_PORT, ServerState.DEFAULT_PORT) ?: ServerState.DEFAULT_PORT)
                // System-triggered restart (intent == null) after our process
                // was killed by lmkd during heavy multimodal work. START_STICKY
                // only brings the service back — MainActivity.onCreate (where
                // auto_restore used to live) is NOT called in that path, so
                // the HTTP server came up empty and clients saw "bloccato".
                // Restore the last-used model here so the server recovers
                // without user intervention.
                if (intent == null) restoreLastModelIfWanted()
            }
        }
        return START_STICKY
    }

    private fun restoreLastModelIfWanted() {
        if (!prefs.autoRestoreEnabled) return
        val path = prefs.lastModelPath?.takeIf { it.isNotBlank() } ?: return
        if (EngineHolder.current?.loadedModelPath == path) return  // already loaded

        // Circuit breaker: a system restart (intent == null) is almost
        // always a symptom of the OS killing us for memory pressure. If it
        // happens repeatedly in a short window, reloading the same model
        // just feeds the same loop; the device spins in restart/kill/reload
        // for hours, drains the battery, and the user can't use the app
        // because every request lands on an engine in mid-load.
        //
        // So: record this attempt, and if we've crossed the threshold,
        // refuse to reload until the user manually re-enables it from
        // Settings. The flag itself is sticky in Prefs so it survives
        // further kills.
        prefs.recordAutoRestoreAttempt()
        if (prefs.autoRestoreTripped) {
            Log.w(
                TAG,
                "auto-restore circuit breaker tripped — skipping reload of $path. " +
                    "Reset from Settings when you've switched to a smaller model.",
            )
            return
        }
        Log.i(TAG, "system restart detected — auto-restoring $path")
        loadModelAsync(path, prefs.backendKind)
    }

    private fun parseBackend(s: String): LiteRtLmEngine.BackendKind =
        if (s.equals("cpu", ignoreCase = true)) LiteRtLmEngine.BackendKind.CPU
        else LiteRtLmEngine.BackendKind.GPU

    private fun startServer(port: Int) {
        if (server != null) {
            Log.w(TAG, "startServer called but server is already running")
            return
        }

        createNotificationChannel()
        val lanIp = NetworkUtil.primaryLanIp() ?: "0.0.0.0"
        ServerState.setRunning(running = true, host = lanIp, port = port)
        ServerState.resetRequestCount()

        val initial = buildNotification(ServerState.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }

        // ModelRepository used by the web UI's /v1/models/available route.
        // Stays pinned as long as the service lives.
        val modelRepo = ModelRepository(applicationContext)
        server = HttpServer(
            port = port,
            host = "0.0.0.0",
            samplingDefaults = { prefs.defaultSampling() },
            assets = applicationContext.assets,
            availableModels = {
                // Cheap (re-)scan on every request. The disk I/O is trivial
                // (listing a directory), and we avoid stale data if the user
                // adb-pushes a new file between UI visits.
                modelRepo.refresh()
                modelRepo.models.value
            },
            loadModel = { path, backend ->
                val kind = if (backend == "cpu") LiteRtLmEngine.BackendKind.CPU
                           else LiteRtLmEngine.BackendKind.GPU
                loadModelAsync(path, kind)
                prefs.lastModelPath = path
                true
            },
            unloadModel = { id ->
                EngineHolder.unload(id)
                ServerState.setLoadedModelIds(EngineHolder.all().map { it.modelId })
                if (EngineHolder.all().isEmpty()) ServerState.clearModel()
            },
            serverApiKey = { prefs.serverApiKey },
            searxngUrl = { prefs.searxngUrl },
            webSearchRewriteQuery = { prefs.webSearchRewriteQuery },
            webSearchMaxResults = { prefs.webSearchMaxResults },
        ).also { it.start() }

        notificationJob = scope.launch {
            ServerState.state.collectLatest { snap ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(snap))
            }
        }

        Log.i(TAG, "Server started on $lanIp:$port")
    }

    private fun loadModelAsync(path: String, backendKind: LiteRtLmEngine.BackendKind) {
        scope.launch {
            ServerState.setLoading(true)
            val filename = path.substringAfterLast('/')
            val backendLabel = if (backendKind == LiteRtLmEngine.BackendKind.CPU) "cpu" else "gpu"
            ServerState.setModel(name = filename, path = null, backend = backendLabel)  // mark target while loading
            try {
                // Build the real LiteRT-LM engine. If construction fails (e.g.
                // the SDK native lib crashed on this device) fall back to the
                // stub so the UI doesn't get stuck.
                val newEngine: InferenceEngine = runCatching {
                    LiteRtLmEngine(
                        backendKind = backendKind,
                        contextWindow = prefs.contextWindow,
                    ) as InferenceEngine
                }.getOrElse {
                    Log.e(TAG, "LiteRtLmEngine ctor failed — falling back to stub", it)
                    StubInferenceEngine()
                }

                withContext(Dispatchers.IO) { EngineHolder.loadNew(newEngine, path) }
                val activeBackend = EngineHolder.current?.backend ?: backendLabel
                ServerState.setModel(name = filename, path = path, backend = activeBackend)
                ServerState.setLoadedModelIds(EngineHolder.all().map { it.modelId })
                Log.i(TAG, "model loaded: $filename (backend=$activeBackend)")
            } catch (t: Throwable) {
                Log.e(TAG, "model load failed for $path", t)
                ServerState.clearModel()
                EngineHolder.unloadAll()
            } finally {
                ServerState.setLoading(false)
            }
        }
    }

    private fun stopServerAndUnloadEngine() {
        notificationJob?.cancel()
        notificationJob = null
        server?.stop()
        server = null
        // Fire-and-forget is fine for an async STOP intent since the
        // service lives on. For onDestroy we have a blocking helper below
        // that WAITS for the unload, otherwise the 3 GB model sits in
        // native memory until the process dies.
        scope.launch { EngineHolder.unloadAll() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServerState.clearModel()
        ServerState.setRunning(false)
        Log.i(TAG, "Server stopped, engine closed")
    }

    override fun onDestroy() {
        // Cancel the CoroutineScope-driven work first, then block briefly
        // to drain EngineHolder. Using `runBlocking` at service teardown
        // is explicitly recommended for freeing native resources — without
        // this the scope.cancel() below would kill the unload mid-flight
        // and the native engine's ~3 GB allocation would leak until the
        // Android process itself gets reaped.
        notificationJob?.cancel()
        notificationJob = null
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServerState.clearModel()
        ServerState.setRunning(false)
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    EngineHolder.unloadAll()
                }
            }
        }
        Log.i(TAG, "Server stopped, engine closed (onDestroy)")
        scope.cancel()
        super.onDestroy()
    }

    // ---------- notification ---------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VicinoLLM",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while the local LLM server is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(snap: ServerState.Snapshot): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Lockscreen priority: the IP:port and request count should read at a
        // glance from the AOD / notification shade without expanding. Put
        // them in the title; push model name + backend to the body.
        val title = when {
            !snap.running -> "VicinoLLM · stopped"
            else -> "http://${snap.host}:${snap.port} · ${snap.requestCount} req"
        }
        val body = when {
            !snap.running -> "tap to open"
            snap.loading -> "loading ${snap.modelName}…"
            snap.modelName != "none" -> "${snap.modelName} · ${snap.backend}"
            else -> "no model loaded"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent,
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "gemma_server_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "GemmaServer::HttpWakeLock"

        const val ACTION_STOP = "com.sectl.litertlm.server.action.STOP"
        const val ACTION_LOAD_MODEL = "com.sectl.litertlm.server.action.LOAD_MODEL"
        const val ACTION_UNLOAD_ALL = "com.sectl.litertlm.server.action.UNLOAD_ALL"
        const val ACTION_UNLOAD_ONE = "com.sectl.litertlm.server.action.UNLOAD_ONE"
        const val EXTRA_PORT = "extra.port"
        const val EXTRA_MODEL_PATH = "extra.model_path"
        const val EXTRA_MODEL_ID = "extra.model_id"
        const val EXTRA_BACKEND = "extra.backend"  // "cpu" | "gpu"

        fun startIntent(context: Context, port: Int): Intent =
            Intent(context, ServerService::class.java).putExtra(EXTRA_PORT, port)

        fun stopIntent(context: Context): Intent =
            Intent(context, ServerService::class.java).setAction(ACTION_STOP)

        fun loadModelIntent(
            context: Context,
            absolutePath: String,
            backendKind: LiteRtLmEngine.BackendKind,
        ): Intent =
            Intent(context, ServerService::class.java)
                .setAction(ACTION_LOAD_MODEL)
                .putExtra(EXTRA_MODEL_PATH, absolutePath)
                .putExtra(
                    EXTRA_BACKEND,
                    if (backendKind == LiteRtLmEngine.BackendKind.CPU) "cpu" else "gpu",
                )

        fun unloadAllIntent(context: Context): Intent =
            Intent(context, ServerService::class.java).setAction(ACTION_UNLOAD_ALL)

        fun unloadOneIntent(context: Context, modelId: String): Intent =
            Intent(context, ServerService::class.java)
                .setAction(ACTION_UNLOAD_ONE)
                .putExtra(EXTRA_MODEL_ID, modelId)
    }
}
