package com.example.aisecurity.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast
import com.example.aisecurity.ui.LiveLogger

class SmsKillSwitchReceiver : BroadcastReceiver() {

    // These are your secret master passwords.
    // You can change these to whatever you want!
    private val SECRET_LOCK_COMMAND = "#AI-LOCKDOWN#"
    private val SECRET_RESCUE_COMMAND = "#AI-RESCUE#"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            if (bundle != null) {
                // SMS messages come in a raw format called "pdus"
                val pdus = bundle.get("pdus") as Array<*>?
                if (pdus != null) {
                    for (pdu in pdus) {
                        val format = bundle.getString("format")
                        val sms = SmsMessage.createFromPdu(pdu as ByteArray, format)

                        val sender = sms.originatingAddress
                        val messageBody = sms.messageBody

                        Log.d("SmsReceiver", "Message received from $sender: $messageBody")

                        // Check if the message contains our secret keywords
                        val enforcer = SecurityEnforcer(context)

                        if (messageBody.contains(SECRET_LOCK_COMMAND)) {
                            LiveLogger.log("📩 SMS COMMAND RECEIVED: Remote Lockdown Triggered by $sender")
                            Toast.makeText(context, "Remote Lockdown Initiated!", Toast.LENGTH_LONG).show()
                            enforcer.lockDevice("Remote SMS Command")
                        }
                        else if (messageBody.contains(SECRET_RESCUE_COMMAND)) {
                            LiveLogger.log("📩 SMS COMMAND RECEIVED: System Rescued by $sender")
                            Toast.makeText(context, "System Rescued!", Toast.LENGTH_LONG).show()
                            enforcer.disengageLockdown()
                        }
                    }
                }
            }
        }
    }
}