package com.sectl.litertlm.server.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sectl.litertlm.server.BuildConfig
import com.sectl.litertlm.server.Prefs
import com.sectl.litertlm.server.R

class AboutFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_about, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val version = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        view.findViewById<TextView>(R.id.aboutVersion).text = version

        view.findViewById<MaterialButton>(R.id.sourceButton).setOnClickListener {
            openUrl(Prefs.PROJECT_URL)
        }
        view.findViewById<MaterialButton>(R.id.licensesButton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace((view.parent as ViewGroup).id, LicensesFragment())
                .addToBackStack("licenses")
                .commit()
        }

        wireCryptoTabs(view)
    }

    private fun wireCryptoTabs(root: View) {
        val tabs = root.findViewById<TabLayout>(R.id.cryptoTabs)
        val panel = root.findViewById<View>(R.id.cryptoPanel)
        val labelTv = panel.findViewById<TextView>(R.id.cryptoLabel)
        val qrIv = panel.findViewById<ImageView>(R.id.cryptoQr)
        val addrTv = panel.findViewById<TextView>(R.id.cryptoAddress)
        val copyBtn = panel.findViewById<MaterialButton>(R.id.cryptoCopy)

        Prefs.CRYPTO_ADDRESSES.forEach { tip ->
            tabs.addTab(tabs.newTab().setText(tip.symbol))
        }

        fun show(index: Int) {
            val tip = Prefs.CRYPTO_ADDRESSES[index]
            labelTv.text = tip.label
            addrTv.text = tip.address
            runCatching { generateQrBitmap(tip.uriScheme + tip.address, sizePx = 480) }
                .onSuccess { qrIv.setImageBitmap(it) }
            copyBtn.setOnClickListener {
                val cm = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("${tip.symbol} address", tip.address))
                Toast.makeText(
                    requireContext(),
                    getString(R.string.about_crypto_copied_fmt, tip.symbol),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = show(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        show(0)
    }

    // ZXing produces a BitMatrix; we walk it into a Bitmap ourselves so we
    // don't need the zxing-android-embedded wrapper.
    private fun generateQrBitmap(payload: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), "No browser to open $url", Toast.LENGTH_SHORT).show()
        }
    }

}
