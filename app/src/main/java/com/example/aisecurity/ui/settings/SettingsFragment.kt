package com.example.aisecurity.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ai.SecurityEnforcer

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val etWarningMessage = view.findViewById<EditText>(R.id.etWarningMessage)
        val etContactNumber = view.findViewById<EditText>(R.id.etContactNumber)
        val btnSave = view.findViewById<Button>(R.id.btnSaveMessage)
        val btnTest = view.findViewById<Button>(R.id.btnTestSecurity)

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // 1. Load existing saved text (if any)
        etWarningMessage.setText(prefs.getString("warning_msg", "This device has been reported lost or stolen. It is currently locked and tracking its location."))
        etContactNumber.setText(prefs.getString("contact_num", ""))

        // 2. Save Button Logic
        btnSave.setOnClickListener {
            prefs.edit()
                .putString("warning_msg", etWarningMessage.text.toString())
                .putString("contact_num", etContactNumber.text.toString())
                .apply()
            Toast.makeText(requireContext(), "Recovery Info Saved!", Toast.LENGTH_SHORT).show()
        }

        // 3. Test Button Logic
        btnTest.setOnClickListener {
            Toast.makeText(requireContext(), "Deploying Ultimate Lockdown...", Toast.LENGTH_SHORT).show()
            val enforcer = SecurityEnforcer(requireContext())
            enforcer.lockDevice("Manual Developer Test")
        }

        return view
    }
}