package com.sectl.litertlm.server.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.sectl.litertlm.server.EngineHolder
import com.sectl.litertlm.server.LiteRtLmEngine
import com.sectl.litertlm.server.Permissions
import com.sectl.litertlm.server.Prefs
import com.sectl.litertlm.server.R
import com.sectl.litertlm.server.SearxngClient
import com.sectl.litertlm.server.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private lateinit var prefs: Prefs

    private lateinit var backendChips: ChipGroup
    private lateinit var chipGpu: Chip
    private lateinit var chipCpu: Chip
    private lateinit var autoRestoreSwitch: MaterialSwitch
    private lateinit var banner: TextView

    private lateinit var sliderTemp: Slider
    private lateinit var sliderTopP: Slider
    private lateinit var sliderTopK: Slider
    private lateinit var sliderMaxTok: Slider
    private lateinit var sliderCtx: Slider
    private lateinit var labelTemp: TextView
    private lateinit var labelTopP: TextView
    private lateinit var labelTopK: TextView
    private lateinit var labelMaxTok: TextView
    private lateinit var labelCtx: TextView
    private lateinit var resetBtn: MaterialButton

    private lateinit var hfTokenField: TextInputEditText
    private lateinit var serverKeyField: TextInputEditText

    private lateinit var searxngUrlField: TextInputEditText
    private lateinit var searxngTestButton: MaterialButton
    private lateinit var searxngTestStatus: TextView
    private lateinit var rewriteQuerySwitch: MaterialSwitch

    private lateinit var permissionsSection: LinearLayout
    private lateinit var permBanner: MaterialCardView
    private lateinit var permBannerReview: MaterialButton
    private lateinit var permissionList: LinearLayout
    private lateinit var permOkPill: TextView
    // When true, the user has tapped the OK pill to force-expand the
    // section even though all critical permissions are granted. Reset on
    // view recreation so the default "hidden when OK" behaviour returns.
    private var permSectionForcedOpen = false

    private lateinit var runtimePermLauncher: ActivityResultLauncher<String>
    private lateinit var settingsPermLauncher: ActivityResultLauncher<Intent>

    private lateinit var testPrompt: TextInputEditText
    private lateinit var testButton: MaterialButton
    private lateinit var testProgress: ProgressBar
    private lateinit var testOutput: TextView
    private lateinit var testStats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register here, not in onViewCreated — ActivityResultLauncher
        // registration must happen before STARTED. Violations throw.
        runtimePermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ -> renderPermissions() }
        settingsPermLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { _ -> renderPermissions() }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        bindViews(view)
        wirePermissions()
        wireBackendChips()
        wireAutoRestore()
        wireSecrets()
        wireWebSearch()
        wireSliders()
        wireTestPrompt()

        viewLifecycleOwner.lifecycleScope.launch {
            ServerState.state.collectLatest { snap ->
                testButton.isEnabled = snap.modelPath != null && !snap.loading
            }
        }
    }

    private fun bindViews(v: View) {
        backendChips = v.findViewById(R.id.backendChips)
        chipGpu = v.findViewById(R.id.chipGpu)
        chipCpu = v.findViewById(R.id.chipCpu)
        autoRestoreSwitch = v.findViewById(R.id.autoRestoreSwitch)
        banner = v.findViewById(R.id.autoRestoreBanner)
        sliderTemp = v.findViewById(R.id.sliderTemperature)
        sliderTopP = v.findViewById(R.id.sliderTopP)
        sliderTopK = v.findViewById(R.id.sliderTopK)
        sliderMaxTok = v.findViewById(R.id.sliderMaxTokens)
        sliderCtx = v.findViewById(R.id.sliderContextWindow)
        labelTemp = v.findViewById(R.id.labelTemperature)
        labelTopP = v.findViewById(R.id.labelTopP)
        labelTopK = v.findViewById(R.id.labelTopK)
        labelMaxTok = v.findViewById(R.id.labelMaxTokens)
        labelCtx = v.findViewById(R.id.labelContextWindow)
        resetBtn = v.findViewById(R.id.resetSamplingButton)
        hfTokenField = v.findViewById(R.id.hfTokenField)
        serverKeyField = v.findViewById(R.id.serverKeyField)
        searxngUrlField = v.findViewById(R.id.searxngUrlField)
        searxngTestButton = v.findViewById(R.id.searxngTestButton)
        searxngTestStatus = v.findViewById(R.id.searxngTestStatus)
        rewriteQuerySwitch = v.findViewById(R.id.rewriteQuerySwitch)
        permissionsSection = v.findViewById(R.id.permissionsSection)
        permBanner = v.findViewById(R.id.permBanner)
        permBannerReview = v.findViewById(R.id.permBannerReview)
        permissionList = v.findViewById(R.id.permissionList)
        permOkPill = v.findViewById(R.id.permOkPill)
        testPrompt = v.findViewById(R.id.testPrompt)
        testButton = v.findViewById(R.id.testButton)
        testProgress = v.findViewById(R.id.testProgress)
        testOutput = v.findViewById(R.id.testOutput)
        testStats = v.findViewById(R.id.testStats)
    }

    private fun wireBackendChips() {
        when (prefs.backendKind) {
            LiteRtLmEngine.BackendKind.GPU -> chipGpu.isChecked = true
            LiteRtLmEngine.BackendKind.CPU -> chipCpu.isChecked = true
        }
        backendChips.setOnCheckedStateChangeListener { _, checked ->
            prefs.backendKind = if (R.id.chipCpu in checked) {
                LiteRtLmEngine.BackendKind.CPU
            } else {
                LiteRtLmEngine.BackendKind.GPU
            }
        }
    }

    private fun wireAutoRestore() {
        autoRestoreSwitch.isChecked = prefs.autoRestoreEnabled
        autoRestoreSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoRestoreEnabled = isChecked
            // Flipping the switch is the user's way to reset the circuit
            // breaker after they've switched to a lighter model.
            if (isChecked) prefs.clearAutoRestoreHistory()
            refreshAutoRestoreBanner()
        }
        refreshAutoRestoreBanner()
    }

    private fun refreshAutoRestoreBanner() {
        if (prefs.autoRestoreTripped) {
            banner.visibility = View.VISIBLE
            banner.text = getString(R.string.auto_restore_tripped_hint)
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun wireSecrets() {
        hfTokenField.setText(prefs.hfToken.orEmpty())
        serverKeyField.setText(prefs.serverApiKey.orEmpty())
        // Persist on focus loss / text change. Simple watcher; no debouncing
        // needed because each keystroke just updates SharedPreferences.
        hfTokenField.addTextChangedListener(onTextChanged = { s, _, _, _ ->
            prefs.hfToken = s?.toString().orEmpty().takeIf { it.isNotBlank() }
        })
        serverKeyField.addTextChangedListener(onTextChanged = { s, _, _, _ ->
            prefs.serverApiKey = s?.toString().orEmpty().takeIf { it.isNotBlank() }
        })
    }

    private fun wirePermissions() {
        permBannerReview.setOnClickListener {
            permissionList.parent?.requestChildFocus(permissionList, permissionList)
        }
        permOkPill.setOnClickListener {
            // User wants to look at the granted permissions (e.g. to revoke
            // one). Force-expand the full section; we'll auto-collapse again
            // next time the fragment is recreated.
            permSectionForcedOpen = true
            renderPermissions()
        }
        renderPermissions()
    }

    private fun renderPermissions() {
        if (!isAdded) return
        val allGranted = Permissions.allCriticalGranted(requireContext())

        if (allGranted && !permSectionForcedOpen) {
            // Clean state: collapse everything to the small confirmation pill.
            permissionsSection.visibility = View.GONE
            permOkPill.visibility = View.VISIBLE
            return
        }

        // Something is missing (or user asked to see the list): render the
        // full section and hide the pill to avoid duplicate UI.
        permOkPill.visibility = View.GONE
        permissionsSection.visibility = View.VISIBLE
        PermissionsList.render(permissionList, runtimePermLauncher, settingsPermLauncher)
        permBanner.visibility = if (allGranted) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // Status might have changed while the user was in Android Settings.
        if (::permissionList.isInitialized) renderPermissions()
    }

    private fun wireWebSearch() {
        searxngUrlField.setText(prefs.searxngUrl.orEmpty())
        rewriteQuerySwitch.isChecked = prefs.webSearchRewriteQuery

        searxngUrlField.addTextChangedListener(onTextChanged = { s, _, _, _ ->
            prefs.searxngUrl = s?.toString()?.takeIf { it.isNotBlank() }
            // Any change invalidates the prior status — nudge the user to
            // re-run the test instead of leaving a stale ✓ next to a new URL.
            searxngTestStatus.text = ""
        })
        rewriteQuerySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.webSearchRewriteQuery = checked
        }
        searxngTestButton.setOnClickListener {
            val url = prefs.searxngUrl
            if (url.isNullOrBlank()) {
                searxngTestStatus.text = getString(R.string.searxng_status_empty)
                return@setOnClickListener
            }
            searxngTestStatus.text = getString(R.string.searxng_status_testing)
            searxngTestButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = runCatching { SearxngClient(url).ping() }.getOrDefault(false)
                searxngTestStatus.text = getString(
                    if (ok) R.string.searxng_status_ok else R.string.searxng_status_fail,
                )
                searxngTestButton.isEnabled = true
            }
        }
    }

    private fun wireSliders() {
        sliderTemp.value = prefs.defaultTemperature.coerceIn(0f, 2f)
        sliderTopP.value = prefs.defaultTopP.coerceIn(0f, 1f)
        sliderTopK.value = prefs.defaultTopK.toFloat().coerceIn(1f, 200f)
        sliderMaxTok.value = prefs.defaultMaxTokens.toFloat().coerceIn(16f, 4096f)
        sliderCtx.value = prefs.contextWindow.toFloat().coerceIn(1024f, 32768f)
        refreshSliderLabels()

        sliderTemp.addOnChangeListener { _, v, _ ->
            prefs.defaultTemperature = v
            labelTemp.text = getString(R.string.label_temperature_fmt, v)
        }
        sliderTopP.addOnChangeListener { _, v, _ ->
            prefs.defaultTopP = v
            labelTopP.text = getString(R.string.label_top_p_fmt, v)
        }
        sliderTopK.addOnChangeListener { _, v, _ ->
            prefs.defaultTopK = v.toInt()
            labelTopK.text = getString(R.string.label_top_k_fmt, v.toInt())
        }
        sliderMaxTok.addOnChangeListener { _, v, _ ->
            prefs.defaultMaxTokens = v.toInt()
            labelMaxTok.text = getString(R.string.label_max_tokens_fmt, v.toInt())
        }
        sliderCtx.addOnChangeListener { _, v, _ ->
            prefs.contextWindow = v.toInt()
            labelCtx.text = getString(R.string.label_context_window_fmt, v.toInt())
        }

        resetBtn.setOnClickListener {
            prefs.resetSamplingDefaults()
            sliderTemp.value = 0.8f
            sliderTopP.value = 0.95f
            sliderTopK.value = 40f
            sliderMaxTok.value = 512f
            refreshSliderLabels()
        }
    }

    private fun refreshSliderLabels() {
        labelTemp.text = getString(R.string.label_temperature_fmt, prefs.defaultTemperature)
        labelTopP.text = getString(R.string.label_top_p_fmt, prefs.defaultTopP)
        labelTopK.text = getString(R.string.label_top_k_fmt, prefs.defaultTopK)
        labelMaxTok.text = getString(R.string.label_max_tokens_fmt, prefs.defaultMaxTokens)
        labelCtx.text = getString(R.string.label_context_window_fmt, prefs.contextWindow)
    }

    private fun wireTestPrompt() {
        testButton.setOnClickListener {
            val prompt = testPrompt.text?.toString()?.trim().orEmpty()
            if (prompt.isEmpty()) {
                Toast.makeText(requireContext(), "Type a prompt first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val engine = EngineHolder.current
            if (engine == null || !engine.isLoaded) {
                Toast.makeText(requireContext(), "Load a model first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            testButton.isEnabled = false
            testProgress.visibility = View.VISIBLE
            testOutput.text = ""
            testStats.text = ""

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        engine.infer(prompt, prefs.defaultSampling())
                    }
                    testOutput.text = result.text
                    testStats.text = getString(
                        R.string.test_stats_fmt,
                        result.promptTokens,
                        result.completionTokens,
                        result.latencyMs,
                        result.tokensPerSecond,
                    )
                } catch (t: Throwable) {
                    testOutput.text = getString(
                        R.string.test_error_fmt,
                        t.message ?: t.javaClass.simpleName,
                    )
                } finally {
                    testButton.isEnabled = true
                    testProgress.visibility = View.GONE
                }
            }
        }
    }
}
