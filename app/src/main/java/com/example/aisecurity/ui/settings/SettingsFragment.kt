package com.example.aisecurity.ui.settings

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
import com.example.aisecurity.SecurityAdminReceiver
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

        val seekAi = view.findViewById<SeekBar>(R.id.seekAiSensitivity)
        val tvAiDesc = view.findViewById<TextView>(R.id.tvAiDesc)

        // Proximity Bindings
        val switchProximityArmed = view.findViewById<SwitchCompat>(R.id.switchProximityArmed)
        val rgWarningDist = view.findViewById<RadioGroup>(R.id.rgWarningDist)
        val rbWarn1 = view.findViewById<RadioButton>(R.id.rbWarn1)
        val rbWarn2 = view.findViewById<RadioButton>(R.id.rbWarn2)
        val rbWarn3 = view.findViewById<RadioButton>(R.id.rbWarn3)

        val rgLockDist = view.findViewById<RadioGroup>(R.id.rgLockDist)
        val rbLock3 = view.findViewById<RadioButton>(R.id.rbLock3)
        val rbLock4 = view.findViewById<RadioButton>(R.id.rbLock4)
        val rbLock5 = view.findViewById<RadioButton>(R.id.rbLock5)

        val switchSiren = view.findViewById<SwitchCompat>(R.id.switchSiren)
        val switchGps = view.findViewById<SwitchCompat>(R.id.switchGps)
        val tvManageContactsLink = view.findViewById<TextView>(R.id.tvManageContactsLink)

        val switchDefense = view.findViewById<SwitchCompat>(R.id.switchDefense)
        val rgDefenseType = view.findViewById<RadioGroup>(R.id.rgDefenseType)
        val rbOverlay = view.findViewById<RadioButton>(R.id.rbOverlay)
        val rbScreenOff = view.findViewById<RadioButton>(R.id.rbScreenOff)
        val rbOrdinaryLock = view.findViewById<RadioButton>(R.id.rbOrdinaryLock)

        val switchStealth = view.findViewById<SwitchCompat>(R.id.switchStealth)

        val btnDemoOverlay = view.findViewById<Button>(R.id.btnDemoOverlay)
        val btnDemoEnforcer = view.findViewById<Button>(R.id.btnDemoEnforcer)
        val btnDemoOrdinary = view.findViewById<Button>(R.id.btnDemoOrdinary)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        applyDangerButton(btnDemoOverlay, isNightMode)
        applyDangerButton(btnDemoEnforcer, isNightMode)
        applyDangerButton(btnDemoOrdinary, isNightMode)

        // 1. AI SENSITIVITY (Normal = 0, Moderate = 1, Strict = 2)
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

        // 2. PROXIMITY RADAR MUTUAL EXCLUSION
        switchProximityArmed.isChecked = prefs.getBoolean("is_proximity_armed", true)

        val warnDist = prefs.getFloat("radar_warning_meters", 2.0f)
        when (warnDist) {
            1.0f -> rbWarn1.isChecked = true
            3.0f -> rbWarn3.isChecked = true
            else -> rbWarn2.isChecked = true
        }

        val lockDist = prefs.getFloat("radar_threshold_meters", 5.0f)
        when (lockDist) {
            3.0f -> rbLock3.isChecked = true
            4.0f -> rbLock4.isChecked = true
            else -> rbLock5.isChecked = true
        }

        if (warnDist == 3.0f) rbLock3.isEnabled = false
        if (lockDist == 3.0f) rbWarn3.isEnabled = false

        rgWarningDist.alpha = if(switchProximityArmed.isChecked) 1f else 0.5f
        rgLockDist.alpha = if(switchProximityArmed.isChecked) 1f else 0.5f
        for (i in 0 until rgWarningDist.childCount) rgWarningDist.getChildAt(i).isEnabled = switchProximityArmed.isChecked
        for (i in 0 until rgLockDist.childCount) rgLockDist.getChildAt(i).isEnabled = switchProximityArmed.isChecked

        switchProximityArmed.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("is_proximity_armed", isChecked) }
            rgWarningDist.alpha = if(isChecked) 1f else 0.5f
            rgLockDist.alpha = if(isChecked) 1f else 0.5f
            for (i in 0 until rgWarningDist.childCount) rgWarningDist.getChildAt(i).isEnabled = isChecked
            for (i in 0 until rgLockDist.childCount) rgLockDist.getChildAt(i).isEnabled = isChecked

            if (isChecked) {
                if (rbWarn3.isChecked) rbLock3.isEnabled = false
                if (rbLock3.isChecked) rbWarn3.isEnabled = false
            }
        }

        rgWarningDist.setOnCheckedChangeListener { _, checkedId ->
            if (!switchProximityArmed.isChecked) return@setOnCheckedChangeListener
            val dist = when (checkedId) {
                R.id.rbWarn1 -> 1.0f
                R.id.rbWarn3 -> 3.0f
                else -> 2.0f
            }
            prefs.edit { putFloat("radar_warning_meters", dist) }

            if (dist == 3.0f) {
                rbLock3.isEnabled = false
                if (rbLock3.isChecked) {
                    rbLock4.isChecked = true
                    showSentryToast("Lockdown shifted to 4m to prevent overlap.", false)
                }
            } else {
                rbLock3.isEnabled = true
            }
        }

        rgLockDist.setOnCheckedChangeListener { _, checkedId ->
            if (!switchProximityArmed.isChecked) return@setOnCheckedChangeListener
            val dist = when (checkedId) {
                R.id.rbLock3 -> 3.0f
                R.id.rbLock4 -> 4.0f
                else -> 5.0f
            }
            prefs.edit { putFloat("radar_threshold_meters", dist) }

            if (dist == 3.0f) {
                rbWarn3.isEnabled = false
                if (rbWarn3.isChecked) {
                    rbWarn2.isChecked = true
                    showSentryToast("Warning shifted to 2m to prevent overlap.", false)
                }
            } else {
                rbWarn3.isEnabled = true
            }
        }

        // 3. TOGGLE PROTOCOLS LOGIC
        switchSiren.isChecked = prefs.getBoolean("protocol_siren", true)
        switchGps.isChecked = prefs.getBoolean("protocol_gps", true)

        switchDefense.isChecked = prefs.getBoolean("protocol_defense_active", true)
        rgDefenseType.visibility = if (switchDefense.isChecked) View.VISIBLE else View.GONE

        val savedDefenseType = prefs.getString("protocol_defense_type", "OVERLAY")
        when (savedDefenseType) {
            "SCREEN_OFF" -> rbScreenOff.isChecked = true
            "ORDINARY" -> rbOrdinaryLock.isChecked = true
            else -> rbOverlay.isChecked = true
        }

        switchSiren.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("protocol_siren", isChecked) } }
        switchGps.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("protocol_gps", isChecked) } }

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
            val type = when (checkedId) {
                R.id.rbScreenOff -> "SCREEN_OFF"
                R.id.rbOrdinaryLock -> {
                    if (!isDeviceAdminActive()) {
                        requestDeviceAdmin()
                        showSentryToast("Please activate Device Administrator to enable this lock.", true)
                    }
                    "ORDINARY"
                }
                else -> "OVERLAY"
            }
            prefs.edit { putString("protocol_defense_type", type) }
        }

        switchStealth.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("protocol_stealth", isChecked) }
            if (isChecked) {
                showSentryToast("Stealth Active: Dial *#8888# to open.", isLong = true)
            }
        }

        // ==========================================
        // 4. DYNAMIC MODAL TEST TRIGGERS
        // ==========================================
        btnDemoOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                showSentryToast("Grant 'Display over other apps' to test.", isLong = true)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = "package:${requireContext().packageName}".toUri() })
                return@setOnClickListener
            }
            showTestWarningDialog("OVERLAY", isNightMode)
        }

        btnDemoEnforcer.setOnClickListener {
            showTestWarningDialog("SCREEN_OFF", isNightMode)
        }

        btnDemoOrdinary.setOnClickListener {
            if (!isDeviceAdminActive()) {
                requestDeviceAdmin()
                showSentryToast("Action Blocked: Please enable Device Administrator first.", true)
                return@setOnClickListener
            }
            showTestWarningDialog("ORDINARY", isNightMode)
        }

        return view
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(requireContext(), SecurityAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(requireContext(), SecurityAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Sentry requires this permission to instantly lock the screen during a proximity breach or AI lockdown.")
        }
        startActivity(intent)
    }

    @SuppressLint("SetTextI18n")
    private fun showTestWarningDialog(testType: String, isNightMode: Boolean) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_lockdown_test, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
        val tvBypassInstructions = dialogView.findViewById<TextView>(R.id.tvBypassInstructions)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnProceed)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        when (testType) {
            "OVERLAY" -> {
                tvMessage.text = "You are about to deploy the active Red Screen Overlay. This is a persistent graphical block designed to completely disable intruder interaction."
                tvBypassInstructions.text = "Use your registered Biometrics (Fingerprint/Face) or enter your Master PIN into the secure prompt to dismiss the overlay and regain control."
            }
            "SCREEN_OFF" -> {
                tvMessage.text = "You are about to test the Screen-Off Enforcer. This protocol instantly cuts power to the display to neutralize unauthorized access and tracking."
                tvBypassInstructions.text = "Simply press your phone's physical power button to wake the screen, and unlock the device using your standard Android lock screen mechanism."
            }
            "ORDINARY" -> {
                tvMessage.text = "You are about to test the Ordinary Lock. This will trigger your standard Android lock screen securely."
                tvBypassInstructions.text = "Simply press your phone's physical power button to wake the screen, and unlock using your standard PIN/Pattern."
            }
        }

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
            SecurityEnforcer(requireContext()).lockDevice("Threat Simulation Demo", testType)
        }

        dialog.show()
    }

    // 🚨 UPDATED: Explicit description showing locking thresholds based on the slider
    @SuppressLint("SetTextI18n")
    private fun updateAiDesc(progress: Int, tv: TextView) {
        when (progress) {
            0 -> tv.text = "Normal threshold. Highly tolerant of variations. Locks at 100% Risk."
            1 -> tv.text = "Moderate threshold. Balanced security for daily use. Locks at 80% Risk."
            2 -> tv.text = "Strict security. Highly sensitive to abnormal swipes. Locks at 60% Risk."
        }
    }

    private fun showSentryToast(message: String, isLong: Boolean) {
        val toast = Toast(requireContext())
        toast.duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

        val customLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#12151C"))
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            setPadding(50, 30, 50, 30)
        }

        val icon = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_sentry_half_gold)
            layoutParams = LinearLayout.LayoutParams(60, 75).apply {
                setMargins(0, 0, 30, 0)
            }
        }

        val textView = TextView(requireContext()).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        customLayout.addView(icon)
        customLayout.addView(textView)
        toast.view = customLayout
        toast.show()
    }

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