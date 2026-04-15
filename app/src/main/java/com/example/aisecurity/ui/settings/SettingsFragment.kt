package com.example.aisecurity.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.LockOverlayService
import com.example.aisecurity.ai.SecurityEnforcer

class SettingsFragment : Fragment() {

    @SuppressLint("BatteryLife", "SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // Bind Elements
        val seekAi = view.findViewById<SeekBar>(R.id.seekAiSensitivity)
        val tvAiDesc = view.findViewById<TextView>(R.id.tvAiDesc)

        val switchSiren = view.findViewById<SwitchCompat>(R.id.switchSiren)
        val switchGps = view.findViewById<SwitchCompat>(R.id.switchGps)
        val tvManageContactsLink = view.findViewById<TextView>(R.id.tvManageContactsLink)

        val switchDefense = view.findViewById<SwitchCompat>(R.id.switchDefense)
        val rgDefenseType = view.findViewById<RadioGroup>(R.id.rgDefenseType)
        val rbOverlay = view.findViewById<RadioButton>(R.id.rbOverlay)
        val rbScreenOff = view.findViewById<RadioButton>(R.id.rbScreenOff)

        val switchStealth = view.findViewById<SwitchCompat>(R.id.switchStealth)

        val btnDemoOverlay = view.findViewById<Button>(R.id.btnDemoOverlay)
        val btnDemoEnforcer = view.findViewById<Button>(R.id.btnDemoEnforcer)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Setup standard buttons
        applyDangerButton(btnDemoOverlay, isNightMode)
        applyDangerButton(btnDemoEnforcer, isNightMode)

        // 1. AI SENSITIVITY LOGIC
        val currentSensitivity = prefs.getInt("ai_sensitivity", 1)
        seekAi.progress = currentSensitivity
        updateAiDesc(currentSensitivity, tvAiDesc)

        seekAi.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAiDesc(progress, tvAiDesc)
                prefs.edit { putInt("ai_sensitivity", progress) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 2. TOGGLE PROTOCOLS LOGIC
        switchSiren.isChecked = prefs.getBoolean("protocol_siren", true)
        switchGps.isChecked = prefs.getBoolean("protocol_gps", true)

        // Setup Active Defense RadioGroup
        switchDefense.isChecked = prefs.getBoolean("protocol_defense_active", true)
        rgDefenseType.visibility = if (switchDefense.isChecked) View.VISIBLE else View.GONE

        val savedDefenseType = prefs.getString("protocol_defense_type", "OVERLAY")
        if (savedDefenseType == "SCREEN_OFF") rbScreenOff.isChecked = true else rbOverlay.isChecked = true

        switchStealth.isChecked = prefs.getBoolean("protocol_stealth", false)

        // Listeners using safe Kotlin KTX edit blocks
        switchSiren.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("protocol_siren", isChecked) } }
        switchGps.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("protocol_gps", isChecked) } }

        // Link to Trusted Contacts
        tvManageContactsLink.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this@SettingsFragment)
                .add(R.id.fragment_container, TrustedContactsFragment())
                .addToBackStack("TrustedContacts")
                .commit()
        }

        switchDefense.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("protocol_defense_active", isChecked) }
            rgDefenseType.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        rgDefenseType.setOnCheckedChangeListener { _, checkedId ->
            val type = if (checkedId == R.id.rbScreenOff) "SCREEN_OFF" else "OVERLAY"
            prefs.edit { putString("protocol_defense_type", type) }
        }

        switchStealth.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("protocol_stealth", isChecked) }
            if (isChecked) Toast.makeText(requireContext(), "Stealth Active: Dial *#8888# to open.", Toast.LENGTH_LONG).show()
        }

        // ==========================================
        // 3. FLAWLESS MODAL TEST TRIGGERS
        // ==========================================
        btnDemoOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), "Grant 'Display over other apps' to test.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = "package:${requireContext().packageName}".toUri() })
                return@setOnClickListener
            }
            showTestWarningDialog(isOverlay = true, isNightMode = isNightMode)
        }

        btnDemoEnforcer.setOnClickListener {
            showTestWarningDialog(isOverlay = false, isNightMode = isNightMode)
        }

        return view
    }

    @SuppressLint("SetTextI18n")
    private fun showTestWarningDialog(isOverlay: Boolean, isNightMode: Boolean) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_lockdown_test, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
        val tvBypassInstructions = dialogView.findViewById<TextView>(R.id.tvBypassInstructions)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnProceed)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        // Set Dynamic Warning & Exit Instructions
        if (isOverlay) {
            tvMessage.text = "You are about to deploy the active Red Screen Overlay. This is a persistent graphical block designed to completely disable intruder interaction."
            tvBypassInstructions.text = "Use your registered Biometrics (Fingerprint/Face) or enter your Master PIN into the secure prompt to dismiss the overlay and regain control."
        } else {
            tvMessage.text = "You are about to test the Screen-Off Enforcer. This protocol instantly cuts power to the display to neutralize unauthorized access and tracking."
            tvBypassInstructions.text = "Simply press your phone's physical power button to wake the screen, and unlock the device using your standard Android lock screen mechanism."
        }

        // Apply Glassmorphism
        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) {
                setColor("#FA0F172A".toColorInt())
                setStroke(2, "#334155".toColorInt())
            } else {
                setColor("#FAFFFFFF".toColorInt())
                setStroke(2, "#CBD5E1".toColorInt())
            }
        }
        dialogRoot.background = dialogBg

        applyDangerButton(btnProceed, isNightMode)
        applyGhostButton(btnCancel, isNightMode)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnProceed.setOnClickListener {
            dialog.dismiss()

            // Instantly execute requested test (SDK Version Checks omitted safely per lint standards)
            if (isOverlay) {
                val lockIntent = Intent(requireContext(), LockOverlayService::class.java)
                requireContext().startForegroundService(lockIntent)
            } else {
                SecurityEnforcer(requireContext()).lockDevice("Threat Simulation Demo")
            }
        }

        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun updateAiDesc(progress: Int, tv: TextView) {
        when (progress) {
            0 -> tv.text = "AI Disabled. App will only lock upon manual trigger or Bluetooth disconnect."
            1 -> tv.text = "Moderate threshold. Tolerates slight variations in swipe speed and app usage."
            2 -> tv.text = "Maximum security. Locks instantly upon abnormal swipe patterns or unusual app transitions."
        }
    }

    // ==========================================
    // THE GLASSMORPHISM ENGINE
    // ==========================================
    private fun applyDangerButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        if (isNightMode) {
            bg.colors = intArrayOf("#3F000F".toColorInt(), "#1A0004".toColorInt())
            bg.setStroke(3, "#EF4444".toColorInt())
            button.setTextColor("#FFFFFF".toColorInt())
        } else {
            bg.colors = intArrayOf("#FEF2F2".toColorInt(), "#FEE2E2".toColorInt())
            bg.setStroke(3, "#EF4444".toColorInt())
            button.setTextColor("#7F1D1D".toColorInt())
        }
        button.background = bg
    }

    private fun applyGhostButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        if (isNightMode) {
            bg.colors = intArrayOf("#0F172A".toColorInt(), "#020617".toColorInt())
            bg.setStroke(3, "#334155".toColorInt())
            button.setTextColor("#94A3B8".toColorInt())
        } else {
            bg.colors = intArrayOf("#F8FAFC".toColorInt(), "#E2E8F0".toColorInt())
            bg.setStroke(3, "#CBD5E1".toColorInt())
            button.setTextColor("#64748B".toColorInt())
        }
        button.background = bg
    }
}