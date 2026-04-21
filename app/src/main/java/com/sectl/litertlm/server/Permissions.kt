package com.sectl.litertlm.server

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

// NOTE: microphone (RECORD_AUDIO) intentionally NOT listed. The web UI
// push-to-talk runs in the browser and uses getUserMedia, which is a
// browser-scoped permission — the Android app never needs mic access.

/**
 * Single source of truth for the permissions VicinoLLM actually needs and
 * whether they are currently granted.
 *
 * Two reasons to centralise this:
 *   1. Several screens need the same check logic — MainActivity (startup
 *      gate), the dedicated Permissions tab, and the "battery optimization
 *      is killing your server" banner. Duplicating the branches on SDK
 *      version across all of them bit-rots fast.
 *   2. "Granted" is not boolean on every entry. MANAGE_EXTERNAL_STORAGE
 *      is a separate activity flow, battery-whitelist is a PowerManager
 *      query, notifications are version-gated. Having a sealed status
 *      keeps the UI code simple.
 */
object Permissions {

    sealed interface Status {
        /** Permission is granted (or not needed on this OS version). */
        data object Granted : Status

        /** User declined before. Re-asking via the system prompt still works. */
        data object Denied : Status

        /** User declined twice on 11+, or permission requires a Settings detour.
         *  The UI must route to a Settings intent rather than request again. */
        data object NeedsSettings : Status

        /** Not required on this SDK. Shown greyed-out. */
        data object NotApplicable : Status
    }

    /**
     * Machine-readable permission entries. Ordered by importance — the UI
     * lists them in this order, so put the critical-for-function ones first.
     *
     * [critical] = without it, core functionality breaks. Non-critical ones
     * are optional nice-to-haves (mic, /sdcard browsing).
     */
    enum class Entry(
        val titleRes: Int,
        val rationaleRes: Int,
        val critical: Boolean,
    ) {
        NOTIFICATIONS(R.string.perm_notifications_title, R.string.perm_notifications_body, critical = true),
        BATTERY_OPTIMIZATION(R.string.perm_battery_title, R.string.perm_battery_body, critical = true),
        STORAGE(R.string.perm_storage_title, R.string.perm_storage_body, critical = false),
    }

    fun statusOf(context: Context, entry: Entry): Status = when (entry) {
        Entry.NOTIFICATIONS -> notificationStatus(context)
        Entry.BATTERY_OPTIMIZATION -> batteryStatus(context)
        Entry.STORAGE -> storageStatus(context)
    }

    /** True when every `critical` entry returns [Status.Granted]. Used to decide
     *  whether the startup Permissions screen should block or pass through. */
    fun allCriticalGranted(context: Context): Boolean =
        Entry.values().filter { it.critical }.all { statusOf(context, it) is Status.Granted }

    // ---- individual checks --------------------------------------------------

    private fun notificationStatus(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Status.Granted
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) Status.Granted else Status.Denied
    }

    private fun batteryStatus(context: Context): Status {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return Status.NotApplicable
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Status.Granted
        } else {
            // The user always has to visit Settings — there's no prompt API.
            Status.NeedsSettings
        }
    }

    private fun storageStatus(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Pre-Android 11 READ_EXTERNAL_STORAGE is enough.
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            return if (granted) Status.Granted else Status.Denied
        }
        return if (Environment.isExternalStorageManager()) {
            Status.Granted
        } else {
            // Scoped-storage settings intent, no runtime prompt possible.
            Status.NeedsSettings
        }
    }

    // ---- intents for the "Grant" buttons ------------------------------------

    /**
     * Intent to send the user somewhere that can actually satisfy the
     * requested permission. For runtime permissions we return `null` and the
     * caller uses ActivityResultContracts.RequestPermission instead — we
     * can't wrap those in a plain Intent.
     */
    fun settingsIntentFor(context: Context, entry: Entry): Intent? = when (entry) {
        Entry.BATTERY_OPTIMIZATION -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        Entry.STORAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else null
        else -> null
    }

    /** For entries that support the runtime prompt, the permission string to
     *  feed into ActivityResultContracts.RequestPermission. */
    fun runtimePermissionFor(entry: Entry): String? = when (entry) {
        Entry.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else null
        else -> null
    }
}
