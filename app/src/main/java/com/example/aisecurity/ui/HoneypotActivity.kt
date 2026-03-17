package com.example.aisecurity.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R
import kotlin.math.abs

class HoneypotActivity : AppCompatActivity() {

    // --- Rhythmic Knock Variables ---
    private val inputIntervals = mutableListOf<Long>()
    private var lastTapTime: Long = 0L
    private val RESET_TIMEOUT = 2500L
    private var fallbackTapCount = 0

    // --- The Phantom Shields ---
    private var windowManager: WindowManager? = null
    private var topShield: View? = null
    private var bottomShield: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. SHOW OVER LOCK SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_honeypot)

        // 2. Hide the Real System UI (Fullscreen)
        hideSystemUI()

        // 3. Deploy the Invisible Phantom Shields!
        deployPhantomShields()

        // 4. Disable Software Back Button
        onBackPressedDispatcher.addCallback(this) {
            // Silently eat the back button press
        }

        // 5. Setup the Secret Knock on the fake background
        val rootLayout = findViewById<View>(R.id.honeypotRoot)
        rootLayout.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val currentTime = System.currentTimeMillis()
                verifyKnock(currentTime)
                v.performClick()
            }
            true
        }
    }

    private fun deployPhantomShields() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create the Top Shield (Blocks Status Bar pull-down)
        val topParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            200, // 200 pixels tall (covers the swipe-down zone)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT // Completely invisible!
        )
        topParams.gravity = Gravity.TOP

        topShield = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        // Create the Bottom Shield (Blocks Navigation Swipe-up for Android Gestures)
        val bottomParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            200,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT
        )
        bottomParams.gravity = Gravity.BOTTOM

        bottomShield = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        // Deploy them to the screen
        try {
            windowManager?.addView(topShield, topParams)
            windowManager?.addView(bottomShield, bottomParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
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
            if (fallbackTapCount >= 5) unlockAndExit()
            lastTapTime = currentTime
            return
        }

        if (lastTapTime != 0L) inputIntervals.add(currentTime - lastTapTime)
        lastTapTime = currentTime

        if (inputIntervals.size >= savedPattern.size) {
            val recentInputs = inputIntervals.takeLast(savedPattern.size)
            if (isPatternMatch(recentInputs, savedPattern)) {
                unlockAndExit()
            }
        }
    }

    private fun unlockAndExit() {
        Toast.makeText(this, "Master Override Accepted", Toast.LENGTH_SHORT).show()
        finish() // Closes the Honeypot Activity
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
        // CRITICAL: We must remove the invisible shields when we exit,
        // otherwise the user won't be able to swipe down their real status bar!
        try {
            if (topShield != null) windowManager?.removeView(topShield)
            if (bottomShield != null) windowManager?.removeView(bottomShield)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}