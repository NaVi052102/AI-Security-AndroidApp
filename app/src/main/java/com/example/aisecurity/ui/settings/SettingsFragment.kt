package com.example.aisecurity.ui.settings

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

        // --- NEW: Find the Secret Knock Button ---
        val btnSecretKnock = view.findViewById<MaterialButton>(R.id.btnSecretKnock)

        // 1. NAVIGATION LOGIC
        btnManageContacts.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, TrustedContactsFragment())
                .addToBackStack(null)
                .commit()
        }

        // --- NEW: TRIGGER THE RECORDING DIALOG ---
        btnSecretKnock?.setOnClickListener {
            showRecordKnockDialog()
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

    private fun showRecordKnockDialog() {
        val taps = mutableListOf<Long>()
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("RECORD SECRET KNOCK")
        builder.setMessage("Tap the black box below in your desired rhythm (Minimum 4 taps)")

        // 1. Create a container so the dialog doesn't stretch infinitely
        val container = android.widget.FrameLayout(requireContext())

        // 2. Safely convert 200 DP to Pixels so it scales perfectly on all screens
        val scale = resources.displayMetrics.density
        val heightInPx = (200 * scale + 0.5f).toInt()
        val marginInPx = (24 * scale + 0.5f).toInt()

        val inputView = View(requireContext()).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                heightInPx
            ).apply {
                setMargins(marginInPx, marginInPx, marginInPx, marginInPx)
            }
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))

            setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    taps.add(System.currentTimeMillis())
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)

                    // --- NEW: Visual Flash Feedback ---
                    v.setBackgroundColor(android.graphics.Color.parseColor("#3A3A3A"))
                    v.postDelayed({ v.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A")) }, 100)

                    v.performClick()
                }
                true
            }
        }

        container.addView(inputView)
        builder.setView(container)

        builder.setPositiveButton("SAVE") { dialog, _ ->
            if (taps.size >= 4) {
                savePattern(taps)
                android.widget.Toast.makeText(context, "Pattern Secured", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                android.widget.Toast.makeText(context, "Too short! Try again.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("CANCEL") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }

    private fun savePattern(taps: List<Long>) {
        val intervals = mutableListOf<Long>()
        for (i in 1 until taps.size) {
            intervals.add(taps[i] - taps[i-1])
        }
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("secret_knock_pattern", intervals.joinToString(",")).apply()
    }
}