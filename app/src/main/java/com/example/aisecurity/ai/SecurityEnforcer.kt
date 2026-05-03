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



    /**

     * Returns true if the device is a MIUI-based device (Xiaomi / Redmi / POCO).

     * On these devices, DevicePolicyManager.lockNow() sets an "admin lock" flag

     * that gates Camera HAL access until the user re-authenticates biometrically.

     * Power-button-equivalent locks (GLOBAL_ACTION_LOCK_SCREEN / keyevent 26)

     * do NOT set this flag and leave the camera accessible.

     */

    private val isMiuiDevice: Boolean by lazy {

        val manufacturer = Build.MANUFACTURER.lowercase()

        val brand = Build.BRAND.lowercase()

        manufacturer.contains("xiaomi") ||

                manufacturer.contains("redmi") ||

                manufacturer.contains("poco") ||

                brand.contains("xiaomi") ||

                brand.contains("redmi") ||

                brand.contains("poco") ||

                !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    }



    private fun getSystemProperty(key: String): String? {

        return try {

            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))

            process.inputStream.bufferedReader().readLine()?.trim()?.takeIf { it.isNotEmpty() }

        } catch (_: Exception) { null }

    }



    /**

     * @param triggerLockFlag  When TRUE (default), sets is_system_locked=true and activates

     *                         the full intruder-lockdown pipeline (Aegis Shield, home

     *                         redirection, siren, overlay). When FALSE, only locks the screen

     *                         — equivalent to pressing the power button. Pass FALSE for remote

     *                         screen-off commands so TouchDynamicsService does NOT block PIN

     *                         entry or intercept HiddenCameraActivity.

     */

    @SuppressLint("MissingPermission")

    fun lockDevice(

        reason: String,

        forceDefenseType: String? = null,

        triggerLockFlag: Boolean = true

    ) {

        LiveLogger.log("🚨 LOCKDOWN TRIGGERED: $reason | LockFlag=$triggerLockFlag | MIUI=$isMiuiDevice")



        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)



        if (triggerLockFlag) {

            prefs.edit { putBoolean("is_system_locked", true) }

        }



        val defenseType = forceDefenseType ?: prefs.getString("protocol_defense_type", "OVERLAY")

        var lockCommandExecuted = false



        // ======================================================================

        // MULTI-PRONGED ORDINARY LOCK

        //

        // MIUI NOTE: DevicePolicyManager.lockNow() is intentionally SKIPPED on

        // MIUI devices when triggerLockFlag=false (i.e. a plain remote screen-off).

        // DPM.lockNow() sets an "admin lock" flag in MIUI that suspends Camera HAL

        // access until the user biometrically re-authenticates, silently blocking

        // HiddenCameraActivity even with wake locks and FLAG_SHOW_WHEN_LOCKED.

        // GLOBAL_ACTION_LOCK_SCREEN and keyevent 26 both simulate a physical power

        // button press and do NOT set this flag — camera remains accessible.

        // ======================================================================



        // Method 1: Device Admin Native Screen Lock

        // Skip on MIUI for plain remote screen-off (triggerLockFlag=false)

        // to avoid the admin-lock camera gate.

        val shouldUseDpm = !isMiuiDevice || triggerLockFlag

        if (shouldUseDpm) {

            try {

                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

                val adminComponent = ComponentName(context, SecurityAdminReceiver::class.java)

                if (dpm.isAdminActive(adminComponent)) {

                    dpm.lockNow()

                    lockCommandExecuted = true

                    LiveLogger.log("🔒 Lock Method 1: DPM.lockNow() executed.")

                }

            } catch (_: Exception) { }

        } else {

            LiveLogger.log("⏭️ Lock Method 1: DPM.lockNow() SKIPPED on MIUI (would gate Camera HAL).")

        }



        // Method 2: Accessibility Service Screen-Off Broadcast

        // Equivalent to pressing the power button. Safe on all devices including MIUI.

        if (!lockCommandExecuted || isMiuiDevice) {

            try {

                val lockIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")

                lockIntent.putExtra("TARGET_SETTING", "FORCE_SLEEP")

                lockIntent.setPackage(context.packageName)

                lockIntent.addFlags(

                    Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES

                )

                context.sendBroadcast(lockIntent)

                lockCommandExecuted = true

                LiveLogger.log("🔒 Lock Method 2: GLOBAL_ACTION_LOCK_SCREEN broadcast sent.")

            } catch (_: Exception) { }

        }



        // Method 3: Root KeyEvent 26 (power button simulation)

        // Also safe on MIUI — does not set admin-lock flag.

        if (!lockCommandExecuted) {

            try {

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26"))

                if (process.waitFor() == 0) {

                    lockCommandExecuted = true

                    LiveLogger.log("🔒 Lock Method 3: Root keyevent 26 executed.")

                }

            } catch (_: Exception) { }

        }



        // Safety net

        if (!lockCommandExecuted && defenseType == "ORDINARY") {

            Handler(Looper.getMainLooper()).post {

                Toast.makeText(

                    context,

                    "Lock Failed! Enable Accessibility or Device Admin to use Ordinary Lock.",

                    Toast.LENGTH_LONG

                ).show()

            }

            if (triggerLockFlag) {

                prefs.edit { putBoolean("is_system_locked", false) }

            }

            return

        }



        if (defenseType == "ORDINARY") return



        // ======================================================================

        // ESCALATED DEFENSE PROTOCOLS

        // Only reached for full AI intruder lockdowns (OVERLAY mode).

        // ======================================================================



        if (prefs.getBoolean("protocol_siren", true)) {

            val serviceIntent = Intent(context, NuclearLockdownService::class.java)

            context.startService(serviceIntent)

        }



        if (defenseType == "OVERLAY") {

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            val wakeLock = powerManager.newWakeLock(

                PowerManager.PARTIAL_WAKE_LOCK,

                "AISecurity::LockdownExecution"

            )

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