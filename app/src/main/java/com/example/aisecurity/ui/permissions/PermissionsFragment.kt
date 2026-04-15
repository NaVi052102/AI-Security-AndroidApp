package com.example.aisecurity.ui.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.SecurityAdminReceiver

class PermissionsFragment : Fragment() {

    private lateinit var tvStatusAccessibility: TextView
    private lateinit var tvStatusUsage: TextView
    private lateinit var tvStatusAdmin: TextView
    private lateinit var tvStatusOverlay: TextView
    private lateinit var tvStatusLocation: TextView
    private lateinit var tvStatusBattery: TextView

    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnGrantUsage: Button
    private lateinit var btnGrantAdmin: Button
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantLocation: Button
    private lateinit var btnGrantBattery: Button

    @SuppressLint("BatteryLife")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_permissions, container, false)

        // Bind Status Text Views
        tvStatusAccessibility = view.findViewById(R.id.tvStatusAccessibility)
        tvStatusUsage = view.findViewById(R.id.tvStatusUsage)
        tvStatusAdmin = view.findViewById(R.id.tvStatusAdmin)
        tvStatusOverlay = view.findViewById(R.id.tvStatusOverlay)
        tvStatusLocation = view.findViewById(R.id.tvStatusLocation)
        tvStatusBattery = view.findViewById(R.id.tvStatusBattery)

        // Bind Buttons (These IDs now perfectly match the fixed XML!)
        btnGrantAccessibility = view.findViewById(R.id.btnGrantAccessibility)
        btnGrantUsage = view.findViewById(R.id.btnGrantUsage)
        btnGrantAdmin = view.findViewById(R.id.btnGrantAdmin)
        btnGrantOverlay = view.findViewById(R.id.btnGrantOverlay)
        btnGrantLocation = view.findViewById(R.id.btnGrantLocation)
        btnGrantBattery = view.findViewById(R.id.btnGrantBattery)

        // ==========================================
        // BUTTON ROUTING LOGIC
        // ==========================================

        btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnGrantUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnGrantAdmin.setOnClickListener {
            val componentName = ComponentName(requireContext(), SecurityAdminReceiver::class.java)
            if (isDeviceAdminActive()) {
                val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.removeActiveAdmin(componentName)

                Toast.makeText(requireContext(), "System Lockdown Control Deactivated", Toast.LENGTH_SHORT).show()
                updateStatusUI(tvStatusAdmin, btnGrantAdmin, false)
                view.postDelayed({ if (isAdded) checkPermissionsState() }, 500)
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to instantly turn off the screen and lock the device if an intruder is detected.")
                }
                startActivity(intent)
            }
        }

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        }

        btnGrantLocation.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        }

        btnGrantBattery.setOnClickListener {
            if (isIgnoringBatteryOptimizations()) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        view?.postDelayed({ if (isAdded) checkPermissionsState() }, 300)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            view?.postDelayed({ if (isAdded) checkPermissionsState() }, 300)
        }
    }

    private fun checkPermissionsState() {
        updateStatusUI(tvStatusAccessibility, btnGrantAccessibility, isAccessibilitySettingsOn(requireContext()))
        updateStatusUI(tvStatusUsage, btnGrantUsage, hasUsageStatsPermission())
        updateStatusUI(tvStatusAdmin, btnGrantAdmin, isDeviceAdminActive())
        updateStatusUI(tvStatusOverlay, btnGrantOverlay, Settings.canDrawOverlays(requireContext()))
        updateStatusUI(tvStatusLocation, btnGrantLocation, hasBackgroundLocationPermission())
        updateStatusUI(tvStatusBattery, btnGrantBattery, isIgnoringBatteryOptimizations())
    }

    // ==========================================
    // THE GLASSMORPHISM UI ENGINE
    // ==========================================
    private fun updateStatusUI(textView: TextView, button: Button, isGranted: Boolean) {

        // Check if the device is currently in Dark Mode or Light Mode
        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val btnBackground = GradientDrawable()
        btnBackground.shape = GradientDrawable.RECTANGLE
        btnBackground.cornerRadius = 1000f // Creates the perfect Pill shape

        val badgeBackground = GradientDrawable()
        badgeBackground.cornerRadius = 50f

        if (isGranted) {
            // --- THE "ACTIVE" GHOST STATE ---
            textView.text = "ACTIVE"
            textView.setTextColor(Color.parseColor("#10B981")) // Emerald Green
            badgeBackground.setColor(Color.parseColor("#1A10B981"))

            btnBackground.orientation = GradientDrawable.Orientation.TOP_BOTTOM

            if (isNightMode) {
                // Dark Mode Ghost Button
                btnBackground.colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
                btnBackground.setStroke(3, Color.parseColor("#334155")) // Slate outline
                button.setTextColor(Color.parseColor("#94A3B8")) // Muted silver text
            } else {
                // Light Mode Ghost Button
                btnBackground.colors = intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#E2E8F0"))
                btnBackground.setStroke(3, Color.parseColor("#CBD5E1")) // Light silver outline
                button.setTextColor(Color.parseColor("#64748B")) // Muted dark text
            }

            button.text = "MANAGE ACCESS"

        } else {
            // --- THE "INACTIVE" GLASSY STATE ---
            textView.text = "ACTION REQUIRED"
            textView.setTextColor(Color.parseColor("#EF4444")) // Rose Red
            badgeBackground.setColor(Color.parseColor("#1AEF4444"))

            btnBackground.orientation = GradientDrawable.Orientation.TOP_BOTTOM

            if (isNightMode) {
                // Dark Mode: Elegant Glossy Gold Button
                btnBackground.colors = intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#080E1A"))
                btnBackground.setStroke(4, Color.parseColor("#D4AF37")) // Gold Texture Rim
                button.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                // Light Mode: Sleek Pearlescent/White Button with Vibrant Blue Rim
                btnBackground.colors = intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F1F5F9"))
                btnBackground.setStroke(4, Color.parseColor("#2563EB")) // Crisp Blue Rim
                button.setTextColor(Color.parseColor("#1E293B")) // Deep Navy text for contrast
            }

            button.text = "AUTHORIZE"
        }

        textView.background = badgeBackground
        button.background = btnBackground
    }

    // ==========================================
    // PERMISSION CHECKERS
    // ==========================================

    private fun isAccessibilitySettingsOn(mContext: Context): Boolean {
        var accessibilityEnabled = 0
        val service = mContext.packageName + "/" + "com.example.aisecurity.ai.TouchDynamicsService"
        try {
            accessibilityEnabled = Settings.Secure.getInt(mContext.applicationContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) { }

        val mStringColonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(mContext.applicationContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    if (mStringColonSplitter.next().equals(service, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = requireContext().getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), requireContext().packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(requireContext(), SecurityAdminReceiver::class.java)
        return dpm.isAdminActive(componentName)
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        return hasFine && hasBackground
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }
}