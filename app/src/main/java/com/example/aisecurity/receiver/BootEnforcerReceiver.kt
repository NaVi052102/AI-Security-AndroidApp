package com.example.aisecurity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.aisecurity.ui.LockOverlayService

class BootEnforcerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        // We now catch ANY of the shotgun intents
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_system_locked", false)

        if (isLocked && Settings.canDrawOverlays(context)) {

            val lockIntent = Intent(context, LockOverlayService::class.java)

            // THE ANTI-CRASH DELAY
            // We wait 2 seconds to let the Android WindowManager fully wake up
            // before we aggressively inject our red screen.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(lockIntent)
                    } else {
                        context.startService(lockIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // Prevents the app from crashing if the OS fights back
                }
            }, 2000)
        }
    }
}