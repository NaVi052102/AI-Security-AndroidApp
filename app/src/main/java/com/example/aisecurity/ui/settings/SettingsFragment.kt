package com.example.aisecurity.ui.settings

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.SecurityAdminReceiver
import com.example.aisecurity.ai.SecurityEnforcer

class SettingsFragment : Fragment() {

    @SuppressLint("BatteryLife", "SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // 🚨 CRITICAL BUG FIX: Clear stuck dead states!
        prefs.edit().putBoolean("is_fake_dead_state", false).apply()

        val seekPenalty = view.findViewById<SeekBar>(R.id.seekAiSensitivity)
        val tvPenaltyDesc = view.findViewById<TextView>(R.id.tvAiDesc)

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

        val switchStealth = view.findViewById<SwitchCompat>(R.id.switchStealth)
        val switchAutoCaseLock = view.findViewById<SwitchCompat>(R.id.switchAutoCaseLock)

        val switchFakeShutdown = view.findViewById<SwitchCompat>(R.id.switchFakeShutdown)
        val tvFakeShutdownStatus = view.findViewById<TextView>(R.id.tvFakeShutdownStatus)
        val spinnerDeviceStyle = view.findViewById<Spinner>(R.id.spinnerDeviceStyle)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // 1. ANOMALY PENALTY LOGIC
        val currentPenalty = prefs.getInt("ai_anomaly_penalty", 1)
        seekPenalty.progress = currentPenalty
        updatePenaltyDesc(currentPenalty, tvPenaltyDesc)

        seekPenalty.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePenaltyDesc(progress, tvPenaltyDesc)
                prefs.edit { putInt("ai_anomaly_penalty", progress) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 2. PROXIMITY RADAR LOGIC
        switchProximityArmed?.isChecked = prefs.getBoolean("is_proximity_armed", true)

        val warnDist = prefs.getFloat("radar_warning_meters", 2.0f)
        when (warnDist) {
            1.0f -> rbWarn1?.isChecked = true
            3.0f -> rbWarn3?.isChecked = true
            else -> rbWarn2?.isChecked = true
        }

        val lockDist = prefs.getFloat("radar_threshold_meters", 5.0f)
        when (lockDist) {
            3.0f -> rbLock3?.isChecked = true
            4.0f -> rbLock4?.isChecked = true
            else -> rbLock5?.isChecked = true
        }

        val isArmed = switchProximityArmed?.isChecked == true
        rgWarningDist?.alpha = if(isArmed) 1f else 0.5f
        rgLockDist?.alpha = if(isArmed) 1f else 0.5f
        rgWarningDist?.let { for (i in 0 until it.childCount) it.getChildAt(i).isEnabled = isArmed }
        rgLockDist?.let { for (i in 0 until it.childCount) it.getChildAt(i).isEnabled = isArmed }

        switchProximityArmed?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("is_proximity_armed", isChecked) }
            rgWarningDist?.alpha = if(isChecked) 1f else 0.5f
            rgLockDist?.alpha = if(isChecked) 1f else 0.5f
            rgWarningDist?.let { for (i in 0 until it.childCount) it.getChildAt(i).isEnabled = isChecked }
            rgLockDist?.let { for (i in 0 until it.childCount) it.getChildAt(i).isEnabled = isChecked }
        }

        rgWarningDist?.setOnCheckedChangeListener { _, checkedId ->
            if (switchProximityArmed?.isChecked != true) return@setOnCheckedChangeListener
            val dist = when (checkedId) {
                R.id.rbWarn1 -> 1.0f
                R.id.rbWarn3 -> 3.0f
                else -> 2.0f
            }
            prefs.edit { putFloat("radar_warning_meters", dist) }
        }

        rgLockDist?.setOnCheckedChangeListener { _, checkedId ->
            if (switchProximityArmed?.isChecked != true) return@setOnCheckedChangeListener
            val dist = when (checkedId) {
                R.id.rbLock3 -> 3.0f
                R.id.rbLock4 -> 4.0f
                else -> 5.0f
            }
            prefs.edit { putFloat("radar_threshold_meters", dist) }
        }

        // 3. PROTOCOL LOGIC
        switchSiren?.isChecked = prefs.getBoolean("protocol_siren", true)
        switchGps?.isChecked = prefs.getBoolean("protocol_gps", true)

