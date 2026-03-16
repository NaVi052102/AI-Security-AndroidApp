package com.example.aisecurity.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.ui.FakeShutdownActivity // --- NEW IMPORT ---
import com.example.aisecurity.ui.biometrics.BiometricsFragment
import com.example.aisecurity.ui.bluetooth.BluetoothFragment
import com.example.aisecurity.ui.dashboard.DashboardFragment
import com.example.aisecurity.ui.logs.LogsFragment
import com.example.aisecurity.ui.map.MapFragment
import com.example.aisecurity.ui.permissions.PermissionsFragment
import com.example.aisecurity.ui.proximity.ProximityFragment
import com.example.aisecurity.ui.settings.SettingsFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val sideNav = findViewById<NavigationView>(R.id.nav_view_sidebar)

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
                R.id.nav_logs -> {
                    loadFragment(LogsFragment())
                    topAppBar.title = "Security Logs"
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
                    topAppBar.title = "Bluetooth Connection"
                }
                R.id.side_permissions -> {
                    loadFragment(PermissionsFragment())
                    topAppBar.title = "App Permissions"
                }
                R.id.side_notifications -> Toast.makeText(this, "Notifications", Toast.LENGTH_SHORT).show()

                // ==========================================
                // TEST TRIGGER: FAKE SHUTDOWN OVERRIDE
                // ==========================================
                R.id.side_help -> {
                    Toast.makeText(this, "Initiating Fake Shutdown...", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@MainActivity, FakeShutdownActivity::class.java)
                    startActivity(intent)
                }
            }
            drawerLayout.close() // Auto-close the sidebar after clicking an item
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}