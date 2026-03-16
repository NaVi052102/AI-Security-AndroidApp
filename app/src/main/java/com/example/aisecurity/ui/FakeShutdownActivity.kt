package com.example.aisecurity.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class FakeShutdownActivity : AppCompatActivity() {

    private var tapCount = 0
    private var lastTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ==========================================
        // 1. HARDWARE LOCKDOWN FLAGS
        // ==========================================
        // Keep screen on, and show this screen even if the phone is locked
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Force physical screen brightness to absolute zero (kills the backlight)
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 0.0f
        window.attributes = layoutParams

        // ==========================================
        // 2. CREATE THE PITCH BLACK FULL SCREEN
        // ==========================================
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)

            // THE ESCAPE HATCH (Tap anywhere 5 times fast)
            setOnClickListener {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastTapTime < 500) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapTime = currentTime

                if (tapCount >= 5) {
                    // Restore normal brightness before exiting
                    val normalParams = window.attributes
                    normalParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = normalParams

                    Toast.makeText(this@FakeShutdownActivity, "Master Override Accepted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        setContentView(rootLayout)
        hideSystemUI()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ==========================================
    // 3. INTERCEPT HARDWARE BUTTONS
    // ==========================================
    // This completely disables the Volume Up and Volume Down buttons
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            // We "consume" the button press and do absolutely nothing
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Completely disable the physical Back Button
    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing!
    }
}