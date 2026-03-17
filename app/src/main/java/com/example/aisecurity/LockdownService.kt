package com.example.aisecurity

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs

class LockdownService : Service() {

    private var windowManager: WindowManager? = null
    private var blackOverlay: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // --- Rhythmic Knock Variables ---
    private val inputIntervals = mutableListOf<Long>()
    private var lastTapTime: Long = 0L
    private val RESET_TIMEOUT = 2500L
    private var fallbackTapCount = 0

    // ========================================================
    // NEW: HARDWARE SCREEN WAKE INTERCEPTOR
    // ========================================================
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                // If the thief presses the power button to wake the screen,
                // we instantly force our black overlay back to the very top!
                reassertBlackScreen()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Register the receiver to listen for Power Button wakes
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showBlackScreen()
        return START_STICKY
    }

    private fun showBlackScreen() {
        if (blackOverlay != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // FIXED: Removed FLAG_NOT_FOCUSABLE so the window traps system focus.
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        layoutParams?.gravity = Gravity.CENTER
        layoutParams?.screenBrightness = 0.01f

        blackOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true // Forces the view to trap focus
            requestFocus()

            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val currentTime = System.currentTimeMillis()
                    verifyKnock(currentTime)
                    v.performClick()
                }
                true
            }
        }

        windowManager?.addView(blackOverlay, layoutParams)
    }

    // --- Forces the Black Screen to the top Z-Order over the lock screen ---
    private fun reassertBlackScreen() {
        try {
            if (blackOverlay != null && windowManager != null) {
                // By briefly updating the view, Android pushes it to the absolute front layer
                windowManager?.updateViewLayout(blackOverlay, layoutParams)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun verifyKnock(currentTime: Long) {
        val savedPattern = getSavedPattern()

        if (lastTapTime != 0L && (currentTime - lastTapTime > RESET_TIMEOUT)) {
            inputIntervals.clear()
            fallbackTapCount = 0
            lastTapTime = 0L
        }

        if (savedPattern.isEmpty()) {
            fallbackTapCount++
            if (fallbackTapCount >= 5) {
                unlockAndLockHardware()
            }
            lastTapTime = currentTime
            return
        }

        if (lastTapTime != 0L) {
            inputIntervals.add(currentTime - lastTapTime)
        }
        lastTapTime = currentTime

        if (inputIntervals.size >= savedPattern.size) {
            val recentInputs = inputIntervals.takeLast(savedPattern.size)

            if (isPatternMatch(recentInputs, savedPattern)) {
                unlockAndLockHardware()
            }
        }
    }

    private fun unlockAndLockHardware() {
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, SecurityAdminReceiver::class.java)

        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.lockNow()
        }

        stopSelf()
    }

    private fun getSavedPattern(): List<Long> {
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val patternStr = prefs.getString("secret_knock_pattern", "") ?: ""
        if (patternStr.isEmpty()) return emptyList()
        return patternStr.split(",").mapNotNull { it.toLongOrNull() }
    }

    private fun isPatternMatch(input: List<Long>, saved: List<Long>): Boolean {
        val tolerance = 500L
        for (i in saved.indices) {
            if (abs(input[i] - saved[i]) > tolerance) return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always unregister your receivers to prevent memory leaks!
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (blackOverlay != null) {
            windowManager?.removeView(blackOverlay)
            blackOverlay = null
            Toast.makeText(this, "Biometric Knock Accepted: OS Lock Engaged", Toast.LENGTH_LONG).show()
        }
    }
}