package com.sectl.litertlm.server.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.sectl.litertlm.server.ModelRepository
import com.sectl.litertlm.server.R

/**
 * Unified view: each row is either a local file, a curated entry not yet
 * downloaded, or a locally-existing file that also matches a curated entry
 * (same filename) — in which case it behaves like a local file.
 *
 * The single action button changes shape based on state:
 *   - not on device  → Download
 *   - on device, not loaded → Load
 *   - on device, loaded → Unload (or "loaded" pill, disabled during switch)
 *   - download in progress for this id → Cancel
 */
class ModelAdapter(
    private val onLoad: (ModelRepository.ModelFile) -> Unit,
    private val onUnload: (modelId: String) -> Unit,
    private val onDownload: (Row.Remote) -> Unit,
    private val onCancelDownload: (modelId: String) -> Unit,
) : RecyclerView.Adapter<ModelAdapter.VH>() {

    sealed interface Row {
        val id: String

        data class Local(val file: ModelRepository.ModelFile) : Row {
            override val id: String = file.name
        }

        data class Remote(
            override val id: String,
            val repo: String,
            val filename: String,
            val sizeLabel: String,
            val license: String,
            val gated: Boolean,
            /** Shown as a small tag next to the name, null hides it. */
            val badge: String? = null,
        ) : Row
    }

    private var items: List<Row> = emptyList()
    private var loadedIds: Set<String> = emptySet()
    private var busy: Boolean = false
    private var downloadingId: String? = null

    fun submit(list: List<Row>) {
        items = list
        notifyDataSetChanged()
    }

    fun setLoadedIds(ids: List<String>) {
        loadedIds = ids.toSet()
        notifyDataSetChanged()
    }

    fun setBusy(v: Boolean) {
        busy = v
        notifyDataSetChanged()
    }

    fun setDownloadingId(id: String?) {
        downloadingId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val row = items[i]
        h.name.text = row.id

        when (row) {
            is Row.Local -> bindLocal(h, row)
            is Row.Remote -> bindRemote(h, row)
        }
    }

    private fun bindLocal(h: VH, row: Row.Local) {
        val src = when (row.file.source) {
            ModelRepository.ModelFile.Source.PUBLIC_MODELS -> "/sdcard/Models"
            ModelRepository.ModelFile.Source.APP_EXTERNAL -> "app-external"
        }
        h.meta.text = "${row.file.sizeMiB} MiB · on device · $src"
        val isLoaded = row.id in loadedIds
        when {
            isLoaded -> {
                h.action.text = "Unload"
                h.action.isEnabled = !busy
                h.action.setOnClickListener { onUnload(row.id) }
            }
            else -> {
                h.action.text = h.itemView.context.getString(R.string.load_model)
                h.action.isEnabled = !busy
                h.action.setOnClickListener { onLoad(row.file) }
            }
        }
    }

    private fun bindRemote(h: VH, row: Row.Remote) {
        val badgeSuffix = row.badge?.let { " · $it" }.orEmpty()
        h.meta.text = "${row.sizeLabel} · ${row.license}${if (row.gated) " · gated" else ""}$badgeSuffix"
        val isDownloading = downloadingId == row.id
        when {
            isDownloading -> {
                h.action.text = h.itemView.context.getString(R.string.download_action_cancel)
                h.action.isEnabled = true
                h.action.setOnClickListener { onCancelDownload(row.id) }
            }
            else -> {
                h.action.text = h.itemView.context.getString(R.string.download_action)
                h.action.isEnabled = downloadingId == null
                h.action.setOnClickListener { onDownload(row) }
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val action: MaterialButton = v.findViewById(R.id.actionButton)
    }
}
