package com.sectl.litertlm.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sectl.litertlm.server.ui.AboutFragment
import com.sectl.litertlm.server.ui.LogFragment
import com.sectl.litertlm.server.ui.ModelsFragment
import com.sectl.litertlm.server.ui.ServerFragment
import com.sectl.litertlm.server.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_AUTOSTART = "autostart"
    }

    private lateinit var prefs: Prefs
    private lateinit var modelRepository: ModelRepository
    private lateinit var pager: ViewPager2

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        modelRepository = ModelRepository(applicationContext)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            // subtitle already set in XML; nothing else to configure.
        }

        pager = findViewById(R.id.pager)
        pager.adapter = PagerAdapter(this)
        // Disable horizontal swipe: with a bottom nav users navigate by
        // tapping, not swiping. Swipe-to-change-page on top of lists reads
        // as janky because vertical scrolls sometimes cross the threshold.
        pager.isUserInputEnabled = false
        // Leave room so the floating bottom nav doesn't cover page content.
        // Height ~= 64dp nav + 18dp margin + safety = 96dp.
        val bottomPadPx = (96f * resources.displayMetrics.density).toInt()
        pager.setPadding(0, 0, 0, bottomPadPx)
        pager.clipToPadding = false

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            pager.currentItem = when (item.itemId) {
                R.id.nav_server   -> 0
                R.id.nav_models   -> 1
                R.id.nav_settings -> 2
                R.id.nav_log      -> 3
                R.id.nav_about    -> 4
                else -> 0
            }
            true
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.menu.getItem(position).isChecked = true
            }
        })

        maybeRequestNotificationPermission()
        modelRepository.refresh()

        // If a critical permission is missing, land the user on the Settings
        // tab (index 2) — the banner at the top + inline permission cards
        // explain exactly what's wrong and what to do about it.
        if (!Permissions.allCriticalGranted(this)) {
            pager.setCurrentItem(2, false)
        }

        // If invoked with the debug extra, start the server after the UI is
        // on screen. The auto-restore logic below is independent.
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && !ServerState.state.value.running) {
            pager.post { autoStartServer() }
        }

        maybeAutoRestore()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && !ServerState.state.value.running) {
            pager.post { autoStartServer() }
        }
    }

    override fun onResume() {
        super.onResume()
        modelRepository.refresh()
    }

    private fun autoStartServer() {
        val port = prefs.port
        ContextCompat.startForegroundService(this, ServerService.startIntent(this, port))
    }

    private fun maybeAutoRestore() {
        if (!prefs.autoRestoreEnabled) return
        val lastPath = prefs.lastModelPath ?: return
        val file = java.io.File(lastPath)
        if (!file.exists() || file.length() == 0L) {
            prefs.lastModelPath = null
            return
        }
        if (EngineHolder.current?.isLoaded == true) return

        if (!ServerState.state.value.running) {
            ContextCompat.startForegroundService(this, ServerService.startIntent(this, prefs.port))
        }
        pager.postDelayed({
            startService(ServerService.loadModelIntent(this, lastPath, prefs.backendKind))
        }, 300)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val status = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (status != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- pager ---

    class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ServerFragment()
            1 -> ModelsFragment()
            2 -> SettingsFragment()
            3 -> LogFragment()
            else -> AboutFragment()
        }
    }
}
