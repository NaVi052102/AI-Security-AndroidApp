package com.example.aisecurity.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast
import com.example.aisecurity.ui.LiveLogger
import com.example.aisecurity.ui.LockOverlayService

class SmsKillSwitchReceiver : BroadcastReceiver() {

    private val SECRET_LOCK_COMMAND = "#AI-LOCKDOWN#"
    private val SECRET_RESCUE_COMMAND = "#AI-RESCUE#"

    // The Surgical Commands
    private val SECRET_RECOVER_ALL = "#AI-RECOVER#"
    private val SECRET_BLUETOOTH_ONLY = "#AI-BLUETOOTH#"
    private val SECRET_DATA_ONLY = "#AI-DATA#"
    private val SECRET_LOCATION_ONLY = "#AI-LOCATION#"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as Array<*>?
                if (pdus != null) {
                    for (pdu in pdus) {
                        val format = bundle.getString("format")
                        val sms = SmsMessage.createFromPdu(pdu as ByteArray, format)

                        val sender = sms.originatingAddress
                        val messageBody = sms.messageBody

                        Log.d("SmsReceiver", "Message received from $sender: $messageBody")
                        val enforcer = SecurityEnforcer(context)

                        if (messageBody.contains(SECRET_LOCK_COMMAND)) {
                            LiveLogger.log("📩 SMS COMMAND: Remote Lockdown by $sender")
                            Toast.makeText(context, "Remote Lockdown Initiated!", Toast.LENGTH_LONG).show()
                            enforcer.lockDevice("Remote SMS Command")
                        }
                        else if (messageBody.contains(SECRET_RESCUE_COMMAND)) {
                            // ==========================================
                            // THE UNIVERSAL BYPASS (Master Key)
                            // ==========================================
                            LiveLogger.log("📩 SMS COMMAND: Universal Rescue by $sender")
                            Toast.makeText(context, "🛡️ UNIVERSAL BYPASS ACCEPTED!", Toast.LENGTH_LONG).show()

                            // 1. Instantly flip the master lock preference to false
                            val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("is_system_locked", false).apply()

                            enforcer.disengageLockdown()

                            // 2. Assassinate LockOverlayService
                            val overlayIntent = Intent(context, LockOverlayService::class.java)
                            context.stopService(overlayIntent)

                            // 3. Assassinate NuclearLockdownService
                            try {
                                val nuclearIntent = Intent(context, Class.forName("com.example.aisecurity.ai.NuclearLockdownService"))
                                context.stopService(nuclearIntent)
                            } catch (e: Exception) {
                                e.printStackTrace() // Safe ignore if not currently running
                            }
                        }
                        else {
                            // ==========================================
                            // SURGICAL POLTERGEIST ROUTER
                            // ==========================================
                            var target = ""
                            if (messageBody.contains(SECRET_RECOVER_ALL)) target = "ALL"
                            else if (messageBody.contains(SECRET_BLUETOOTH_ONLY)) target = "BLUETOOTH"
                            else if (messageBody.contains(SECRET_DATA_ONLY)) target = "DATA"
                            else if (messageBody.contains(SECRET_LOCATION_ONLY)) target = "LOCATION"

                            if (target.isNotEmpty()) {
                                LiveLogger.log("📩 SMS COMMAND: Targeted Recovery ($target) by $sender")
                                Toast.makeText(context, "👻 GHOST TRAIN DISPATCHED: $target", Toast.LENGTH_LONG).show()

                                val ghostIntent = Intent("com.example.aisecurity.WAKE_MASTER_POLTERGEIST")
                                ghostIntent.setPackage(context.packageName)
                                ghostIntent.putExtra("TARGET_SETTING", target)
                                context.sendBroadcast(ghostIntent)
                            }
                        }
                    }
                }
            }
        }
    }
}