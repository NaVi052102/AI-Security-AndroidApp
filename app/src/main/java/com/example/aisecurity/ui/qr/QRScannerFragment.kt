package com.example.aisecurity.ui.qr

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.main.MainActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

@Suppress("DEPRECATION")
class QRScannerFragment : Fragment() {

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents == null) {
            showSentryToast("Scan Cancelled")
        } else {
            val scannedData = result.contents

            if (scannedData.startsWith("https://bioguard-efb32.web.app/track")) {
                val intent = Intent(requireContext(), MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(scannedData)
                }
                startActivity(intent)
            } else {
                showSentryToast("Invalid Sentry QR Code")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_qr_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnScan = view.findViewById<Button>(R.id.btnLaunchScanner)
        val imgCameraIcon = view.findViewById<ImageView>(R.id.imgCameraIcon)

        val isNightMode = isDarkMode()

        if (isNightMode) {
            imgCameraIcon.setColorFilter(Color.parseColor("#FFFFFF"))
        } else {
            imgCameraIcon.setColorFilter(Color.parseColor("#0F172A"))
        }

        applyBluetoothButtonTheme(btnScan)

        btnScan.setOnClickListener {
            val options = ScanOptions()
            options.setPrompt("Scan Smartwatch QR Code")
            options.setBeepEnabled(true)
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setCaptureActivity(PortraitCaptureActivity::class.java)
            options.setOrientationLocked(true)

            barcodeLauncher.launch(options)
        }
    }

    // ==========================================
    // 🚨 PREMIUM CUSTOM TOAST BUILDER
    // ==========================================
    private fun showSentryToast(message: String) {
        val toast = Toast(requireContext())
        toast.duration = Toast.LENGTH_SHORT

        // Build the dark glass pill background
        val customLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#12151C")) // Deep Dark Background
                setStroke(3, Color.parseColor("#3B82F6")) // Sentry Blue Rim
            }
            setPadding(50, 30, 50, 30)
        }

        // 🚨 INJECTING YOUR EXISTING XML LOGO
        val icon = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_sentry_half_gold) // Points directly to your file!
            layoutParams = LinearLayout.LayoutParams(60, 75).apply {
                setMargins(0, 0, 30, 0)
            }
        }

        // Inject the text
        val textView = TextView(requireContext()).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // Assemble and display
        customLayout.addView(icon)
        customLayout.addView(textView)
        toast.view = customLayout
        toast.show()
    }

    private fun isDarkMode(): Boolean {
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyBluetoothButtonTheme(button: Button) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            colors = intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#2563EB"))
        }
        button.setTextColor(Color.WHITE)
        button.background = bg
    }
}