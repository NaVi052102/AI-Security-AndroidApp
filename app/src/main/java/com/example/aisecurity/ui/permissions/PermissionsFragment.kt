package com.example.aisecurity.ui.permissions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.aisecurity.R

class PermissionsFragment : Fragment() {

    private lateinit var tvStatusAccessibility: TextView
    private lateinit var tvStatusUsage: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_permissions, container, false)

        tvStatusAccessibility = view.findViewById(R.id.tvStatusAccessibility)
        tvStatusUsage = view.findViewById(R.id.tvStatusUsage)

        val btnGrantAccessibility = view.findViewById<Button>(R.id.btnGrantAccessibility)
        val btnGrantUsage = view.findViewById<Button>(R.id.btnGrantUsage)

        btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnGrantUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsState()
    }

    private fun checkPermissionsState() {
        // Check Accessibility
        if (isAccessibilitySettingsOn(requireContext())) {
            tvStatusAccessibility.text = "GRANTED"
            tvStatusAccessibility.setTextColor(android.graphics.Color.parseColor("#34C759")) // Green
            tvStatusAccessibility.setBackgroundColor(android.graphics.Color.parseColor("#1A34C759"))
        } else {
            tvStatusAccessibility.text = "MISSING"
            tvStatusAccessibility.setTextColor(android.graphics.Color.parseColor("#FF3B30")) // Red
            tvStatusAccessibility.setBackgroundColor(android.graphics.Color.parseColor("#1AFF3B30"))
        }

        // Check Usage Stats
        val appOps = requireContext().getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            requireContext().packageName
        )

        if (mode == android.app.AppOpsManager.MODE_ALLOWED) {
            tvStatusUsage.text = "GRANTED"
            tvStatusUsage.setTextColor(android.graphics.Color.parseColor("#34C759"))
            tvStatusUsage.setBackgroundColor(android.graphics.Color.parseColor("#1A34C759"))
        } else {
            tvStatusUsage.text = "MISSING"
            tvStatusUsage.setTextColor(android.graphics.Color.parseColor("#FF3B30"))
            tvStatusUsage.setBackgroundColor(android.graphics.Color.parseColor("#1AFF3B30"))
        }
    }

    private fun isAccessibilitySettingsOn(mContext: Context): Boolean {
        var accessibilityEnabled = 0
        val service = mContext.packageName + "/" + "com.example.aisecurity.ai.TouchDynamicsService" // Make sure this matches your service path!
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                mContext.applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) { }

        val mStringColonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                mContext.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    val accessibilityService = mStringColonSplitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}