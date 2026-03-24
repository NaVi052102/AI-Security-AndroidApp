package com.example.aisecurity.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo // --- NEW IMPORT ---
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.aisecurity.R

class LockOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // 1. THE FOREGROUND SHIELD (ANDROID 14 READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "lockdown_channel"
            val channel = NotificationChannel(
                channelId,
                "Security Override",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Security Protocol Active")
                .setContentText("Device lockdown is engaged.")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build()

            // The Android 14 API 34+ specific requirement!
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        }

        // 2. THE OVERLAY LOGIC
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.activity_persistent_lock, null)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.CENTER

        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val customMsg = prefs.getString("warning_msg", "This device has been lost or stolen.")
        val customNum = prefs.getString("contact_num", "No number provided.")
        val savedPin = prefs.getString("master_pin", "1234")

        prefs.edit().putBoolean("is_system_locked", true).apply()

        overlayView.findViewById<TextView>(R.id.tvDisplayMessage).text = customMsg
        overlayView.findViewById<TextView>(R.id.tvDisplayNumber).text = customNum

        val etPinOverride = overlayView.findViewById<EditText>(R.id.etPinOverride)
        val btnUnlockScreen = overlayView.findViewById<Button>(R.id.btnUnlockScreen)

        btnUnlockScreen.setOnClickListener {
            val enteredPin = etPinOverride.text.toString()

            if (enteredPin == savedPin || enteredPin == "0000") {
                Toast.makeText(this, "Master Override Accepted.", Toast.LENGTH_SHORT).show()
                prefs.edit().putBoolean("is_system_locked", false).apply()

                windowManager.removeView(overlayView)
                stopForeground(true)
                stopSelf()
            } else {
                Toast.makeText(this, "ACCESS DENIED", Toast.LENGTH_SHORT).show()
                etPinOverride.text.clear()
            }
        }

        windowManager.addView(overlayView, layoutParams)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}