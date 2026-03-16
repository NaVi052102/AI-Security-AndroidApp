package com.example.aisecurity.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val btnManageContacts = view.findViewById<MaterialButton>(R.id.btnManageContacts)
        val switchAi = view.findViewById<SwitchMaterial>(R.id.switchAi)
        val switchProximity = view.findViewById<SwitchMaterial>(R.id.switchProximity)

        // 1. NAVIGATION LOGIC
        btnManageContacts.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, TrustedContactsFragment()) // Opens the new list!
                .addToBackStack(null)
                .commit()
        }

        // 2. LOAD SAVED SWITCH STATES
        switchAi.isChecked = prefs.getBoolean("ai_active", true)
        switchProximity.isChecked = prefs.getBoolean("proximity_active", true)

        // 3. SAVE SWITCH STATES WHEN TOGGLED
        switchAi.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("ai_active", isChecked).apply()
        }

        switchProximity.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("proximity_active", isChecked).apply()
        }
    }
}