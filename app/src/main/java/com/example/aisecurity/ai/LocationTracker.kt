package com.example.aisecurity.ai

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import android.util.Log

class LocationTracker(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun grabLocationAndSendStealthSMS() {
        // 1. OPEN THE VAULT AND GRAB THE SAVED NUMBER
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val trustedNumber = prefs.getString("trusted_number", "")

        // Safety Check: Did the user actually save a number in the Settings page?
        if (trustedNumber.isNullOrEmpty()) {
            Log.e("AI_SECURITY", "Stealth SMS Aborted: No Emergency Contact set in Settings!")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 2. GRAB THE EXACT GPS COORDINATES
        val location: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (location != null) {
            val lat = location.latitude
            val lng = location.longitude

            // 3. CREATE THE SECRET MESSAGE
            // Contains both the hidden tag for your app's Tracker Map, AND a clickable Google Maps link
            val secretMessage = "AI_SECURE_LOC:$lat,$lng\nEmergency! Unauthorized access. http://maps.google.com/?q=$lat,$lng"

            // 4. FIRE THE SMS
            sendSMS(trustedNumber, secretMessage)
        } else {
            Log.e("AI_SECURITY", "Stealth SMS Failed: Could not get a GPS lock.")
        }
    }

    private fun sendSMS(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.d("AI_SECURITY", "Stealth Location SMS successfully sent to $phoneNumber!")
        } catch (e: Exception) {
            Log.e("AI_SECURITY", "SMS Failed to send: ${e.message}")
        }
    }
}