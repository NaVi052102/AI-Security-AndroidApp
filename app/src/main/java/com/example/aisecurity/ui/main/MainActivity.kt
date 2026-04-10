package com.example.aisecurity.ui.main

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.biometrics.BiometricsFragment
import com.example.aisecurity.ui.bluetooth.BluetoothFragment
import com.example.aisecurity.ui.dashboard.DashboardFragment
import com.example.aisecurity.ui.logs.LogsFragment
import com.example.aisecurity.ui.map.MapFragment
import com.example.aisecurity.ui.permissions.PermissionsFragment
import com.example.aisecurity.ui.proximity.ProximityFragment
import com.example.aisecurity.ui.SettingsFragment
import com.example.aisecurity.ui.help.HelpFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

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

        drawerLayout = findViewById(R.id.drawer_layout)
        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val sideNav = findViewById<NavigationView>(R.id.nav_view_sidebar)

        // =========================================================
        // DEFINE THE THEME STATE
        // =========================================================
        val actualDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        var isCurrentlyDark = actualDarkMode

        // =========================================================
        // ANIMATED CUSTOM SIDEBAR THEME SWITCH LOGIC
        // =========================================================
        val themeMenuItem = sideNav.menu.findItem(R.id.side_dark_mode)

        // Find our shrunk custom animated switch
        val customSwitchContainer = themeMenuItem?.actionView?.findViewById<FrameLayout>(R.id.custom_theme_switch)
        val customSwitchThumb = themeMenuItem?.actionView?.findViewById<ImageView>(R.id.custom_theme_thumb)

        fun updateSwitchUI(isDark: Boolean, animate: Boolean) {
            val thumbTranslation = if (isDark) 20f * resources.displayMetrics.density else 0f
            val trackColor = if (isDark) android.graphics.Color.parseColor("#1A1D24") else android.graphics.Color.parseColor("#E5E7EB")
            val thumbIcon = if (isDark) R.drawable.ic_moon_filled else R.drawable.ic_sun_filled

            // Dynamically change the native menu icon!
            themeMenuItem?.setIcon(if (isDark) R.drawable.ic_moon_outline else R.drawable.ic_sun_outline)

            customSwitchContainer?.background?.mutate()?.setTint(trackColor)

            if (animate) {
                customSwitchThumb?.animate()
                    ?.translationX(thumbTranslation)
                    ?.rotationBy(360f)
                    ?.setDuration(350)
                    ?.withEndAction { customSwitchThumb.setImageResource(thumbIcon) }
                    ?.start()
            } else {
                customSwitchThumb?.translationX = thumbTranslation
                customSwitchThumb?.setImageResource(thumbIcon)
            }
        }

        // Set initial visual state instantly
        updateSwitchUI(isCurrentlyDark, false)


        // =========================================================
        // THE ULTIMATE STATUS BAR & NAVIGATION BAR NUKE
        // =========================================================
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

        // 1. OPEN SIDEBAR WHEN TRIPLE LINES ARE CLICKED
        topAppBar.setNavigationOnClickListener {
            drawerLayout.open()
        }

        // --- DEFAULT TO THE COMMAND CENTER DASHBOARD ---
        if (savedInstanceState == null) {
            loadFragment(DashboardFragment())
            topAppBar.title = "Command Center"
        }

        // 2. BOTTOM NAVIGATION CLICKS
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    loadFragment(DashboardFragment())
                    topAppBar.title = "Command Center"
                }
                R.id.nav_biometrics -> {
                    loadFragment(BiometricsFragment())
                    topAppBar.title = "Biometrics Engine"
                }
                R.id.nav_proximity -> {
                    loadFragment(ProximityFragment())
                    topAppBar.title = "Proximity Radar"
                }
                R.id.nav_map -> {
                    loadFragment(MapFragment())
                    topAppBar.title = "Tracker Map"
                }
            }
            true
        }

        // 3. SIDEBAR NAVIGATION CLICKS
        sideNav.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.side_settings -> {
                    loadFragment(SettingsFragment())
                    topAppBar.title = "Settings"
                }
                R.id.side_bluetooth -> {
                    loadFragment(BluetoothFragment())
                    topAppBar.title = "Connections"
                }
                R.id.side_permissions -> {
                    loadFragment(PermissionsFragment())
                    topAppBar.title = "App Permissions"
                }
                R.id.side_logs -> {
                    loadFragment(LogsFragment())
                    topAppBar.title = "Security Audit Logs"
                }
                R.id.side_help -> {
                    loadFragment(HelpFragment())
                    topAppBar.title = "Help & Support"
                }
                R.id.side_dark_mode -> {
                    // THE ANIMATED CLICK IS NOW HANDLED NATIVELY HERE!
                    isCurrentlyDark = !isCurrentlyDark
                    prefs.edit().putBoolean("dark_mode", isCurrentlyDark).apply()

                    updateSwitchUI(isCurrentlyDark, true)

                    window.decorView.postDelayed({
                        if (isCurrentlyDark) {
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        } else {
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        }
                    }, 400)
                    return@setNavigationItemSelectedListener true
                }
            }
            drawerLayout.close()
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}