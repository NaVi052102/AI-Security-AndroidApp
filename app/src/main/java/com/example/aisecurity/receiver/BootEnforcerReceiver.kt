package com.example.aisecurity.receiver

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.aisecurity.SecurityAdminReceiver
import com.example.aisecurity.ui.LockOverlayService

class BootEnforcerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_system_locked", false)

        if (isLocked) {
            // ==========================================
            // THE BLACKOUT STRIKE
            // Instantly kill the screen to buy time for the overlay to load
            // ==========================================
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SecurityAdminReceiver::class.java)
                if (dpm.isAdminActive(adminComponent)) {
                    dpm.lockNow() // Forces the screen to go black instantly
                }
            } catch (_: Exception) { }

            if (Settings.canDrawOverlays(context)) {
                val lockIntent = Intent(context, LockOverlayService::class.java)

                // The 500ms fast-strike delay
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Unnecessary SDK version check removed
                        context.startForegroundService(lockIntent)
                    } catch (_: Exception) { }
                }, 500)
            }
        }
    }
}