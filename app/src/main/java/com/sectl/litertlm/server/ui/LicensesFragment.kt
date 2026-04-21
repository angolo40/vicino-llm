package com.sectl.litertlm.server.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sectl.litertlm.server.R

class LicensesFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_licenses, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val text = runCatching {
            requireContext().assets.open("licenses.txt")
                .bufferedReader()
                .use { it.readText() }
        }.getOrElse { "Unable to load licenses: ${it.message}" }
        view.findViewById<TextView>(R.id.licensesText).text = text
    }
}
