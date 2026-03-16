package com.example.aisecurity.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Double-check we are actually receiving an SMS
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            for (sms in messages) {
                val messageBody = sms.messageBody ?: ""

                // 1. Scan for the secret security trigger
                if (messageBody.contains("AI_SECURE_LOC:")) {
                    Log.d("AI_SECURITY", "Secret SMS Location Received in Background!")

                    try {
                        // 2. Extract the coordinates.
                        // It cuts out "AI_SECURE_LOC:" and grabs everything before the next line break
                        val locPart = messageBody.substringAfter("AI_SECURE_LOC:").substringBefore("\n")
                        val coordinates = locPart.split(",")

                        if (coordinates.size == 2) {
                            val lat = coordinates[0].toDouble()
                            val lng = coordinates[1].toDouble()

                            // 3. Save the coordinates secretly to SharedPreferences
                            val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                            prefs.edit()
                                .putFloat("target_lat", lat.toFloat())
                                .putFloat("target_lng", lng.toFloat())
                                .apply()

                            Log.d("AI_SECURITY", "Target locked at: $lat, $lng")
                        }
                    } catch (e: Exception) {
                        Log.e("AI_SECURITY", "Failed to decode secret SMS: ${e.message}")
                    }
                }
            }
        }
    }
}