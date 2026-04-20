package com.example.aisecurity.ai

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.edit
import com.example.aisecurity.SecurityAdminReceiver
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService

@Suppress("DEPRECATION")
class SecurityEnforcer(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun lockDevice(reason: String) {
        LiveLogger.log("🚨 ULTIMATE LOCKDOWN TRIGGERED: $reason")

        // 0. THE DEATH MARK (Boot Persistence) using modern KTX extension
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("is_system_locked", true) }

        // 1. THE WAKELOCK ANCHOR (CPU Seizure)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AISecurity::LockdownExecution")
        wakeLock.acquire(3000)

        // 2. THE DIALOG GUILLOTINE
        try {
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) { } // Underscore used for ignored exceptions

        // 3. THE INSTANT BLINDER
        val overlayIntent = Intent(context, LockOverlayService::class.java)
        context.startForegroundService(overlayIntent) // Removed unnecessary SDK_INT check

        // 4. LAUNCH SIREN & VOLUME HIJACK
        val serviceIntent = Intent(context, NuclearLockdownService::class.java)
        context.startService(serviceIntent)

        // 5. PRIMARY SCREEN KILLER (Device Admin)
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, SecurityAdminReceiver::class.java)

            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
            }
        } catch (_: Exception) {
            LiveLogger.log("⚠️ Hardware Lock Bypassed by OEM.")
        }

        // 6. ACCESSIBILITY OVERRIDE
        try {
            val lockIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
            lockIntent.putExtra("TARGET_SETTING", "FORCE_SLEEP")
            lockIntent.setPackage(context.packageName)
            lockIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(lockIntent)
        } catch (_: Exception) { }
    }

    fun disengageLockdown() {
        LiveLogger.log("✅ Device unlocked by owner.")

        // Erase the Death Mark
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean("is_system_locked", false)
            putInt("current_risk", 0)
        }

        // Stop the Siren Service
        val intent = Intent(context, NuclearLockdownService::class.java)
        context.stopService(intent)

        // Kill the Red Overlay
        val overlayIntent = Intent(context, LockOverlayService::class.java)
        context.stopService(overlayIntent)

        // Kill the Phantom Screen (if active)
        val rescueIntent = Intent("com.example.aisecurity.ACTION_RESCUE")
        rescueIntent.setPackage(context.packageName)
        context.sendBroadcast(rescueIntent)
    }
}