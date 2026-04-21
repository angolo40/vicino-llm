package com.sectl.litertlm.server.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.sectl.litertlm.server.NetworkUtil
import com.sectl.litertlm.server.Prefs
import com.sectl.litertlm.server.R
import com.sectl.litertlm.server.ServerService
import com.sectl.litertlm.server.ServerState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ServerFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var backendChip: Chip
    private lateinit var lanAddress: TextView
    private lateinit var requestCount: TextView
    private lateinit var portField: TextInputEditText
    private lateinit var toggleButton: MaterialButton
    private lateinit var curlHints: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_server, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())

        statusDot = view.findViewById(R.id.statusDot)
        statusText = view.findViewById(R.id.statusText)
        backendChip = view.findViewById(R.id.backendChip)
        lanAddress = view.findViewById(R.id.lanAddress)
        requestCount = view.findViewById(R.id.requestCount)
        portField = view.findViewById(R.id.portField)
        toggleButton = view.findViewById(R.id.toggleButton)
        curlHints = view.findViewById(R.id.curlHints)

        portField.setText(prefs.port.toString())
        lanAddress.text = NetworkUtil.primaryLanIp()?.let { "$it:${portField.text}" } ?: "—"

        toggleButton.setOnClickListener { onToggleServer() }

        viewLifecycleOwner.lifecycleScope.launch {
            ServerState.state.collectLatest { render(it) }
        }
    }

    private fun onToggleServer() {
        val snap = ServerState.state.value
        val ctx = requireContext()
        if (snap.running) {
            ctx.startService(ServerService.stopIntent(ctx))
        } else {
            val port = portField.text.toString().toIntOrNull()?.coerceIn(1024, 65535)
                ?: ServerState.DEFAULT_PORT
            prefs.port = port
            ContextCompat.startForegroundService(ctx, ServerService.startIntent(ctx, port))
        }
    }

    private fun render(snap: ServerState.Snapshot) {
        val lan = snap.host.takeIf { it != "0.0.0.0" } ?: NetworkUtil.primaryLanIp() ?: "—"
        lanAddress.text = "$lan:${snap.port}"
        requestCount.text = "${snap.requestCount} requests"

        val dotDrawable = when {
            snap.loading -> R.drawable.bg_dot_loading
            snap.running -> R.drawable.bg_dot_running
            else -> R.drawable.bg_dot_stopped
        }
        statusDot.setBackgroundResource(dotDrawable)
        statusText.text = getString(
            if (snap.running) R.string.status_running else R.string.status_stopped,
        )

        toggleButton.text = getString(
            if (snap.running) R.string.stop_server else R.string.start_server,
        )
        portField.isEnabled = !snap.running

        backendChip.text = snap.backend.takeIf { it != "none" }?.uppercase() ?: "—"

        curlHints.text = buildString {
            append("curl http://$lan:${snap.port}/health\n\n")
            append("curl http://$lan:${snap.port}/v1/chat/completions \\\n")
            append("  -H 'Content-Type: application/json' \\\n")
            append("  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'")
        }
    }
}
