package com.sectl.litertlm.server.ui

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.google.android.material.button.MaterialButton
import com.sectl.litertlm.server.Permissions
import com.sectl.litertlm.server.R

/**
 * Renders the three permission rows (Notifications / Battery / Storage) into
 * any host LinearLayout and wires their Grant buttons to the supplied
 * launchers. Kept UI-agnostic so it works inside both SettingsFragment and
 * the startup dialog.
 *
 * The two ActivityResultLaunchers MUST be registered on a Fragment/Activity
 * (they can't be created here) — the caller passes them in and also calls
 * [render] again inside their result callbacks to refresh.
 */
object PermissionsList {

    fun render(
        host: LinearLayout,
        runtimeLauncher: ActivityResultLauncher<String>,
        settingsLauncher: ActivityResultLauncher<Intent>,
    ) {
        val ctx = host.context
        host.removeAllViews()
        val inflater = LayoutInflater.from(ctx)

        Permissions.Entry.values().forEach { entry ->
            val row = inflater.inflate(R.layout.item_permission_row, host, false)
            val title = row.findViewById<TextView>(R.id.permRowTitle)
            val body = row.findViewById<TextView>(R.id.permRowBody)
            val badge = row.findViewById<TextView>(R.id.permRowBadge)
            val status = row.findViewById<TextView>(R.id.permRowStatus)
            val action = row.findViewById<MaterialButton>(R.id.permRowAction)

            title.text = ctx.getString(entry.titleRes)
            body.text = ctx.getString(entry.rationaleRes)
            badge.text = ctx.getString(
                if (entry.critical) R.string.perm_critical_badge else R.string.perm_optional_badge,
            )

            when (Permissions.statusOf(ctx, entry)) {
                Permissions.Status.Granted -> {
                    status.text = ctx.getString(R.string.perm_status_granted)
                    action.visibility = View.GONE
                }
                Permissions.Status.Denied -> {
                    status.text = ctx.getString(R.string.perm_status_denied)
                    action.visibility = View.VISIBLE
                    action.setOnClickListener { launchRuntime(entry, runtimeLauncher) }
                }
                Permissions.Status.NeedsSettings -> {
                    status.text = ctx.getString(R.string.perm_status_needs_settings)
                    action.visibility = View.VISIBLE
                    action.setOnClickListener { launchSettings(ctx, entry, settingsLauncher) }
                }
                Permissions.Status.NotApplicable -> return@forEach
            }
            host.addView(row)
        }
    }

    private fun launchRuntime(
        entry: Permissions.Entry,
        launcher: ActivityResultLauncher<String>,
    ) {
        val perm = Permissions.runtimePermissionFor(entry) ?: return
        launcher.launch(perm)
    }

    private fun launchSettings(
        ctx: Context,
        entry: Permissions.Entry,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        val intent = Permissions.settingsIntentFor(ctx, entry) ?: return
        runCatching { launcher.launch(intent) }
    }
}
