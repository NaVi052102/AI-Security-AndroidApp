package com.example.aisecurity.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback // NEW: Modern gesture blocking
import androidx.appcompat.app.AppCompatActivity
import com.example.aisecurity.R

class PersistentLockActivity : AppCompatActivity() {

    // The Master Trap Flag
    private var isUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. FORCE OVER LOCKSCREEN & KEEP AWAKE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_persistent_lock)

        // 2. LOAD RECOVERY INFO
        val prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val customMsg = prefs.getString("warning_msg", "This device has been lost or stolen.")
        val customNum = prefs.getString("contact_num", "No number provided.")
        val savedPin = prefs.getString("master_pin", "1234") // Default PIN

        findViewById<TextView>(R.id.tvDisplayMessage).text = customMsg
        findViewById<TextView>(R.id.tvDisplayNumber).text = customNum

        // 3. HIDE SYSTEM BARS
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // ==========================================
        // ENTERPRISE TRAP LAYER 1 & 2
        // ==========================================

        // Disable Home and Recents buttons
        startLockTask()

        // Block modern edge-swipe back gestures
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@PersistentLockActivity, "ACTION BLOCKED: Device is locked.", Toast.LENGTH_SHORT).show()
            }
        })

        // 4. PIN VALIDATION LOGIC
        val etPinOverride = findViewById<EditText>(R.id.etPinOverride)
        val btnUnlockScreen = findViewById<Button>(R.id.btnUnlockScreen)

        btnUnlockScreen.setOnClickListener {
            val enteredPin = etPinOverride.text.toString()

            // SECURITY CHECK + DEVELOPER ESCAPE HATCH (0000)
            if (enteredPin == savedPin || enteredPin == "0000") {
                Toast.makeText(this, "Master Override Accepted.", Toast.LENGTH_SHORT).show()

                isUnlocked = true // Set flag to true so the Bounce-Back trap ignores us
                stopLockTask()    // Tell Android to release the buttons
                finish()          // Safely close the screen
            } else {
                Toast.makeText(this, "ACCESS DENIED: Incorrect PIN", Toast.LENGTH_LONG).show()
                etPinOverride.text.clear()
            }
        }
    }

    // ==========================================
    // ENTERPRISE TRAP LAYER 3: The Bounce-Back
    // ==========================================
    override fun onPause() {
        super.onPause()
        // If the activity is paused (e.g., they found a loophole to go to the home screen)
        // AND we haven't officially unlocked it... drag them right back.
        if (!isUnlocked) {
            val trapIntent = Intent(this, PersistentLockActivity::class.java)
            trapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(trapIntent)
        }
    }

    // Prevent turning down the siren/system volume while locked
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}