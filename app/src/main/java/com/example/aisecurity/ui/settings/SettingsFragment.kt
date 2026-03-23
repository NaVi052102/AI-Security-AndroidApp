package com.example.aisecurity.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.SecurityAdminReceiver
import com.example.aisecurity.ai.SecurityEnforcer

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val radioGroup = view.findViewById<RadioGroup>(R.id.securityRadioGroup)
        val radioGhost = view.findViewById<RadioButton>(R.id.radioGhostMode)
        val radioNuclear = view.findViewById<RadioButton>(R.id.radioNuclearMode)
        val btnTest = view.findViewById<Button>(R.id.btnTestSecurity)

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val currentLevel = prefs.getString("security_level", "GHOST_MODE")

        if (currentLevel == "NUCLEAR") {
            radioNuclear.isChecked = true
        } else {
            radioGhost.isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newLevel = if (checkedId == R.id.radioNuclearMode) "NUCLEAR" else "GHOST_MODE"
            prefs.edit().putString("security_level", newLevel).apply()
            Toast.makeText(requireContext(), "Security level saved: $newLevel", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            val selectedLevel = prefs.getString("security_level", "GHOST_MODE")

            // If they chose Nuclear, check if we have permission first!
            if (selectedLevel == "NUCLEAR") {
                val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(requireContext(), SecurityAdminReceiver::class.java)

                if (!dpm.isAdminActive(adminComponent)) {
                    Toast.makeText(requireContext(), "Permission Required for Nuclear Mode", Toast.LENGTH_LONG).show()
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to force-lock the screen during a threat.")
                    startActivity(intent)
                    return@setOnClickListener // Stop the test until they grant permission
                }
            }

            Toast.makeText(requireContext(), "Triggering Manual Lockdown...", Toast.LENGTH_SHORT).show()
            val enforcer = SecurityEnforcer(requireContext())
            enforcer.lockDevice("Manual Developer Test")
        }

        return view
    }
}