        switchSiren?.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("protocol_siren", isChecked) } }
        switchGps?.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("protocol_gps", isChecked) } }

        tvManageContactsLink?.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this@SettingsFragment)
                .add(R.id.fragment_container, TrustedContactsFragment())
                .addToBackStack("TrustedContacts")
                .commit()
        }

        switchStealth?.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("protocol_stealth", isChecked) } }
        switchAutoCaseLock?.isChecked = prefs.getBoolean("auto_case_lock", false)
        switchAutoCaseLock?.setOnCheckedChangeListener { _, isChecked -> prefs.edit { putBoolean("auto_case_lock", isChecked) } }

        switchFakeShutdown?.isChecked = prefs.getBoolean("enable_fake_shutdown", false)
        switchFakeShutdown?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("enable_fake_shutdown", isChecked) }
            tvFakeShutdownStatus?.text = if (isChecked) "Enabled" else "Disabled"
        }

        // SPINNER LOGIC
        spinnerDeviceStyle?.let { spinner ->
            val styles = arrayOf("Xiaomi / Generic", "Vivo V40 Lite")
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, styles)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            val savedStyle = prefs.getString("fake_shutdown_style", "Xiaomi / Generic")
            val position = styles.indexOf(savedStyle).takeIf { it >= 0 } ?: 0
            spinner.setSelection(position)

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    prefs.edit { putString("fake_shutdown_style", styles[pos]) }
                    if (view is TextView) {
                        view.setTextColor(Color.WHITE)
                        view.textSize = 13f
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        // =========================================================================
        // 🚨 COMPILER FIX: Dynamic Identifiers to bypass "Unresolved Reference"
        // =========================================================================
        val pkg = requireContext().packageName
        val idOverlay = resources.getIdentifier("rbOverlay", "id", pkg)
        val idScreenOff = resources.getIdentifier("rbScreenOff", "id", pkg)
        val idOrdinary = resources.getIdentifier("rbOrdinaryLock", "id", pkg)

        val savedDefenseType = prefs.getString("protocol_defense_type", "OVERLAY")

        switchDefense?.isChecked = prefs.getBoolean("protocol_defense_active", true)
        rgDefenseType?.visibility = if (switchDefense?.isChecked == true) View.VISIBLE else View.GONE

        // Set active radio button dynamically
        if (idScreenOff != 0 && savedDefenseType == "SCREEN_OFF") rgDefenseType?.check(idScreenOff)
        else if (idOrdinary != 0 && savedDefenseType == "ORDINARY") rgDefenseType?.check(idOrdinary)
        else if (idOverlay != 0) rgDefenseType?.check(idOverlay)

        switchDefense?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("protocol_defense_active", isChecked) }
            rgDefenseType?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        rgDefenseType?.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                idScreenOff -> "SCREEN_OFF"
                idOrdinary -> {
                    if (!isDeviceAdminActive()) requestDeviceAdmin()
                    "ORDINARY"
                }
                else -> "OVERLAY"
            }
            prefs.edit { putString("protocol_defense_type", type) }
        }

        // Bind Demo Buttons Dynamically
        val idBtnOverlay = resources.getIdentifier("btnDemoOverlay", "id", pkg)
        val idBtnEnforcer = resources.getIdentifier("btnDemoEnforcer", "id", pkg)
        val idBtnOrdinary = resources.getIdentifier("btnDemoOrdinary", "id", pkg)

        if (idBtnOverlay != 0) {
            view.findViewById<Button>(idBtnOverlay)?.apply {
                applyDangerButton(this, isNightMode)
                setOnClickListener { showTestWarningDialog("OVERLAY", isNightMode) }
            }
        }
        if (idBtnEnforcer != 0) {
            view.findViewById<Button>(idBtnEnforcer)?.apply {
                applyDangerButton(this, isNightMode)
                setOnClickListener { showTestWarningDialog("SCREEN_OFF", isNightMode) }
            }
        }
        if (idBtnOrdinary != 0) {
            view.findViewById<Button>(idBtnOrdinary)?.apply {
                applyDangerButton(this, isNightMode)
                setOnClickListener { showTestWarningDialog("ORDINARY", isNightMode) }
            }
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
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Sentry requires this to lock the screen.")
        }
        startActivity(intent)
    }

    @SuppressLint("SetTextI18n")
    private fun showTestWarningDialog(testType: String, isNightMode: Boolean) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_lockdown_test, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnProceed)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            setColor(if (isNightMode) "#FA0F172A".toColorInt() else "#FAFFFFFF".toColorInt())
            setStroke(2, if (isNightMode) "#334155".toColorInt() else "#CBD5E1".toColorInt())
        }
        dialogRoot.background = dialogBg

        applyDangerButton(btnProceed, isNightMode)
        applyGhostButton(btnCancel, isNightMode)

        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnProceed.setOnClickListener {
            dialog.dismiss()
            SecurityEnforcer(requireContext()).lockDevice("Demo", testType)
        }
        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun updatePenaltyDesc(progress: Int, tv: TextView?) {
        when (progress) {
            0 -> tv?.text = "Normal Penalty: Detects anomalies with a 5% risk increase per event."
            1 -> tv?.text = "Moderate Penalty: Detects anomalies with a 10% risk increase per event."
            2 -> tv?.text = "Strict Penalty: Detects anomalies with a 15% risk increase per event."
        }
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