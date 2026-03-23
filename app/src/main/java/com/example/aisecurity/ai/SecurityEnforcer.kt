package com.example.aisecurity.ai

import android.content.Context
import android.content.Intent
import com.example.aisecurity.ui.LiveLogger

class SecurityEnforcer(private val context: Context) {

    // The two tiers of security you designed
    enum class SecurityLevel {
        GHOST_MODE,   // Tier 1: The Fake App Honeypot
        NUCLEAR       // Tier 2: The Siren & Screen Lock Loop
    }

    // Called by TouchDynamicsService when the AI detects an anomaly
    fun lockDevice(reason: String) {
        LiveLogger.log("🚨 THREAT DETECTED: $reason")

        // Read the user's preferred security level (Defaults to GHOST_MODE)
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val levelString = prefs.getString("security_level", SecurityLevel.GHOST_MODE.name)
        val level = SecurityLevel.valueOf(levelString ?: SecurityLevel.GHOST_MODE.name)

        when (level) {
            SecurityLevel.GHOST_MODE -> {
                LiveLogger.log("👻 Deploying Honeypot (Ghost Mode)...")


                val intent = Intent(context, HoneypotActivity::class.java).apply {
                    // We must use these flags because we are launching from a background service
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)

            }
            SecurityLevel.NUCLEAR -> {
                LiveLogger.log("☢️ Deploying Nuclear Lockdown (Siren & Lock Loop)...")
                val intent = Intent(context, NuclearLockdownService::class.java)
                context.startService(intent)
            }
        }
    }

    // Called via SMS Kill-Switch to rescue the phone
    fun disengageLockdown() {
        LiveLogger.log("✅ Device unlocked by owner.")

        // Stop the Nuclear Siren & Loop if it is currently running
        val intent = Intent(context, NuclearLockdownService::class.java)
        context.stopService(intent)

        // Reset the AI's risk score to 0 so it doesn't instantly lock you out again!
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("current_risk", 0).apply()
    }

    // A helper function for your UI.
    // Call this when the user clicks a button to change their security settings.
    fun setSecurityLevel(level: SecurityLevel) {
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("security_level", level.name).apply()
        LiveLogger.log("⚙️ Security Level updated to: ${level.name}")
    }
}