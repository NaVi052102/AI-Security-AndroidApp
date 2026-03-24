package com.example.aisecurity.ai

import android.content.Context
import android.content.Intent
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.HoneypotActivity

class SecurityEnforcer(private val context: Context) {

    fun lockDevice(reason: String) {
        LiveLogger.log("🚨 ULTIMATE LOCKDOWN TRIGGERED: $reason")

        // 1. Launch the Siren & Volume Interceptor
        val serviceIntent = Intent(context, NuclearLockdownService::class.java)
        context.startService(serviceIntent)

        // 2. Launch the Pinned Warning Screen
        val activityIntent = Intent(context, HoneypotActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(activityIntent)
    }

    fun disengageLockdown() {
        LiveLogger.log("✅ Device unlocked by owner.")

        // 1. Stop the Siren Service
        val intent = Intent(context, NuclearLockdownService::class.java)
        context.stopService(intent)

        // 2. Broadcast the rescue signal to kill the Pinned Screen
        val rescueIntent = Intent("com.example.aisecurity.ACTION_RESCUE")
        rescueIntent.setPackage(context.packageName) // Security measure: only our app can hear this
        context.sendBroadcast(rescueIntent)

        // 3. Reset the AI's risk score
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("current_risk", 0).apply()
    }
}