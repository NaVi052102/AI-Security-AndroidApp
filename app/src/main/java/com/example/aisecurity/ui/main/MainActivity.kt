package com.example.aisecurity.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.example.aisecurity.ui.map.LocationTrackingService
import com.example.aisecurity.R
import com.example.aisecurity.ui.biometrics.BiometricsFragment
import com.example.aisecurity.ui.bluetooth.BluetoothFragment
import com.example.aisecurity.ui.dashboard.DashboardFragment
import com.example.aisecurity.ui.logs.LogsFragment
import com.example.aisecurity.ui.map.MapFragment
import com.example.aisecurity.ui.permissions.PermissionsFragment
import com.example.aisecurity.ui.proximity.ProximityFragment
import com.example.aisecurity.ui.settings.SettingsFragment
import com.example.aisecurity.ui.settings.AccountSettingsFragment
import com.example.aisecurity.ui.help.HelpFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView


class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

    private val FOREGROUND_LOC_REQ = 101
    private val BACKGROUND_LOC_REQ = 102
    private val NOTIFICATION_REQ = 103

    private lateinit var dashboardFragment: Fragment
    private lateinit var biometricsFragment: Fragment
    private lateinit var proximityFragment: Fragment
    private lateinit var mapFragment: Fragment
    private lateinit var settingsFragment: Fragment
    private lateinit var accountSettingsFragment: Fragment
    private lateinit var bluetoothFragment: Fragment
    private lateinit var permissionsFragment: Fragment
    private lateinit var logsFragment: Fragment
    private lateinit var helpFragment: Fragment

    private lateinit var activeFragment: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isNightMode = prefs.getBoolean("dark_mode", true)

        if (isNightMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        setContentView(R.layout.activity_main)

        checkNotificationsAndForeground()

        drawerLayout = findViewById(R.id.drawer_layout)
        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val sideNav = findViewById<NavigationView>(R.id.nav_view_sidebar)

        // =========================================================
        // BULLETPROOF FRAGMENT RESTORATION
        // =========================================================
        if (savedInstanceState == null) {
            dashboardFragment = DashboardFragment()
            biometricsFragment = BiometricsFragment()
            proximityFragment = ProximityFragment()
            mapFragment = MapFragment()
            settingsFragment = SettingsFragment()
            accountSettingsFragment = AccountSettingsFragment()
            bluetoothFragment = BluetoothFragment()
            permissionsFragment = PermissionsFragment()
            logsFragment = LogsFragment()
            helpFragment = HelpFragment()

            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragment_container, helpFragment, "help").hide(helpFragment)
                add(R.id.fragment_container, logsFragment, "logs").hide(logsFragment)
                add(R.id.fragment_container, permissionsFragment, "permissions").hide(permissionsFragment)
                add(R.id.fragment_container, bluetoothFragment, "bluetooth").hide(bluetoothFragment)
                add(R.id.fragment_container, settingsFragment, "settings").hide(settingsFragment)
                add(R.id.fragment_container, accountSettingsFragment, "account").hide(accountSettingsFragment)
                add(R.id.fragment_container, mapFragment, "map").hide(mapFragment)
                add(R.id.fragment_container, proximityFragment, "proximity").hide(proximityFragment)
                add(R.id.fragment_container, biometricsFragment, "biometrics").hide(biometricsFragment)
                add(R.id.fragment_container, dashboardFragment, "dashboard")
            }.commit()

            activeFragment = dashboardFragment
            topAppBar.title = "Command Center"
        } else {
            dashboardFragment = supportFragmentManager.findFragmentByTag("dashboard") ?: DashboardFragment()
            biometricsFragment = supportFragmentManager.findFragmentByTag("biometrics") ?: BiometricsFragment()
            proximityFragment = supportFragmentManager.findFragmentByTag("proximity") ?: ProximityFragment()
            mapFragment = supportFragmentManager.findFragmentByTag("map") ?: MapFragment()
            settingsFragment = supportFragmentManager.findFragmentByTag("settings") ?: SettingsFragment()
            accountSettingsFragment = supportFragmentManager.findFragmentByTag("account") ?: AccountSettingsFragment()
            bluetoothFragment = supportFragmentManager.findFragmentByTag("bluetooth") ?: BluetoothFragment()
            permissionsFragment = supportFragmentManager.findFragmentByTag("permissions") ?: PermissionsFragment()
            logsFragment = supportFragmentManager.findFragmentByTag("logs") ?: LogsFragment()
            helpFragment = supportFragmentManager.findFragmentByTag("help") ?: HelpFragment()

            activeFragment = supportFragmentManager.fragments.firstOrNull { !it.isHidden && it.tag != null } ?: dashboardFragment
        }

        // --- Theme Engine (Kept Same) ---
        val actualDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        var isCurrentlyDark = actualDarkMode
        val themeMenuItem = sideNav.menu.findItem(R.id.side_dark_mode)
        val customSwitchContainer = themeMenuItem?.actionView?.findViewById<FrameLayout>(R.id.custom_theme_switch)
        val customSwitchThumb = themeMenuItem?.actionView?.findViewById<ImageView>(R.id.custom_theme_thumb)

        fun updateSwitchUI(isDark: Boolean, animate: Boolean) {
            val thumbTranslation = if (isDark) 20f * resources.displayMetrics.density else 0f
            val trackColor = if (isDark) android.graphics.Color.parseColor("#1A1D24") else android.graphics.Color.parseColor("#E5E7EB")
            val thumbIcon = if (isDark) R.drawable.ic_moon_filled else R.drawable.ic_sun_filled

            themeMenuItem?.setIcon(if (isDark) R.drawable.ic_moon_outline else R.drawable.ic_sun_outline)
            customSwitchContainer?.background?.mutate()?.setTint(trackColor)

            if (animate) {
                customSwitchThumb?.animate()?.translationX(thumbTranslation)?.rotationBy(360f)?.setDuration(350)
                    ?.withEndAction { customSwitchThumb.setImageResource(thumbIcon) }?.start()
            } else {
                customSwitchThumb?.translationX = thumbTranslation
                customSwitchThumb?.setImageResource(thumbIcon)
            }
        }

        updateSwitchUI(isCurrentlyDark, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (actualDarkMode) {
            window.statusBarColor = android.graphics.Color.parseColor("#000000")
            window.navigationBarColor = android.graphics.Color.parseColor("#000000")
            window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#000000"))
            drawerLayout.setBackgroundColor(android.graphics.Color.parseColor("#000000"))
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        } else {
            window.statusBarColor = android.graphics.Color.parseColor("#FFFFFF")
            window.navigationBarColor = android.graphics.Color.parseColor("#FFFFFF")
            window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
            drawerLayout.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        }

        topAppBar.setNavigationOnClickListener { drawerLayout.open() }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> { switchFragment(dashboardFragment); topAppBar.title = "Command Center" }
                R.id.nav_biometrics -> { switchFragment(biometricsFragment); topAppBar.title = "Biometrics Engine" }
                R.id.nav_proximity -> { switchFragment(proximityFragment); topAppBar.title = "Proximity Radar" }
                R.id.nav_map -> { switchFragment(mapFragment); topAppBar.title = "Tracker Map" }
            }
            true
        }

        sideNav.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.side_settings -> { switchFragment(settingsFragment); topAppBar.title = "Settings" }
                R.id.side_account -> { switchFragment(accountSettingsFragment); topAppBar.title = "Account Settings" }
                R.id.side_bluetooth -> { switchFragment(bluetoothFragment); topAppBar.title = "Connections" }
                R.id.side_permissions -> { switchFragment(permissionsFragment); topAppBar.title = "App Permissions" }
                R.id.side_logs -> { switchFragment(logsFragment); topAppBar.title = "Security Audit Logs" }
                R.id.side_help -> { switchFragment(helpFragment); topAppBar.title = "Help & Support" }
                R.id.side_dark_mode -> {
                    isCurrentlyDark = !isCurrentlyDark
                    prefs.edit().putBoolean("dark_mode", isCurrentlyDark).apply()
                    updateSwitchUI(isCurrentlyDark, true)
                    window.decorView.postDelayed({
                        if (isCurrentlyDark) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }, 400)
                    return@setNavigationItemSelectedListener true
                }
            }
            drawerLayout.close()
            true
        }
    }

    private fun switchFragment(targetFragment: Fragment) {
        if (activeFragment == targetFragment) return
        supportFragmentManager.beginTransaction().hide(activeFragment).show(targetFragment).commit()
        activeFragment = targetFragment
    }

    // =========================================================
    // THE UNBREAKABLE PERMISSION CHAIN
    // =========================================================

    private fun checkNotificationsAndForeground() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), FOREGROUND_LOC_REQ)
        } else {
            checkBackgroundLocation()
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                AlertDialog.Builder(this)
                    .setTitle("Background Tracking Required")
                    .setMessage("To track this phone when it is stolen or the app is closed, you MUST select 'Allow all the time' in the next screen.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND_LOC_REQ)
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }
        checkBatteryOptimizations()
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Disable Battery Killer")
                    .setMessage("Your phone will kill the security tracker to save battery. Please click 'Allow' to let BioGuard run forever.")
                    .setPositiveButton("Fix Now") { _, _ ->
                        intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                        startLocationService()
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }
        startLocationService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == FOREGROUND_LOC_REQ) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkBackgroundLocation()
            } else {
                Toast.makeText(this, "App cannot function without Basic Location.", Toast.LENGTH_LONG).show()
            }
        }
        else if (requestCode == BACKGROUND_LOC_REQ) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkBatteryOptimizations()
            } else {
                Toast.makeText(this, "WARNING: App will stop tracking if minimized!", Toast.LENGTH_LONG).show()
                checkBatteryOptimizations()
            }
        }
    }

    private fun startLocationService() {
        val serviceIntent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}