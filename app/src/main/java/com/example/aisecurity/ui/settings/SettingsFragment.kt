package com.example.aisecurity.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.LockOverlayService
import com.example.aisecurity.ai.SecurityEnforcer

class SettingsFragment : Fragment() {

    @SuppressLint("BatteryLife") // Bypasses the strict Android IDE warning
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val etWarningMessage = view.findViewById<EditText>(R.id.etWarningMessage)
        val etContactNumber = view.findViewById<EditText>(R.id.etContactNumber)
        val btnSave = view.findViewById<Button>(R.id.btnSaveMessage)
        val btnTestNuclear = view.findViewById<Button>(R.id.btnTestNuclear)
        val btnTestPersistent = view.findViewById<Button>(R.id.btnTestPersistent)

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // --- LOAD RECOVERY INFO ---
        etWarningMessage.setText(prefs.getString("warning_msg", "This device has been reported lost or stolen."))
        etContactNumber.setText(prefs.getString("contact_num", ""))

        // --- SAVE LOGIC ---
        btnSave.setOnClickListener {
            prefs.edit {
                putString("warning_msg", etWarningMessage.text.toString())
                putString("contact_num", etContactNumber.text.toString())
            }
            Toast.makeText(requireContext(), "Recovery Info Saved!", Toast.LENGTH_SHORT).show()

            val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${requireContext().packageName}".toUri() // Fixed URI warning
                }
                startActivity(intent)
            }
        }

        btnTestNuclear.setOnClickListener {
            SecurityEnforcer(requireContext()).lockDevice("Manual Developer Test")
        }

        // --- FIXED TEST BUTTON ---
        btnTestPersistent.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = "package:${requireContext().packageName}".toUri()
                }
                startActivity(intent)
            } else {
                // IGNITE THE LOCKDOWN OVERLAY!
                val lockIntent = Intent(requireContext(), LockOverlayService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(lockIntent)
                } else {
                    requireContext().startService(lockIntent)
                }
            }
        }

        return view
    }
}