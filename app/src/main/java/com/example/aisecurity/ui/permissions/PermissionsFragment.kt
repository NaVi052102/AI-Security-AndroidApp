package com.example.aisecurity.ui.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.google.android.material.card.MaterialCardView

class PermissionsFragment : Fragment() {

    private lateinit var tvStatusAccessibility: TextView
    private lateinit var tvStatusUsage: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cardAccessibility = view.findViewById<MaterialCardView>(R.id.cardAccessibility)
        val cardUsageStats = view.findViewById<MaterialCardView>(R.id.cardUsageStats)
        tvStatusAccessibility = view.findViewById(R.id.tvStatusAccessibility)
        tvStatusUsage = view.findViewById(R.id.tvStatusUsage)

        // Teleport to Android Accessibility Settings when clicked
        cardAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Teleport to Android Usage Access Settings when clicked
        cardUsageStats.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    // We check permissions in onResume so it updates immediately when the user comes back from Settings
    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        // Check Accessibility
        if (isAccessibilityEnabled()) {
            tvStatusAccessibility.text = "✅ GRANTED"
            tvStatusAccessibility.setTextColor(Color.parseColor("#4CAF50")) // Green
        } else {
            tvStatusAccessibility.text = "❌ MISSING - TAP TO FIX"
            tvStatusAccessibility.setTextColor(Color.parseColor("#F44336")) // Red
        }

        // Check Usage Access
        if (isUsageAccessEnabled()) {
            tvStatusUsage.text = "✅ GRANTED"
            tvStatusUsage.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvStatusUsage.text = "❌ MISSING - TAP TO FIX"
            tvStatusUsage.setTextColor(Color.parseColor("#F44336"))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        var accessibilityEnabled = 0
        try {
            accessibilityEnabled = Settings.Secure.getInt(requireContext().contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) { }

        if (accessibilityEnabled == 1) {
            val services = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return services?.contains(requireContext().packageName) == true
        }
        return false
    }

    private fun isUsageAccessEnabled(): Boolean {
        val appOps = requireContext().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), requireContext().packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}