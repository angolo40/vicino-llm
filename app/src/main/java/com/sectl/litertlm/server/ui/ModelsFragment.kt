package com.sectl.litertlm.server.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.sectl.litertlm.server.CuratedModels
import com.sectl.litertlm.server.EngineHolder
import com.sectl.litertlm.server.GemmaServerApp
import com.sectl.litertlm.server.ModelDownloader
import com.sectl.litertlm.server.ModelRepository
import com.sectl.litertlm.server.Prefs
import com.sectl.litertlm.server.R
import com.sectl.litertlm.server.ServerService
import com.sectl.litertlm.server.ServerState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ModelsFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var modelRepo: ModelRepository
    private lateinit var loadedChips: ChipGroup
    private lateinit var noLoadedHint: TextView
    private lateinit var unloadAllButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var modelsList: RecyclerView
    private lateinit var pathHint: TextView
    private lateinit var downloadStatus: TextView
    private lateinit var downloader: ModelDownloader
    private val adapter = ModelAdapter(
        onLoad = { file -> loadFromDisk(file) },
        onUnload = { id -> unload(id) },
        onDownload = { remote -> startDownload(remote) },
        onCancelDownload = { downloader.cancel() },
    )

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_models, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        modelRepo = ModelRepository(requireContext().applicationContext)

        loadedChips = view.findViewById(R.id.loadedChips)
        noLoadedHint = view.findViewById(R.id.noLoadedHint)
        unloadAllButton = view.findViewById(R.id.unloadAllButton)
        refreshButton = view.findViewById(R.id.refreshButton)
        modelsList = view.findViewById(R.id.modelsList)
        pathHint = view.findViewById(R.id.modelsPathHint)
        downloadStatus = view.findViewById(R.id.downloadStatus)
        downloader = (requireContext().applicationContext as GemmaServerApp).modelDownloader

        modelsList.layoutManager = LinearLayoutManager(requireContext())
        modelsList.adapter = adapter

        refreshButton.setOnClickListener { modelRepo.refresh() }
        unloadAllButton.setOnClickListener {
            requireContext().startService(ServerService.unloadAllIntent(requireContext()))
        }

        val (pub, appExt) = modelRepo.paths()
        pathHint.text = "Push to:\n  $pub\n  (or) $appExt"

        observeDownload()

        viewLifecycleOwner.lifecycleScope.launch {
            ServerState.state.collectLatest { snap ->
                renderLoadedChips(snap.loadedModelIds)
                adapter.setLoadedIds(snap.loadedModelIds)
                adapter.setBusy(snap.loading)
                unloadAllButton.isEnabled = snap.running && snap.loadedModelIds.isNotEmpty() && !snap.loading
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            modelRepo.models.collectLatest { rebuildList() }
        }

        modelRepo.refresh()
    }

    // Merge local files with curated remote entries, deduping by filename.
    private fun rebuildList() {
        val local = modelRepo.models.value
        val onDiskIds = local.map { it.name }.toSet()
        val localRows = local.map { ModelAdapter.Row.Local(it) }
        val remoteRows = CuratedModels.ALL
            .filter { it.filename !in onDiskIds }
            .map {
                ModelAdapter.Row.Remote(
                    id = it.id,
                    repo = it.repo,
                    filename = it.filename,
                    sizeLabel = it.sizeLabel,
                    license = it.license,
                    gated = it.gated,
                    badge = it.badge,
                )
            }
        adapter.submit(localRows + remoteRows)
    }

    override fun onResume() {
        super.onResume()
        modelRepo.refresh()
    }

    private fun loadFromDisk(item: ModelRepository.ModelFile) {
        val ctx = requireContext()
        if (!ServerState.state.value.running) {
            Toast.makeText(ctx, "Start the server first", Toast.LENGTH_SHORT).show()
            return
        }
        // Heavy-model RAM gate. LiteRT-LM's E4B bundle needs ~3.65 GB on disk
        // plus vision slabs + KV cache at runtime; on Samsung OneUI devices
        // with less than ~12 GB the lmkd watermark is breached during the
        // first multimodal request and the service dies in a restart loop.
        // Block with an explanatory dialog rather than letting users walk
        // into the trap blindly.
        if (isLikelyTooHeavyForDevice(ctx, item)) {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.model_heavy_dialog_title)
                .setMessage(getString(R.string.model_heavy_dialog_body, item.name))
                .setPositiveButton(R.string.model_heavy_dialog_proceed) { _, _ ->
                    proceedWithLoad(ctx, item)
                }
                .setNegativeButton(R.string.model_heavy_dialog_cancel, null)
                .show()
            return
        }
        proceedWithLoad(ctx, item)
    }

    private fun proceedWithLoad(ctx: android.content.Context, item: ModelRepository.ModelFile) {
        prefs.lastModelPath = item.absolutePath
        ctx.startService(ServerService.loadModelIntent(ctx, item.absolutePath, prefs.backendKind))
    }

    private fun isLikelyTooHeavyForDevice(
        ctx: android.content.Context,
        item: ModelRepository.ModelFile,
    ): Boolean {
        // Treat anything >= 2.5 GB on disk as "heavy" — covers the E4B
        // bundle and any bigger future Gemma variant. E2B (~1.5 GB) falls
        // below the threshold so it loads with no prompt.
        val heavyBytes = 2_500L * 1024L * 1024L
        if (item.sizeBytes < heavyBytes) return false
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager ?: return false
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // Warn when total RAM is below 12 GB. On devices with exactly 8 GB
        // E4B is basically guaranteed to crash under memory pressure;
        // between 8 and 12 GB it's borderline.
        val twelveGb = 12L * 1024L * 1024L * 1024L
        return info.totalMem < twelveGb
    }

    private fun unload(modelId: String) {
        // Send the service a targeted UNLOAD_ONE — it unloads just this engine
        // and updates ServerState so the chip group, persistent notification
        // and Settings banner all reflect the new registry state.
        requireContext().startService(
            ServerService.unloadOneIntent(requireContext(), modelId),
        )
    }

    private fun startDownload(remote: ModelAdapter.Row.Remote) {
        if (remote.gated && prefs.hfToken.isNullOrBlank()) {
            Toast.makeText(
                requireContext(),
                "This model is gated. Set your HF token in Settings first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        adapter.setDownloadingId(remote.id)
        downloader.start(
            scope = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope,
            repo = remote.repo,
            filename = remote.filename,
            hfToken = prefs.hfToken,
        )
    }

    private fun observeDownload() {
        viewLifecycleOwner.lifecycleScope.launch {
            downloader.progress.collectLatest { s ->
                when (s) {
                    is ModelDownloader.State.Idle -> {
                        downloadStatus.visibility = View.GONE
                    }
                    is ModelDownloader.State.Running -> {
                        adapter.setDownloadingId(s.id)
                        downloadStatus.visibility = View.VISIBLE
                        val totalMib = (s.total / (1024L * 1024L)).coerceAtLeast(0)
                        val doneMib = s.bytesDone / (1024L * 1024L)
                        downloadStatus.text = getString(
                            R.string.download_progress_fmt,
                            s.pct, doneMib.toInt(), totalMib.toInt(),
                        )
                    }
                    is ModelDownloader.State.Done -> {
                        downloadStatus.visibility = View.VISIBLE
                        downloadStatus.text = getString(R.string.download_done)
                        adapter.setDownloadingId(null)
                        modelRepo.refresh()
                    }
                    is ModelDownloader.State.Failed -> {
                        downloadStatus.visibility = View.VISIBLE
                        downloadStatus.text = getString(R.string.download_failed_fmt, s.message)
                        adapter.setDownloadingId(null)
                    }
                    is ModelDownloader.State.Cancelled -> {
                        downloadStatus.visibility = View.VISIBLE
                        downloadStatus.text = getString(R.string.download_cancelled)
                        adapter.setDownloadingId(null)
                    }
                }
            }
        }
    }

    private fun renderLoadedChips(ids: List<String>) {
        loadedChips.removeAllViews()
        if (ids.isEmpty()) {
            noLoadedHint.visibility = View.VISIBLE
            return
        }
        noLoadedHint.visibility = View.GONE
        ids.forEach { id ->
            val chip = Chip(requireContext())
            chip.text = id
            chip.isCheckable = false
            loadedChips.addView(chip)
        }
    }
}
