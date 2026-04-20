package com.example.aisecurity.ui

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.aisecurity.R

class FakeShutdownActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 🚨 PUNCH THROUGH THE LOCK SCREEN
        // These flags must be set BEFORE super.onCreate() to seize the screen instantly
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
        setContentView(R.layout.activity_fake_shutdown)

        // 🚨 MODERN DEEP IMMERSIVE MODE (Android 11+)
        // Fixes the deprecation warnings and hides status/nav bars flawlessly
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // 🚨 MODERN BACK BUTTON TRAP (Android 13+)
        // Replaces the deprecated onBackPressed() function
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing. The thief is trapped.
            }
        })
    }

    // 🚨 ABSORB HARDWARE BUTTONS (Volume Up/Down)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return true
    }

    // 🚨 ABSORB ALL SCREEN TOUCHES
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return true
    }
}