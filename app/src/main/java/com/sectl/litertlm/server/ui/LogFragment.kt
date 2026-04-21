package com.sectl.litertlm.server.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sectl.litertlm.server.R
import com.sectl.litertlm.server.RequestLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LogFragment : Fragment() {
    private lateinit var logView: TextView

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_log, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logView = view.findViewById(R.id.requestLog)

        viewLifecycleOwner.lifecycleScope.launch {
            RequestLog.entries.collectLatest { list ->
                logView.text = if (list.isEmpty()) {
                    "(no requests yet)"
                } else {
                    list.joinToString("\n") { it.format() }
                }
            }
        }
    }
}
