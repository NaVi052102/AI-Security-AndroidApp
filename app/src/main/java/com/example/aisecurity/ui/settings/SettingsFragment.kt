package com.example.aisecurity.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ai.SecurityEnforcer
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val etWarningMessage = view.findViewById<EditText>(R.id.etWarningMessage)
        val etContactNumber = view.findViewById<EditText>(R.id.etContactNumber)
        val btnSave = view.findViewById<Button>(R.id.btnSaveMessage)

        // Find our two new distinct buttons
        val btnTestNuclear = view.findViewById<Button>(R.id.btnTestNuclear)
        val btnTestPersistent = view.findViewById<Button>(R.id.btnTestPersistent)

        val switchTheme = view.findViewById<SwitchMaterial>(R.id.switchTheme)

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // --- THEME LOGIC ---
        val isNightMode = prefs.getBoolean("dark_mode", true)
        switchTheme.isChecked = isNightMode

        switchTheme.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()

            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        // --- RECOVERY INFO LOGIC ---
        etWarningMessage.setText(prefs.getString("warning_msg", "This device has been reported lost or stolen. It is currently locked and tracking its location."))
        etContactNumber.setText(prefs.getString("contact_num", ""))

        btnSave.setOnClickListener {
            prefs.edit()
                .putString("warning_msg", etWarningMessage.text.toString())
                .putString("contact_num", etContactNumber.text.toString())
                .apply()
            Toast.makeText(requireContext(), "Recovery Info Saved!", Toast.LENGTH_SHORT).show()
        }

        // --- BUTTON 1: NUCLEAR LOCKDOWN ---
        btnTestNuclear.setOnClickListener {
            Toast.makeText(requireContext(), "Deploying Nuclear Lockdown...", Toast.LENGTH_SHORT).show()
            val enforcer = SecurityEnforcer(requireContext())
            enforcer.lockDevice("Manual Developer Test")
        }

        // --- BUTTON 2: PERSISTENT LOCK TEST ---
        btnTestPersistent.setOnClickListener {
            Toast.makeText(requireContext(), "Launching Safe Lockdown Test...", Toast.LENGTH_SHORT).show()
            val intent = Intent(requireContext(), PersistentLockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }

        return view
    }
}