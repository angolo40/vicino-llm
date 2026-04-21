package com.sectl.litertlm.server

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Discovers LiteRT-LM model files on the device.
 *
 * Preferred location: `/sdcard/Models/` — mentioned in the spec and easy to
 * push to with `adb push`.
 * Fallback: `Context.getExternalFilesDir(null)` → the app-scoped external
 * dir, which doesn't need `MANAGE_EXTERNAL_STORAGE`. Users push there with
 * `adb push foo.litertlm /sdcard/Android/data/com.sectl.litertlm.server/files/`.
 *
 * The repo returns files sorted newest-first so freshly sideloaded models
 * pop to the top of the spinner.
 */
class ModelRepository(private val appContext: Context) {

    data class ModelFile(
        val name: String,
        val absolutePath: String,
        val sizeBytes: Long,
        val source: Source,
    ) {
        enum class Source { PUBLIC_MODELS, APP_EXTERNAL }

        val sizeMiB: Long get() = sizeBytes / (1024L * 1024L)
    }

    private val _models = MutableStateFlow<List<ModelFile>>(emptyList())
    val models: StateFlow<List<ModelFile>> = _models.asStateFlow()

    fun refresh() {
        val found = mutableListOf<ModelFile>()
        found += scan(publicModelsDir(), ModelFile.Source.PUBLIC_MODELS)
        found += scan(appExternalDir(), ModelFile.Source.APP_EXTERNAL)

        // Sort by mtime desc — newest sideloads first.
        val byMtime = found.sortedByDescending { File(it.absolutePath).lastModified() }
        _models.value = byMtime

        Log.i(
            TAG,
            "refresh: ${byMtime.size} model(s) found " +
                "(${byMtime.count { it.source == ModelFile.Source.PUBLIC_MODELS }} in /sdcard/Models, " +
                "${byMtime.count { it.source == ModelFile.Source.APP_EXTERNAL }} in app-external)",
        )
    }

    private fun scan(dir: File?, source: ModelFile.Source): List<ModelFile> {
        if (dir == null) return emptyList()
        return try {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles { f -> f.isFile && matchesModelFilename(f.name) }
                ?.map {
                    ModelFile(
                        name = it.name,
                        absolutePath = it.absolutePath,
                        sizeBytes = it.length(),
                        source = source,
                    )
                }
                .orEmpty()
        } catch (se: SecurityException) {
            Log.w(TAG, "scan $dir denied: ${se.message}")
            emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "scan $dir failed", t)
            emptyList()
        }
    }

    private fun matchesModelFilename(name: String): Boolean {
        val lower = name.lowercase()
        // .litertlm is the primary format. .task is a secondary fallback for
        // older bundles the user may already have from MediaPipe days.
        return lower.endsWith(".litertlm") || lower.endsWith(".task")
    }

    private fun publicModelsDir(): File? =
        File(Environment.getExternalStorageDirectory(), "Models").takeIf { true }

    private fun appExternalDir(): File? = appContext.getExternalFilesDir(null)

    /** Useful for the UI hint / README. */
    fun paths(): Pair<String, String> {
        return publicModelsDir()!!.absolutePath to
            (appExternalDir()?.absolutePath ?: "<unavailable>")
    }

    companion object {
        private const val TAG = "ModelRepository"
    }
}
