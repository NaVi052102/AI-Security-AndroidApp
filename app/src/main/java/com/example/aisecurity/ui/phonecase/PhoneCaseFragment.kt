package com.example.aisecurity.ui.phonecase

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ble.CaseManager

class PhoneCaseFragment : Fragment(R.layout.fragment_phone_case) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvStatus = view.findViewById<TextView>(R.id.tvCaseStatus)
        val btnLock = view.findViewById<Button>(R.id.btnLockCase)
        val btnUnlock = view.findViewById<Button>(R.id.btnUnlockCase)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Apply Premium Glassmorphic Styles
        applyGlassButton(btnLock, "#EF4444", isNightMode)
        applyGlassButton(btnUnlock, "#10B981", isNightMode)

        // Observe the Bluetooth Connection Status live
        CaseManager.isConnected.observe(viewLifecycleOwner) { isConnected ->
            if (isConnected) {
                tvStatus.text = "HARDWARE CONNECTED"
                tvStatus.setTextColor(Color.parseColor("#10B981"))
            } else {
                tvStatus.text = "HARDWARE DISCONNECTED"
                tvStatus.setTextColor(Color.parseColor("#EF4444"))
            }
        }

        // Send <CMD:LOCK> to ESP32
        btnLock.setOnClickListener {
            if (CaseManager.isConnected.value == true) {
                CaseManager.triggerLock()
                Toast.makeText(requireContext(), "Locking Case...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Connect the case in Bluetooth settings first.", Toast.LENGTH_SHORT).show()
            }
        }

        // Send <CMD:UNLOCK> to ESP32
        btnUnlock.setOnClickListener {
            if (CaseManager.isConnected.value == true) {
                CaseManager.triggerUnlock()
                Toast.makeText(requireContext(), "Unlocking Case...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Connect the case in Bluetooth settings first.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyGlassButton(button: Button, strokeColor: String, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        if (isNightMode) {
            bg.colors = intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#080E1A"))
            bg.setStroke(4, Color.parseColor(strokeColor))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.colors = intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F1F5F9"))
            bg.setStroke(4, Color.parseColor(strokeColor))
            button.setTextColor(Color.parseColor("#1E293B"))
        }
        button.background = bg
    }
}