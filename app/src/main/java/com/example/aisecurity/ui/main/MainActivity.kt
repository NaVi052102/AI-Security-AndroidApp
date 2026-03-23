package com.example.aisecurity.ui.main

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import com.example.aisecurity.SecurityAdminReceiver // --- NEW IMPORT ---
import com.example.aisecurity.ui.FakeShutdownActivity
import com.example.aisecurity.ui.biometrics.BiometricsFragment
import com.example.aisecurity.ui.bluetooth.BluetoothFragment
import com.example.aisecurity.ui.dashboard.DashboardFragment
import com.example.aisecurity.ui.logs.LogsFragment
import com.example.aisecurity.ui.map.MapFragment
import com.example.aisecurity.ui.permissions.PermissionsFragment
import com.example.aisecurity.ui.proximity.ProximityFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.example.aisecurity.ui.HoneypotActivity
import com.example.aisecurity.ui.SettingsFragment

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
                // TEST TRIGGER: REAL STEALTH LOCKDOWN
                // ==========================================
                R.id.side_help -> {
                    // 1. Check if we have permission to draw the black screen
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, "Please grant Overlay Permission first", Toast.LENGTH_LONG).show()
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        startActivity(intent)
                    } else {
                        // 2. Check if we have Device Admin powers
                        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val adminComponent = ComponentName(this@MainActivity, SecurityAdminReceiver::class.java)

                        if (!devicePolicyManager.isAdminActive(adminComponent)) {
                            Toast.makeText(this@MainActivity, "Please Activate Device Admin", Toast.LENGTH_LONG).show()
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Command Center requires Admin access to lock the device.")
                            startActivity(intent)
                        } else {
                            // 3. IF WE HAVE BOTH PERMISSIONS, TRIGGER THE LOCKDOWN!
                            // TRIGGER THE HONEYPOT INSTEAD OF THE BLACK SCREEN
                            Toast.makeText(this@MainActivity, "Initiating Honeypot Trap...", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@MainActivity, HoneypotActivity::class.java))
                        }
                    }
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