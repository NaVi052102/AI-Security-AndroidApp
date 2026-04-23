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
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.main.MainActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

class QRScannerFragment : Fragment() {

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents == null) {
            Toast.makeText(requireContext(), "Scan Cancelled", Toast.LENGTH_SHORT).show()
        } else {
            val scannedData = result.contents

            if (scannedData.startsWith("https://bioguard-efb32.web.app/track")) {
                val intent = Intent(requireContext(), MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(scannedData)
                }
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "Invalid Sentry QR Code", Toast.LENGTH_SHORT).show()
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

        // 🚨 Exact button styling from BluetoothFragment
        applyBluetoothButtonTheme(btnScan)

        btnScan.setOnClickListener {
            val options = ScanOptions()
            options.setPrompt("Scan Smartwatch QR Code")
            options.setBeepEnabled(true)

            // 🚨 HIGH-SPEED OPTIMIZATION: Tell scanner to ignore barcodes and ONLY look for QRs
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)

            // 🚨 FORCE PORTRAIT: Target the custom portrait activity we just built
            options.setCaptureActivity(PortraitCaptureActivity::class.java)
            options.setOrientationLocked(true)

            barcodeLauncher.launch(options)
        }
    }

    private fun isDarkMode(): Boolean {
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyBluetoothButtonTheme(button: Button) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            // Exact blue gradient used in your Bluetooth button
            colors = intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#2563EB"))
        }
        button.setTextColor(Color.WHITE)
        button.background = bg
    }
}