package com.example.aisecurity.ui

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

class FakeDeadStateService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var blackScreenView: View

    private val escapeTimestamps = mutableListOf<Long>()
    private var tapCount = 0
    private var lastTapTime = 0L

    // This receiver listens for the power button (Screen On/Off) and charger
    private val escapeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_POWER_CONNECTED) {
                val now = System.currentTimeMillis()
                escapeTimestamps.add(now)

                // Clear any timestamps older than 10 seconds
                escapeTimestamps.removeAll { now - it > 10000 }

                // ESCAPE HATCH: 3 power actions (Off -> On -> Off) or charger plugs within 10s
                if (escapeTimestamps.size >= 3) {
                    Toast.makeText(context, "System Restored. Fake Shutdown Deactivated.", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create a pitch-black layout
        blackScreenView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)

            // 🚨 HIDDEN TOUCH ESCAPE HATCH: Tap 5 times quickly
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime > 1000) {
                        tapCount = 0 // Reset if more than 1 second passes between taps
                    }
                    lastTapTime = now
                    tapCount++

                    if (tapCount >= 5) {
                        Toast.makeText(this@FakeDeadStateService, "Emergency Touch Bypass Activated.", Toast.LENGTH_LONG).show()
                        stopSelf()
                    }
                }
                true // Consume all touches so the thief can't click things behind the black screen
            }

            systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Configure the window to float above absolutely everything, including the lock screen
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE
        )

        windowManager.addView(blackScreenView, params)

        // Register the power button and cable listener
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(escapeReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the black screen when the service stops
        if (::windowManager.isInitialized && ::blackScreenView.isInitialized) {
            windowManager.removeView(blackScreenView)
        }
        try {
            unregisterReceiver(escapeReceiver)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}