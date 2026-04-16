package com.example.aisecurity.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.aisecurity.R
import com.example.aisecurity.ble.WatchManager
import com.example.aisecurity.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardFragment : Fragment() {

    // 🚨 STATE TRACKERS FOR INFINITE ANIMATION
    private var currentAnimState = -1
    private var pulseAnimX: android.animation.ObjectAnimator? = null
    private var pulseAnimY: android.animation.ObjectAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Text Elements
        val tvSystemPercentage = view.findViewById<TextView>(R.id.tvSystemPercentage)
        val tvSystemStatus = view.findViewById<TextView>(R.id.tvSystemStatus)
        val tvActionTitle = view.findViewById<TextView>(R.id.tvActionTitle)
        val tvActiveTitle = view.findViewById<TextView>(R.id.tvActiveTitle)
        val activeContainer = view.findViewById<LinearLayout>(R.id.activeContainer)
        val tvPermissionCount = view.findViewById<TextView>(R.id.tvPermissionCount)
        val imgSentryLogo = view.findViewById<android.widget.ImageView>(R.id.imgSentryLogo)

        // Action Cards (The "To-Do" List)
        val actionCardPermissions = view.findViewById<LinearLayout>(R.id.actionCardPermissions)
        val actionCardSettings = view.findViewById<LinearLayout>(R.id.actionCardSettings)
        val actionCardWatch = view.findViewById<LinearLayout>(R.id.actionCardWatch)
        val actionCardAi = view.findViewById<LinearLayout>(R.id.actionCardAi)
        val actionCardSms = view.findViewById<LinearLayout>(R.id.actionCardSms)

        // Active Rows (The "Completed" List)
        val activeRowPermissions = view.findViewById<LinearLayout>(R.id.activeRowPermissions)
        val activeRowSettings = view.findViewById<LinearLayout>(R.id.activeRowSettings)
        val activeRowWatch = view.findViewById<LinearLayout>(R.id.activeRowWatch)
        val activeRowAi = view.findViewById<LinearLayout>(R.id.activeRowAi)
        val activeRowSms = view.findViewById<LinearLayout>(R.id.activeRowSms)

        // Routing Logic
        view.findViewById<TextView>(R.id.btnAuthorize).setOnClickListener { (requireActivity() as MainActivity).navigateToFromDashboard("PERMISSIONS") }
        view.findViewById<TextView>(R.id.btnConfigure).setOnClickListener { (requireActivity() as MainActivity).navigateToFromDashboard("SETTINGS") }
        view.findViewById<TextView>(R.id.btnConnect).setOnClickListener { (requireActivity() as MainActivity).navigateToFromDashboard("BLUETOOTH") }
        view.findViewById<TextView>(R.id.btnTrain).setOnClickListener { (requireActivity() as MainActivity).navigateToFromDashboard("BIOMETRICS") }
        view.findViewById<TextView>(R.id.btnAddContact).setOnClickListener { (requireActivity() as MainActivity).navigateToFromDashboard("ACCOUNT") }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                if (!isAdded) return@launch

                val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

                // 1. Gather Data States
                val aiReady = prefs.getBoolean("ai_ready", false)
                val isWatchConnected = WatchManager.isConnected.value == true
                val trustedContactsJson = prefs.getString("trusted_contacts_json", "[]") ?: "[]"
                val hasContacts = trustedContactsJson != "[]" && trustedContactsJson.length > 2

                // 2. Calculate Permissions
                var permsGranted = 0
                val totalPerms = 6

                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) permsGranted++
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) permsGranted++
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) permsGranted++
                else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) permsGranted++
                if (Settings.canDrawOverlays(requireContext())) permsGranted++

                val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) permsGranted++

                val enabledServices = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                if (enabledServices?.contains(requireContext().packageName) == true) permsGranted++

                // 3. Check Settings Configuration
                val defenseActive = prefs.getBoolean("protocol_defense_active", true)
                val sirenActive = prefs.getBoolean("protocol_siren", true)
                val isSettingsOptimal = defenseActive && sirenActive

                withContext(Dispatchers.Main) {
                    var score = 0
                    var actionsRequired = 0

                    if (permsGranted >= totalPerms) {
                        score += 20
                        actionCardPermissions.visibility = View.GONE
                        activeRowPermissions.visibility = View.VISIBLE
                    } else {
                        actionsRequired++
                        tvPermissionCount.text = "Only $permsGranted of $totalPerms allowed."
                        actionCardPermissions.visibility = View.VISIBLE
                        activeRowPermissions.visibility = View.GONE
                    }

                    if (isSettingsOptimal) {
                        score += 20
                        actionCardSettings.visibility = View.GONE
                        activeRowSettings.visibility = View.VISIBLE
                    } else {
                        actionsRequired++
                        actionCardSettings.visibility = View.VISIBLE
                        activeRowSettings.visibility = View.GONE
                    }

                    if (isWatchConnected) {
                        score += 20
                        actionCardWatch.visibility = View.GONE
                        activeRowWatch.visibility = View.VISIBLE
                    } else {
                        actionsRequired++
                        actionCardWatch.visibility = View.VISIBLE
                        activeRowWatch.visibility = View.GONE
                    }

                    if (aiReady) {
                        score += 20
                        actionCardAi.visibility = View.GONE
                        activeRowAi.visibility = View.VISIBLE
                    } else {
                        actionsRequired++
                        actionCardAi.visibility = View.VISIBLE
                        activeRowAi.visibility = View.GONE
                    }

                    if (hasContacts) {
                        score += 20
                        actionCardSms.visibility = View.GONE
                        activeRowSms.visibility = View.VISIBLE
                    } else {
                        actionsRequired++
                        actionCardSms.visibility = View.VISIBLE
                        activeRowSms.visibility = View.GONE
                    }

                    // --- Master UI Updates ---
                    tvSystemPercentage.text = "$score%"

                    // 🚨 THE METALLIC PROGRESSION ENGINE
                    val newState = when {
                        score == 100 -> 2 // Top Gold, Bottom Gold
                        score > 0 -> 1    // Top Silver, Bottom Gold
                        else -> 0         // Top Silver, Bottom Silver
                    }

                    // Only trigger if the score crosses a threshold, preventing stutter!
                    if (newState != currentAnimState) {
                        currentAnimState = newState

                        pulseAnimX?.cancel()
                        pulseAnimY?.cancel()

                        // Remove all color filters to let the pure metal shine!
                        imgSentryLogo.clearColorFilter()

                        when (newState) {
                            2 -> { // MAXIMUM SECURITY (100%): Full Gold + Snap Animation
                                imgSentryLogo.setImageResource(R.drawable.ic_sentry_gold)
                                imgSentryLogo.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).withEndAction {
                                    imgSentryLogo.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                                }.start()
                            }
                            1 -> { // BUILDING SECURITY (1-99%): Half Gold + Smooth Breathing
                                imgSentryLogo.setImageResource(R.drawable.ic_sentry_half_gold)
                                pulseAnimX = android.animation.ObjectAnimator.ofFloat(imgSentryLogo, "scaleX", 1f, 1.04f).apply {
                                    duration = 1200
                                    repeatCount = android.animation.ObjectAnimator.INFINITE
                                    repeatMode = android.animation.ObjectAnimator.REVERSE
                                    start()
                                }
                                pulseAnimY = android.animation.ObjectAnimator.ofFloat(imgSentryLogo, "scaleY", 1f, 1.04f).apply {
                                    duration = 1200
                                    repeatCount = android.animation.ObjectAnimator.INFINITE
                                    repeatMode = android.animation.ObjectAnimator.REVERSE
                                    start()
                                }
                            }
                            0 -> { // VULNERABLE (0%): Full Silver + Rapid Heartbeat
                                imgSentryLogo.setImageResource(R.drawable.ic_sentry_silver)
                                pulseAnimX = android.animation.ObjectAnimator.ofFloat(imgSentryLogo, "scaleX", 1f, 0.9f).apply {
                                    duration = 400
                                    repeatCount = android.animation.ObjectAnimator.INFINITE
                                    repeatMode = android.animation.ObjectAnimator.REVERSE
                                    start()
                                }
                                pulseAnimY = android.animation.ObjectAnimator.ofFloat(imgSentryLogo, "scaleY", 1f, 0.9f).apply {
                                    duration = 400
                                    repeatCount = android.animation.ObjectAnimator.INFINITE
                                    repeatMode = android.animation.ObjectAnimator.REVERSE
                                    start()
                                }
                            }
                        }
                    }

                    if (actionsRequired == 0) {
                        tvActionTitle.visibility = View.GONE
                        tvSystemStatus.text = "DEVICE PROTECTED"
                        tvSystemStatus.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        tvActionTitle.visibility = View.VISIBLE
                        tvSystemStatus.text = "SETUP INCOMPLETE: $actionsRequired ITEMS"
                        tvSystemStatus.setTextColor(Color.parseColor("#EF4444"))
                    }

                    if (score == 0) {
                        tvActiveTitle.visibility = View.GONE
                        activeContainer.visibility = View.GONE
                    } else {
                        tvActiveTitle.visibility = View.VISIBLE
                        activeContainer.visibility = View.VISIBLE
                    }

                    if (activity is MainActivity) {
                        (activity as MainActivity).updateSidebarHeaderStatus(actionsRequired)
                    }
                }
                delay(1000)
            }
        }
    }
}