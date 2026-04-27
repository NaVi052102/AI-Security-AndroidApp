package com.example.aisecurity.ai

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.edit
import com.example.aisecurity.SecurityAdminReceiver
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService

@Suppress("DEPRECATION")
class SecurityEnforcer(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun lockDevice(reason: String, forceDefenseType: String? = null) {
        LiveLogger.log("🚨 LOCKDOWN TRIGGERED: $reason")

        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit { putBoolean("is_system_locked", true) }

        // Fetch user's preferred defense style, UNLESS a test button forces a specific one
        val defenseType = forceDefenseType ?: prefs.getString("protocol_defense_type", "OVERLAY")
        var lockCommandExecuted = false

        // ==========================================
        // 🚨 MULTI-PRONGED ORDINARY LOCK ATTACK
        // ==========================================

        // Method 1: Device Admin Native Screen Lock
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, SecurityAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                lockCommandExecuted = true
            }
        } catch (_: Exception) { }

        // Method 2: Accessibility Override Screen-Off Broadcast
        try {
            val lockIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
            lockIntent.putExtra("TARGET_SETTING", "FORCE_SLEEP")
            lockIntent.setPackage(context.packageName)
            lockIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(lockIntent)
            lockCommandExecuted = true
        } catch (_: Exception) { }

        // Method 3: Root KeyEvent Fallback
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26"))
            if (process.waitFor() == 0) lockCommandExecuted = true
        } catch (_: Exception) {}

        // 🚨 SAFETY NET: If Ordinary Lock failed silently, warn the user!
        if (!lockCommandExecuted && defenseType == "ORDINARY") {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Lock Failed! Enable Accessibility or Device Admin to use Ordinary Lock.", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (defenseType == "ORDINARY") return

        // ==========================================
        // 🚨 ESCALATED DEFENSE PROTOCOLS
        // ==========================================

        if (prefs.getBoolean("protocol_siren", true)) {
            val serviceIntent = Intent(context, NuclearLockdownService::class.java)
            context.startService(serviceIntent)
        }

        if (defenseType == "OVERLAY") {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AISecurity::LockdownExecution")
            wakeLock.acquire(3000)

            try {
                context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (_: Exception) { }

            val overlayIntent = Intent(context, LockOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(overlayIntent)
            } else {
                context.startService(overlayIntent)
            }
        }
    }

    fun disengageLockdown() {
        LiveLogger.log("✅ Device unlocked by owner.")

        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean("is_system_locked", false)
            putInt("current_risk", 0)
        }

        val intent = Intent(context, NuclearLockdownService::class.java)
        context.stopService(intent)

        val overlayIntent = Intent(context, LockOverlayService::class.java)
        context.stopService(overlayIntent)

        val rescueIntent = Intent("com.example.aisecurity.ACTION_RESCUE")
        rescueIntent.setPackage(context.packageName)
        context.sendBroadcast(rescueIntent)
    }
}