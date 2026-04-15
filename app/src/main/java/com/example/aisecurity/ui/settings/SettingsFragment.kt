package com.example.aisecurity.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.LockOverlayService
import com.example.aisecurity.ai.SecurityEnforcer

class SettingsFragment : Fragment() {

    private var countdownTimerOverlay: CountDownTimer? = null
    private var countdownTimerEnforcer: CountDownTimer? = null

    @SuppressLint("BatteryLife")
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
        applyDangerButton(btnDemoOverlay, isNightMode)
        applyDangerButton(btnDemoEnforcer, isNightMode)

        // 1. AI SENSITIVITY LOGIC
        val currentSensitivity = prefs.getInt("ai_sensitivity", 1)
        seekAi.progress = currentSensitivity
        updateAiDesc(currentSensitivity, tvAiDesc)

        seekAi.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAiDesc(progress, tvAiDesc)
                prefs.edit().putInt("ai_sensitivity", progress).apply()
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

        // Listeners
        switchSiren.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("protocol_siren", isChecked).apply() }
        switchGps.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("protocol_gps", isChecked).apply() }

        // Link to Trusted Contacts
        tvManageContactsLink.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this@SettingsFragment)
                .add(R.id.fragment_container, TrustedContactsFragment())
                .addToBackStack("TrustedContacts")
                .commit()
        }

        switchDefense.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("protocol_defense_active", isChecked).apply()
            rgDefenseType.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        rgDefenseType.setOnCheckedChangeListener { _, checkedId ->
            val type = if (checkedId == R.id.rbScreenOff) "SCREEN_OFF" else "OVERLAY"
            prefs.edit().putString("protocol_defense_type", type).apply()
        }

        switchStealth.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("protocol_stealth", isChecked).apply()
            if (isChecked) Toast.makeText(requireContext(), "Stealth Active: Dial *#8888# to open.", Toast.LENGTH_LONG).show()
        }

        // 3A. TEST RED SCREEN OVERLAY
        btnDemoOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), "Grant 'Display over other apps' to test.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = "package:${requireContext().packageName}".toUri() })
                return@setOnClickListener
            }
            btnDemoOverlay.isEnabled = false
            applyDisabledButton(btnDemoOverlay, isNightMode)

            countdownTimerOverlay = object : CountDownTimer(10000, 1000) {
                override fun onTick(millis: Long) {
                    btnDemoOverlay.text = "LAUNCHING IN ${millis / 1000}S... (PRESS TO CANCEL)"
                    btnDemoOverlay.isEnabled = true
                    btnDemoOverlay.setOnClickListener { cancelOverlayDemo(btnDemoOverlay, isNightMode) }
                }
                override fun onFinish() {
                    btnDemoOverlay.text = "SYSTEM LOCKED"
                    val lockIntent = Intent(requireContext(), LockOverlayService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(lockIntent)
                    } else {
                        requireContext().startService(lockIntent)
                    }
                    view.postDelayed({ cancelOverlayDemo(btnDemoOverlay, isNightMode) }, 2000)
                }
            }.start()
        }

        // 3B. TEST SCREEN-OFF ENFORCER
        btnDemoEnforcer.setOnClickListener {
            btnDemoEnforcer.isEnabled = false
            applyDisabledButton(btnDemoEnforcer, isNightMode)

            countdownTimerEnforcer = object : CountDownTimer(10000, 1000) {
                override fun onTick(millis: Long) {
                    btnDemoEnforcer.text = "ENFORCING IN ${millis / 1000}S... (PRESS TO CANCEL)"
                    btnDemoEnforcer.isEnabled = true
                    btnDemoEnforcer.setOnClickListener { cancelEnforcerDemo(btnDemoEnforcer, isNightMode) }
                }
                override fun onFinish() {
                    btnDemoEnforcer.text = "SCREEN OFF ENFORCED"
                    SecurityEnforcer(requireContext()).lockDevice("Threat Simulation Demo")
                    view.postDelayed({ cancelEnforcerDemo(btnDemoEnforcer, isNightMode) }, 2000)
                }
            }.start()
        }

        return view
    }

    private fun cancelOverlayDemo(btn: Button, isNightMode: Boolean) {
        countdownTimerOverlay?.cancel()
        btn.text = "TEST RED SCREEN OVERLAY"
        applyDangerButton(btn, isNightMode)
        btn.setOnClickListener { btn.performClick() } // Reset listener
    }

    private fun cancelEnforcerDemo(btn: Button, isNightMode: Boolean) {
        countdownTimerEnforcer?.cancel()
        btn.text = "TEST SCREEN-OFF ENFORCER"
        applyDangerButton(btn, isNightMode)
        btn.setOnClickListener { btn.performClick() } // Reset listener
    }

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
            bg.colors = intArrayOf(Color.parseColor("#3F000F"), Color.parseColor("#1A0004"))
            bg.setStroke(3, Color.parseColor("#EF4444"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.colors = intArrayOf(Color.parseColor("#FEF2F2"), Color.parseColor("#FEE2E2"))
            bg.setStroke(3, Color.parseColor("#EF4444"))
            button.setTextColor(Color.parseColor("#7F1D1D"))
        }
        button.background = bg
    }

    private fun applyDisabledButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
        }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#1E293B"))
            bg.setStroke(2, Color.parseColor("#334155"))
            button.setTextColor(Color.parseColor("#475569"))
        } else {
            bg.setColor(Color.parseColor("#F1F5F9"))
            bg.setStroke(2, Color.parseColor("#CBD5E1"))
            button.setTextColor(Color.parseColor("#94A3B8"))
        }
        button.background = bg
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownTimerOverlay?.cancel()
        countdownTimerEnforcer?.cancel()
    }
}