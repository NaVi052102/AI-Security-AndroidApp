package com.example.aisecurity.ai

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class SecurityEnforcer(private val context: Context) {

    fun lockDevice(triggerReason: String) {
        Log.d("AI_SECURITY", "Threat Detected! Reason: $triggerReason")

        // ==========================================
        // STEP 1: LAUNCH THE GHOST SCREEN (Selfie + Lock)
        // ==========================================
        // This launches the invisible LockdownActivity which will silently snap the
        // picture and then immediately lock the phone.
        val lockdownIntent = Intent(context, com.example.aisecurity.ui.LockdownActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(lockdownIntent)

        // ==========================================
        // STEP 2: FIRE THE STEALTH SMS TRACKER
        // ==========================================
        val tracker = LocationTracker(context)
        tracker.grabLocationAndSendStealthSMS()
    }

    // ==========================================
    // THE ADMIN RECEIVER
    // ==========================================
    // This tells the Android OS that your app has permission to turn off the screen
    class AdminReceiver : DeviceAdminReceiver() {
        override fun onEnabled(context: Context, intent: Intent) {
            super.onEnabled(context, intent)
            Log.d("AI_SECURITY", "Device Admin Enabled")
        }

        override fun onDisabled(context: Context, intent: Intent) {
            super.onDisabled(context, intent)
            Log.d("AI_SECURITY", "Device Admin Disabled")
        }
    }
}