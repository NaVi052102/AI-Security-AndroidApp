package com.example.aisecurity.receiver

import android.app.admin.DevicePolicyManager // --- NEW IMPORT ---
import android.content.BroadcastReceiver
import android.content.ComponentName // --- NEW IMPORT ---
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.aisecurity.SecurityAdminReceiver // --- NEW IMPORT ---
import com.example.aisecurity.ui.LockOverlayService

class BootEnforcerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_system_locked", false)

        if (isLocked) {
            // ==========================================
            // TACTIC 1: THE BLACKOUT STRIKE
            // Instantly kill the screen to buy time for the overlay
            // ==========================================
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SecurityAdminReceiver::class.java)
                if (dpm.isAdminActive(adminComponent)) {
                    dpm.lockNow() // Instantly puts the phone to sleep
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (Settings.canDrawOverlays(context)) {
                val lockIntent = Intent(context, LockOverlayService::class.java)

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(lockIntent)
                        } else {
                            context.startService(lockIntent)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }, 500)
            }
        }
    }
}